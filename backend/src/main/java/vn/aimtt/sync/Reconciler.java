package vn.aimtt.sync;

import java.net.URI;
import java.util.*;
import org.springframework.stereotype.Component;
import vn.aimtt.trello.*;
import vn.aimtt.trello.TrelloClient.*;

/**
 * Looks for the card of an UNKNOWN item on the destination Board by its reference marker (SDS §5.16).
 * Not finding a card does not prove the create failed; it only advances the bounded reconciliation schedule.
 */
@Component
public class Reconciler {
    public enum Result { FOUND, DUPLICATE, NOT_FOUND, UNAVAILABLE, REAUTH_REQUIRED }
    public record Outcome(Result result, Card card) {}
    private final TrelloClient client;
    private final TrelloConnectionService connections;
    private final SyncItemStore store;
    private final SyncProperties properties;
    private final TrelloProperties trello;

    public Reconciler(TrelloClient client, TrelloConnectionService connections, SyncItemStore store, SyncProperties properties, TrelloProperties trello) {
        this.client = client; this.connections = connections; this.store = store; this.properties = properties; this.trello = trello;
    }

    public Outcome search(SyncItemStore.Snapshot snapshot, String marker) {
        var connection = connections.activeFor(snapshot.owner(), snapshot.trelloIdentity());
        if (connection.isEmpty()) return new Outcome(Result.REAUTH_REQUIRED, null);
        try {
            return connections.call(connection.get(), cred -> {
                var matches = new ArrayList<Card>(); String before = null;
                for (int page = 0; page < properties.reconcilePages(); page++) {
                    var cards = client.boardCards(cred, snapshot.boardId(), before, 1000);
                    for (var card : cards) if (card.desc() != null && containsMarker(card.desc(), marker)) matches.add(card);
                    if (cards.size() < 1000) break;
                    before = cards.get(cards.size() - 1).id();
                }
                if (matches.size() == 1) return new Outcome(Result.FOUND, matches.get(0));
                return new Outcome(matches.isEmpty() ? Result.NOT_FOUND : Result.DUPLICATE, null);
            });
        } catch (TrelloException e) {
            return new Outcome(e.kind() == Kind.AUTH ? Result.REAUTH_REQUIRED : Result.UNAVAILABLE, null);
        } catch (vn.aimtt.common.ApiException e) {
            return new Outcome(Result.REAUTH_REQUIRED, null);
        }
    }
    /** Marker must match exactly, not as a prefix of another task ID. */
    static boolean containsMarker(String desc, String marker) {
        int at = desc.indexOf(marker);
        while (at >= 0) {
            int end = at + marker.length();
            if (end == desc.length() || !Character.isLetterOrDigit(desc.charAt(end)) && desc.charAt(end) != '-') return true;
            at = desc.indexOf(marker, at + 1);
        }
        return false;
    }

    /** Applies one reconciliation round to an UNKNOWN item and returns the outcome. */
    public Result reconcile(UUID itemId, boolean manual) {
        var item = store.item(itemId);
        if (!"UNKNOWN".equals(item.status())) return item.cardId() != null ? Result.FOUND : Result.NOT_FOUND;
        var outcome = search(store.snapshot(item.snapshotId()), item.marker());
        switch (outcome.result()) {
            case FOUND -> store.complete(itemId, outcome.card().id(), safeUrl(outcome.card().url()), "RECONCILE");
            case DUPLICATE -> store.reconcileMiss(itemId, "DUPLICATE_DETECTED", true, java.time.Duration.ofDays(3650));
            case NOT_FOUND -> {
                int next = item.reconcileCount() + 1;
                boolean exhausted = next >= Math.max(1, properties.maxAutoReconcile());
                store.reconcileMiss(itemId, exhausted ? "NOT_FOUND_AFTER_RECONCILE" : "NOT_FOUND_YET", true,
                        exhausted ? java.time.Duration.ofDays(3650) : properties.reconcileDelay().multipliedBy(1L << Math.min(next, 6)));
            }
            case UNAVAILABLE -> store.reconcileMiss(itemId, item.errorCode(), false, properties.reconcileDelay());
            case REAUTH_REQUIRED -> store.reconcileMiss(itemId, item.errorCode(), false, properties.reconcileDelay().multipliedBy(4));
        }
        return outcome.result();
    }

    /** Only Trello card links are stored/opened (SDS §10.4). */
    public String safeUrl(String url) {
        if (url == null) return null;
        try {
            var uri = URI.create(url);
            var hosts = Arrays.stream(Optional.ofNullable(trello.cardUrlHosts()).orElse("trello.com").split(",")).map(String::trim).toList();
            boolean local = trello.allowLocalHttp() && "http".equals(uri.getScheme()) && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()));
            if ((("https".equals(uri.getScheme()) && hosts.contains(uri.getHost())) || local) && uri.getUserInfo() == null) return uri.toString();
        } catch (IllegalArgumentException ignored) { }
        return null;
    }
}
