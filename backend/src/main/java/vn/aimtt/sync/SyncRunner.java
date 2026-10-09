package vn.aimtt.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vn.aimtt.common.ApiException;
import vn.aimtt.trello.*;
import vn.aimtt.trello.TrelloClient.*;

/**
 * Processes one sync item from its immutable snapshot (SDS §5.15): re-validate destination and members without
 * changing them, mark DISPATCHED, send one create with the complete payload, store the card ID immediately.
 */
@Component
public class SyncRunner {
    private static final Logger log = LoggerFactory.getLogger(SyncRunner.class);
    private final SyncItemStore store;
    private final TrelloClient client;
    private final TrelloConnectionService connections;
    private final Reconciler reconciler;
    private final SyncProperties properties;
    private final ObjectMapper json;

    public SyncRunner(SyncItemStore store, TrelloClient client, TrelloConnectionService connections, Reconciler reconciler, SyncProperties properties, ObjectMapper json) {
        this.store = store; this.client = client; this.connections = connections; this.reconciler = reconciler; this.properties = properties; this.json = json;
    }

    /** One worker tick: recover expired leases, then process one queued item or one reconciliation. */
    public boolean runOnce() {
        store.recover();
        var lease = store.claim();
        if (lease.isPresent()) { process(lease.get()); return true; }
        var reconcile = store.claimReconcile();
        if (reconcile.isPresent()) { reconciler.reconcile(reconcile.get(), false); return true; }
        return false;
    }

    void process(SyncItemStore.Lease lease) {
        var item = store.item(lease.itemId());
        var snapshot = store.snapshot(item.snapshotId());
        CardPayloads.Payload payload;
        try { payload = json.readValue(snapshot.payloadJson(), CardPayloads.Payload.class); }
        catch (Exception e) { store.finishNotDispatched(lease, "SNAPSHOT_INVALID", false, false, null, null, "VALIDATE"); return; }
        var connection = connections.activeFor(snapshot.owner(), snapshot.trelloIdentity());
        if (connection.isEmpty()) { store.finishNotDispatched(lease, "TRELLO_REAUTH_REQUIRED", true, false, null, null, "VALIDATE"); return; }

        // 1. Validate only — never substitute another member or destination (SDS §5.15).
        try {
            String problem = connections.call(connection.get(), cred -> {
                var list = client.list(cred, snapshot.listId());
                if (list.closed() || !snapshot.boardId().equals(list.idBoard())) return "DESTINATION_INVALID";
                if (client.board(cred, snapshot.boardId()).closed()) return "DESTINATION_INVALID";
                if (!payload.idMembers().isEmpty()) {
                    var members = client.members(cred, snapshot.boardId()).stream().map(Member::id).toList();
                    if (!members.containsAll(payload.idMembers())) return "MEMBER_INVALID";
                }
                return null;
            });
            if (problem != null) { store.finishNotDispatched(lease, problem, false, false, null, null, "VALIDATE"); return; }
        } catch (TrelloException e) {
            switch (e.kind()) {
                case AUTH -> store.finishNotDispatched(lease, "TRELLO_REAUTH_REQUIRED", true, false, null, e.status(), "VALIDATE");
                case NOT_FOUND, REJECTED -> store.finishNotDispatched(lease, "DESTINATION_INVALID", false, false, null, e.status(), "VALIDATE");
                case FORBIDDEN -> store.finishNotDispatched(lease, "TRELLO_FORBIDDEN", false, false, null, e.status(), "VALIDATE");
                default -> transientFailure(lease, item, e, "VALIDATE");
            }
            return;
        } catch (ApiException e) {
            store.finishNotDispatched(lease, "TRELLO_REAUTH_REQUIRED", true, false, null, null, "VALIDATE"); return;
        }

        // 2. Commit DISPATCHED before sending; a crash after this point can only become UNKNOWN.
        if (!store.markDispatched(lease)) return;
        Card card;
        try {
            card = connections.call(connection.get(), cred -> client.createCard(cred, new CardRequest(payload.name(), payload.desc(), payload.idList(), payload.idMembers(), payload.due())));
        } catch (TrelloException e) {
            log.warn("Trello create failed syncItemId={} kind={} status={}", item.id(), e.kind(), e.status());
            switch (e.kind()) {
                case AUTH -> store.finishRejected(lease, "TRELLO_REAUTH_REQUIRED", true, e.status());
                case FORBIDDEN -> store.finishRejected(lease, "TRELLO_FORBIDDEN", false, e.status());
                case NOT_FOUND -> store.finishRejected(lease, "DESTINATION_INVALID", false, e.status());
                case REJECTED -> store.finishRejected(lease, "TRELLO_REJECTED", false, e.status());
                case RATE_LIMIT, NOT_SENT -> transientFailure(lease, item, e, "DISPATCH"); // request not processed
                case SERVER -> store.finishUnknown(item.id(), "TRELLO_SERVER_ERROR_AFTER_DISPATCH", e.status());
                case INVALID_RESPONSE -> store.finishUnknown(item.id(), "INVALID_RESPONSE_AFTER_DISPATCH", e.status());
                default -> store.finishUnknown(item.id(), "DISPATCH_TIMEOUT", null);
            }
            return;
        } catch (ApiException e) {
            // Refresh failed before the request was sent.
            store.finishRejected(lease, "TRELLO_REAUTH_REQUIRED", true, null); return;
        } catch (RuntimeException e) {
            log.error("Trello create outcome unknown syncItemId={} type={}", item.id(), e.getClass().getSimpleName());
            store.finishUnknown(item.id(), "DISPATCH_TIMEOUT", null); return;
        }
        // 3. Persist the card ID. If this commit fails the item stays DISPATCHED and lease recovery turns it UNKNOWN (AT21).
        store.complete(item.id(), card.id(), reconciler.safeUrl(card.url()), "DISPATCH");
    }

    private void transientFailure(SyncItemStore.Lease lease, SyncItemStore.Item item, TrelloException e, String phase) {
        boolean again = item.attemptCount() < properties.maxTransientAttempts();
        Duration delay = e.retryAfterSeconds() != null ? Duration.ofSeconds(e.retryAfterSeconds())
                : properties.retryBaseDelay().multipliedBy(1L << Math.min(item.attemptCount(), 6)).plusMillis(new Random().nextInt(500));
        String code = e.kind() == Kind.RATE_LIMIT ? "TRELLO_RATE_LIMIT" : "TRELLO_UNAVAILABLE";
        store.finishNotDispatched(lease, code, true, again, delay, e.status() == 0 ? null : e.status(), phase);
    }
}
