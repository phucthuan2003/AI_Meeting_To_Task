package vn.aimtt;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import vn.aimtt.sync.SyncItemStore;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** SDS §14.5 Trello vertical slice + reliability cases AT05–06, AT18–27 against a real DB and an HTTP Trello double. */
@SpringBootTest
@AutoConfigureMockMvc
class TrelloSyncIntegrationTest extends IntegrationSupport {
    static final String TEXT = "Nam: Mai hoàn thành màn hình đăng nhập trước thứ Sáu.\nNam: Long sửa Payment API.\nNam: Gửi biên bản họp.";
    @MockitoSpyBean SyncItemStore items;

    @BeforeEach void resetTrello() {
        TRELLO.reset();
        // Items left in flight by another test must not be processed against this test's fake Trello state.
        jdbc.update("update sync_items set status = 'SUPERSEDED', lease_owner = null, lease_expires_at = null where status in ('QUEUED','SYNCING','UNKNOWN')");
    }

    Meeting standard(String token) throws Exception {
        return analysed(token, TEXT, "2026-10-09", s -> String.join(",",
                create("t1", s.get(0), "Hoàn thành màn hình đăng nhập", "Mai", "trước thứ Sáu"),
                create("t2", s.get(1), "Sửa Payment API", "Long", null),
                create("t3", s.get(2), "Gửi biên bản họp", null, null)));
    }

    @Test void tokenConnectionListsBoardsWithoutExposingSecretsAndChecksOwnership() throws Exception {
        String token = user(), other = user();
        assertThat(body(call(token, post("/api/v1/trello/connections/token"), Map.of("token", "wrongtoken0000000000000000000000")).andExpect(status().isUnprocessableEntity()))
                .get("code").asText()).isEqualTo("TRELLO_TOKEN_INVALID");
        String connection = connectToken(token);
        var list = ok(call(token, get("/api/v1/trello/connections"), null));
        assertThat(list).hasSize(1);
        assertThat(list.toString()).doesNotContain("validtoken").doesNotContain("access").contains("\"status\":\"ACTIVE\"");
        String stored = jdbc.queryForObject("select access_token_enc from trello_connections where id = ?::uuid", String.class, connection);
        assertThat(stored).startsWith("v1:").doesNotContain("validtoken");
        assertThat(ok(call(token, get("/api/v1/trello/connections/{id}/boards", connection), null))).hasSize(2);
        assertThat(ok(call(token, get("/api/v1/trello/boards/{b}/lists", FakeTrello.BOARD).param("connectionId", connection), null)).get(0).get("id").asText()).isEqualTo(FakeTrello.LIST);
        assertThat(ok(call(token, get("/api/v1/trello/boards/{b}/members", FakeTrello.BOARD).param("connectionId", connection), null))).hasSize(4);
        call(other, get("/api/v1/trello/connections/{id}/boards", connection), null).andExpect(status().isNotFound());
        call(token, get("/api/v1/trello/boards/{b}/lists", "../../x").param("connectionId", connection), null).andExpect(status().is4xxClientError());
    }

    @Test void destinationValidationVersionAndMemberSuggestionsNeverAutoAssign() throws Exception {
        String token = user();
        var meeting = standard(token);
        String connection = connectToken(token);
        call(token, put("/api/v1/meetings/{id}/destination", meeting.id()), Map.of("connectionId", connection, "boardId", FakeTrello.BOARD, "listId", FakeTrello.OTHER_LIST, "expectedVersion", 0))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("LIST_NOT_IN_BOARD"));
        call(token, put("/api/v1/meetings/{id}/destination", meeting.id()), Map.of("connectionId", connection, "boardId", FakeTrello.BOARD, "listId", FakeTrello.CLOSED_LIST, "expectedVersion", 0))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DESTINATION_CLOSED"));
        var dest = destination(token, meeting.id(), connection, 0);
        assertThat(dest.get("version").asLong()).isEqualTo(1);
        assertThat(dest.get("members")).hasSize(4);
        call(token, put("/api/v1/meetings/{id}/destination", meeting.id()), Map.of("connectionId", connection, "boardId", FakeTrello.BOARD, "listId", FakeTrello.LIST, "expectedVersion", 0))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_VERSION"));

        var resolved = ok(call(token, post("/api/v1/meetings/{id}/resolve-members", meeting.id()), Map.of("expectedDestinationVersion", 1)));
        var byName = new HashMap<String, JsonNode>(); resolved.forEach(r -> byName.put(Optional.ofNullable(r.get("assigneeRaw").textValue()).orElse("-"), r));
        assertThat(byName.get("Mai").get("memberResolution").asText()).isEqualTo("SUGGESTED");      // AT06
        assertThat(byName.get("Mai").get("trelloMemberId").asText()).isEqualTo(FakeTrello.MAI);
        assertThat(byName.get("Long").get("memberResolution").asText()).isEqualTo("AMBIGUOUS");     // AT05
        assertThat(byName.get("Long").get("candidates")).hasSize(2);
        assertThat(byName.get("Long").get("trelloMemberId").isNull()).isTrue();
        var mai = task(token, meeting.id(), "Hoàn thành màn hình đăng nhập");
        assertThat(mai.get("readyForApproval").asBoolean()).isFalse();
        assertThat(mai.get("warnings").toString()).contains("ASSIGNEE_SUGGESTED", "Nguyễn Thị Mai");

        // Confirm the suggestion explicitly and remember the alias for this Board.
        var confirmed = ok(call(token, patch("/api/v1/tasks/{id}", mai.get("taskId").asText()), Map.of("expectedVersion", mai.get("version").asLong(),
                "memberDecision", "RESOLVED", "rememberAlias", true)));
        assertThat(confirmed.get("memberResolution").asText()).isEqualTo("RESOLVED");
        assertThat(confirmed.get("trelloMemberName").asText()).isEqualTo("Nguyễn Thị Mai");
        call(token, patch("/api/v1/tasks/{id}", confirmed.get("taskId").asText()), Map.of("expectedVersion", confirmed.get("version").asLong(), "trelloMemberId", "m000000000000000000000zz", "memberDecision", "RESOLVED"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("MEMBER_NOT_IN_BOARD"));

        // A confirmed alias resolves the same spoken name on the same Board for a later meeting.
        var second = standard(token);
        destination(token, second.id(), connection, 0);
        var again = ok(call(token, post("/api/v1/meetings/{id}/resolve-members", second.id()), Map.of("expectedDestinationVersion", 1)));
        boolean aliasUsed = false; for (var r : again) if ("Mai".equals(r.get("assigneeRaw").textValue())) aliasUsed = r.get("memberResolution").asText().equals("RESOLVED");
        assertThat(aliasUsed).isTrue();

        // AT18: switching Board increases the destination version and drops members that are not on the new Board.
        var moved = ok(call(token, put("/api/v1/meetings/{id}/destination", meeting.id()), Map.of("connectionId", connection, "boardId", FakeTrello.OTHER_BOARD,
                "listId", FakeTrello.OTHER_LIST, "expectedVersion", 1)));
        assertThat(moved.get("version").asLong()).isEqualTo(2);
        var afterMove = task(token, meeting.id(), "Hoàn thành màn hình đăng nhập");
        assertThat(afterMove.get("memberResolution").asText()).isEqualTo("MISSING");
        assertThat(afterMove.get("version").asLong()).isGreaterThan(confirmed.get("version").asLong());

        var deadlines = ok(call(token, post("/api/v1/meetings/{id}/resolve-deadlines", meeting.id()), null));
        assertThat(deadlines.get(0).get("suggestedDueLocal").asText()).isEqualTo("2026-10-16T17:00");
        assertThat(deadlines.get(0).get("note").asText()).contains("trước");
    }

    @Test void approveCreatesSnapshotsAndCardsWithFullPayloadAndIsIdempotent() throws Exception {
        String token = user();
        var meeting = standard(token);
        String connection = connectToken(token);
        destination(token, meeting.id(), connection, 0);
        var t1 = ready(token, task(token, meeting.id(), "Hoàn thành màn hình đăng nhập"), FakeTrello.MAI);
        var t3 = ready(token, task(token, meeting.id(), "Gửi biên bản họp"), null);
        var t2 = task(token, meeting.id(), "Sửa Payment API");
        // Preview equals what will be sent.
        var preview = ok(call(token, get("/api/v1/tasks/{id}/card-preview", t1.get("taskId").asText()), null));
        assertThat(preview.get("payload").get("idMembers").get(0).asText()).isEqualTo(FakeTrello.MAI);
        assertThat(preview.get("payload").get("due").asText()).isEqualTo("2026-10-16T10:00:00Z");
        assertThat(preview.get("payload").get("desc").asText()).contains("AI_MTT_REF=" + t1.get("taskId").asText()).contains("Hạn theo cuộc họp");

        // All-or-none: an unresolved task blocks the whole request.
        var rejected = body(call(token, post("/api/v1/meetings/{id}/approve-and-sync", meeting.id()).header("Idempotency-Key", "approve-key-0001"),
                Map.of("expectedDestinationVersion", 1, "tasks", List.of(Map.of("taskId", t1.get("taskId").asText(), "expectedVersion", t1.get("version").asLong()),
                        Map.of("taskId", t2.get("taskId").asText(), "expectedVersion", t2.get("version").asLong())))).andExpect(status().isUnprocessableEntity()));
        assertThat(rejected.get("details").toString()).contains("UNRESOLVED_MEMBER", "UNRESOLVED_DEADLINE");
        assertThat(jdbc.queryForObject("select count(*) from sync_items i join tasks t on t.id = i.task_id where t.meeting_id = ?::uuid", Integer.class, meeting.id())).isZero();

        var accepted = approve(token, meeting.id(), 1, List.of(t1, t3), "approve-key-0002");
        assertThat(accepted.get("taskCount").asInt()).isEqualTo(2);
        // AT19: same key + same body returns the same job; same key + other body conflicts.
        assertThat(approve(token, meeting.id(), 1, List.of(t1, t3), "approve-key-0002").get("syncJobId")).isEqualTo(accepted.get("syncJobId"));
        call(token, post("/api/v1/meetings/{id}/approve-and-sync", meeting.id()).header("Idempotency-Key", "approve-key-0002"),
                Map.of("expectedDestinationVersion", 1, "tasks", List.of(Map.of("taskId", t1.get("taskId").asText(), "expectedVersion", t1.get("version").asLong()))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        // Approved/queued tasks are locked for editing and destination changes are blocked while in flight.
        call(token, patch("/api/v1/tasks/{id}", t1.get("taskId").asText()), Map.of("expectedVersion", t1.get("version").asLong() + 1, "taskName", "x"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TASK_NOT_EDITABLE"));
        call(token, put("/api/v1/meetings/{id}/destination", meeting.id()), Map.of("connectionId", connection, "boardId", FakeTrello.BOARD, "listId", FakeTrello.LIST, "expectedVersion", 1))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_BUSY"));

        drainSync();
        var job = syncJob(token, accepted.get("syncJobId").asText());
        assertThat(job.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(job.get("summary").get("synced").asInt()).isEqualTo(2);
        assertThat(TRELLO.cards).hasSize(2);
        var card = TRELLO.cards.stream().filter(c -> c.get("name").equals("Hoàn thành màn hình đăng nhập")).findFirst().orElseThrow();
        assertThat(card.get("idMembers")).isEqualTo(FakeTrello.MAI);
        assertThat(card.get("due")).isEqualTo("2026-10-16T10:00:00Z");
        assertThat(card.get("desc").toString()).contains("AI_MTT_REF=" + t1.get("taskId").asText()).doesNotContain("Trích dẫn");
        var after = task(token, meeting.id(), "Hoàn thành màn hình đăng nhập");
        assertThat(after.get("syncStatus").asText()).isEqualTo("SYNCED");
        assertThat(after.get("trelloCardUrl").asText()).startsWith("https://trello.com/c/");
        assertThat(after.get("sync").get("actions").toString()).contains("OPEN_CARD");
        // AT24: retry on a synced item returns the result and never creates another card.
        ok(call(token, post("/api/v1/sync-items/{id}/retry", job.get("results").get(0).get("syncItemId").asText()), null));
        drainSync();
        assertThat(TRELLO.cards).hasSize(2);
        assertThat(TRELLO.createCalls.get()).isEqualTo(2);
        // A synced task cannot be approved again.
        var resend = body(call(token, post("/api/v1/meetings/{id}/approve-and-sync", meeting.id()).header("Idempotency-Key", "approve-key-0003"),
                Map.of("expectedDestinationVersion", 1, "tasks", List.of(Map.of("taskId", after.get("taskId").asText(), "expectedVersion", after.get("version").asLong())))));
        assertThat(resend.get("details").toString()).contains("ALREADY_SYNCED");
        // Latest job is restorable for the reopened panel.
        assertThat(ok(call(token, get("/api/v1/meetings/{id}", meeting.id()), null)).get("latestSyncJobId").asText()).isEqualTo(accepted.get("syncJobId").asText());
    }

    @Test void concurrentApprovalsWithDifferentKeysCreateOnlyOneItem() throws Exception {   // AT20
        String token = user();
        var meeting = standard(token);
        destination(token, meeting.id(), connectToken(token), 0);
        var t3 = ready(token, task(token, meeting.id(), "Gửi biên bản họp"), null);
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<Integer>>();
            for (String key : List.of("parallel-key-0001", "parallel-key-0002")) futures.add(pool.submit(() -> {
                start.await();
                return call(token, post("/api/v1/meetings/{id}/approve-and-sync", meeting.id()).header("Idempotency-Key", key),
                        Map.of("expectedDestinationVersion", 1, "tasks", List.of(Map.of("taskId", t3.get("taskId").asText(), "expectedVersion", t3.get("version").asLong()))))
                        .andReturn().getResponse().getStatus();
            }));
            start.countDown();
            var statuses = new ArrayList<Integer>(); for (var f : futures) statuses.add(f.get(30, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(202, 409);
        } finally { pool.shutdownNow(); }
        assertThat(jdbc.queryForObject("select count(*) from sync_items where task_id = ?::uuid", Integer.class, t3.get("taskId").asText())).isEqualTo(1);
        drainSync();
        assertThat(TRELLO.cards).hasSize(1);
    }

    @Test void timeoutAfterDispatchBecomesUnknownAndIsReconciledNotRecreated() throws Exception {   // AT22
        String token = user();
        var meeting = standard(token);
        destination(token, meeting.id(), connectToken(token), 0);
        var t3 = ready(token, task(token, meeting.id(), "Gửi biên bản họp"), null);
        var accepted = approve(token, meeting.id(), 1, List.of(t3), "timeout-key-0001");
        TRELLO.fault(FakeTrello.Fault.TIMEOUT_AFTER_CREATE, 1);
        syncRunner.runOnce();
        var job = syncJob(token, accepted.get("syncJobId").asText());
        var item = job.get("results").get(0);
        assertThat(item.get("status").asText()).isEqualTo("UNKNOWN");
        assertThat(item.get("errorCode").asText()).isEqualTo("DISPATCH_TIMEOUT");
        assertThat(job.get("status").asText()).isEqualTo("NEEDS_ACTION");
        assertThat(item.get("actions").toString()).contains("RECONCILE", "LINK_CARD").doesNotContain("RETRY").doesNotContain("RECREATE");
        call(token, post("/api/v1/sync-items/{id}/retry", item.get("syncItemId").asText()), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECONCILIATION_REQUIRED"));
        call(token, post("/api/v1/sync-items/{id}/recreate", item.get("syncItemId").asText()), Map.of("expectedVersion", 99, "acknowledgementDuplicateRisk", true))
                .andExpect(status().isConflict());
        call(token, patch("/api/v1/meetings/{id}/input", meeting.id()), Map.of("expectedVersion", 0, "transcriptText", "x")).andExpect(status().isConflict());
        Thread.sleep(1600); // let the fake finish the slow response
        var reconciled = ok(call(token, post("/api/v1/sync-items/{id}/reconcile", item.get("syncItemId").asText()), null));
        assertThat(reconciled.get("results").get(0).get("status").asText()).isEqualTo("SYNCED");
        assertThat(reconciled.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(TRELLO.cards).hasSize(1);
        assertThat(TRELLO.createCalls.get()).isEqualTo(1);
    }

    @Test void serverErrorAndLostDbWriteAfterSuccessAreUnknownUntilReconciled() throws Exception {   // AT21, AT23
        String token = user();
        var meeting = standard(token);
        destination(token, meeting.id(), connectToken(token), 0);
        var t1 = ready(token, task(token, meeting.id(), "Hoàn thành màn hình đăng nhập"), null);
        var t3 = ready(token, task(token, meeting.id(), "Gửi biên bản họp"), null);
        var accepted = approve(token, meeting.id(), 1, List.of(t1, t3), "lost-write-key-1");
        // First item: Trello answers 502 after creating the card.
        TRELLO.fault(FakeTrello.Fault.SERVER_ERROR_AFTER_CREATE, 1);
        syncRunner.runOnce();
        // Second item: card created but the DB write of the success fails.
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("simulated")).when(items).complete(any(), any(), any(), eq("DISPATCH"));
        assertThatThrownBy(() -> syncRunner.runOnce()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        reset(items);
        var stuck = jdbc.queryForList("select status, dispatch_state from sync_items where sync_job_id = ?::uuid and status = 'SYNCING'", accepted.get("syncJobId").asText());
        assertThat(stuck).hasSize(1);
        assertThat(stuck.get(0).get("dispatch_state")).isEqualTo("DISPATCHED");
        // Worker died after dispatch: lease expiry must not re-create.
        jdbc.update("update sync_items set lease_expires_at = now() - interval '1 second' where sync_job_id = ?::uuid and status = 'SYNCING'", UUID.fromString(accepted.get("syncJobId").asText()));
        items.recover();
        var job = syncJob(token, accepted.get("syncJobId").asText());
        assertThat(job.get("summary").get("unknown").asInt()).isEqualTo(2);
        assertThat(job.get("results").toString()).contains("TRELLO_SERVER_ERROR_AFTER_DISPATCH", "WORKER_LOST_AFTER_DISPATCH");
        assertThat(TRELLO.createCalls.get()).isEqualTo(2);
        jdbc.update("update sync_items set next_retry_at = now() where sync_job_id = ?::uuid", UUID.fromString(accepted.get("syncJobId").asText()));
        drainSync();   // automatic reconciliation finds both cards
        job = syncJob(token, accepted.get("syncJobId").asText());
        assertThat(job.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(TRELLO.cards).hasSize(2);
        assertThat(TRELLO.createCalls.get()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from sync_attempts a join sync_items i on i.id = a.sync_item_id where i.sync_job_id = ?::uuid and a.phase = 'RECOVERY'",
                Integer.class, UUID.fromString(accepted.get("syncJobId").asText()))).isEqualTo(1);
    }

    @Test void partialBatchKeepsSuccessAndFailedTaskCanBeEditedAndReapproved() throws Exception {   // AT25
        String token = user();
        var meeting = standard(token);
        destination(token, meeting.id(), connectToken(token), 0);
        var t1 = ready(token, task(token, meeting.id(), "Hoàn thành màn hình đăng nhập"), null);
        var t3 = ready(token, task(token, meeting.id(), "Gửi biên bản họp"), null);
        var accepted = approve(token, meeting.id(), 1, List.of(t1, t3), "partial-key-0001");
        TRELLO.fault(FakeTrello.Fault.REJECT, 1);
        drainSync();
        var job = syncJob(token, accepted.get("syncJobId").asText());
        assertThat(job.get("status").asText()).isEqualTo("PARTIAL_FAILED");
        var failed = job.get("results").get(0).get("status").asText().equals("FAILED") ? job.get("results").get(0) : job.get("results").get(1);
        assertThat(failed.get("errorCode").asText()).isEqualTo("TRELLO_REJECTED");
        assertThat(failed.get("actions").toString()).contains("EDIT").doesNotContain("RETRY");
        call(token, post("/api/v1/sync-items/{id}/retry", failed.get("syncItemId").asText()), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_RETRYABLE"));
        assertThat(TRELLO.cards).hasSize(1);
        // Edit after definite failure → back to review, old item superseded, re-approve creates exactly one more card.
        var failedTask = ok(call(token, get("/api/v1/meetings/{id}/tasks", meeting.id()), null));
        JsonNode target = null; for (var t : failedTask.get("tasks")) if (t.get("taskId").asText().equals(failed.get("taskId").asText())) target = t;
        assertThat(target.get("syncStatus").asText()).isEqualTo("FAILED");
        assertThat(target.get("editable").asBoolean()).isTrue();
        var edited = ok(call(token, patch("/api/v1/tasks/{id}", target.get("taskId").asText()), Map.of("expectedVersion", target.get("version").asLong(), "taskName", "Tên đã sửa")));
        assertThat(edited.get("reviewStatus").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(edited.get("syncStatus").asText()).isEqualTo("NOT_SYNCED");
        assertThat(syncJob(token, accepted.get("syncJobId").asText()).get("status").asText()).isEqualTo("COMPLETED");
        approve(token, meeting.id(), 1, List.of(edited), "partial-key-0002");
        drainSync();
        assertThat(TRELLO.cards).hasSize(2);
        assertThat(task(token, meeting.id(), "Tên đã sửa").get("syncStatus").asText()).isEqualTo("SYNCED");
    }

    @Test void transientRateLimitRetriesWithinBudgetButRevokedTokenNeedsReconnect() throws Exception {   // AT26
        String token = user();
        var meeting = standard(token);
        String connection = connectToken(token);
        destination(token, meeting.id(), connection, 0);
        var t3 = ready(token, task(token, meeting.id(), "Gửi biên bản họp"), null);
        var t1 = ready(token, task(token, meeting.id(), "Hoàn thành màn hình đăng nhập"), null);
        TRELLO.fault(FakeTrello.Fault.RATE_LIMIT, 1);
        var first = approve(token, meeting.id(), 1, List.of(t3), "rate-key-00001");
        drainSync();
        assertThat(syncJob(token, first.get("syncJobId").asText()).get("status").asText()).isEqualTo("COMPLETED");
        assertThat(TRELLO.cards).hasSize(1);

        TRELLO.validTokens.clear();   // token revoked on Trello
        var second = approve(token, meeting.id(), 1, List.of(t1), "revoke-key-0001");
        drainSync();
        var job = syncJob(token, second.get("syncJobId").asText());
        assertThat(job.get("results").get(0).get("status").asText()).isEqualTo("FAILED");
        assertThat(job.get("results").get(0).get("errorCode").asText()).isEqualTo("TRELLO_REAUTH_REQUIRED");
        assertThat(jdbc.queryForObject("select status from trello_connections where id = ?::uuid", String.class, connection)).isEqualTo("REAUTH_REQUIRED");
        assertThat(TRELLO.cards).hasSize(1);
        call(token, post("/api/v1/sync-items/{id}/retry", job.get("results").get(0).get("syncItemId").asText()), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TRELLO_REAUTH_REQUIRED"));
        // Reconnect the same identity → retry the same snapshot → one card.
        TRELLO.validTokens.add("validtoken000000000000000000000001");
        connectToken(token);
        ok(call(token, post("/api/v1/sync-items/{id}/retry", job.get("results").get(0).get("syncItemId").asText()), null));
        drainSync();
        assertThat(syncJob(token, second.get("syncJobId").asText()).get("status").asText()).isEqualTo("COMPLETED");
        assertThat(TRELLO.cards).hasSize(2);
    }

    @Test void notFoundAfterReconcileAllowsAuditedRecreateAndLinkingRequiresSameBoard() throws Exception {
        String token = user();
        var meeting = standard(token);
        destination(token, meeting.id(), connectToken(token), 0);
        var t1 = ready(token, task(token, meeting.id(), "Hoàn thành màn hình đăng nhập"), null);
        var t3 = ready(token, task(token, meeting.id(), "Gửi biên bản họp"), null);
        var accepted = approve(token, meeting.id(), 1, List.of(t1, t3), "recreate-key-001");
        TRELLO.fault(FakeTrello.Fault.TIMEOUT_AFTER_CREATE, 2);
        syncRunner.runOnce(); syncRunner.runOnce();
        Thread.sleep(1600);
        TRELLO.cards.clear(); // the cards never really appeared on the Board
        var job = syncJob(token, accepted.get("syncJobId").asText());
        String a = job.get("results").get(0).get("syncItemId").asText(), b = job.get("results").get(1).get("syncItemId").asText();
        for (int i = 0; i < 3; i++) ok(call(token, post("/api/v1/sync-items/{id}/reconcile", a), null));
        var afterReconcile = syncJob(token, accepted.get("syncJobId").asText());
        var itemA = afterReconcile.get("results").get(0);
        assertThat(itemA.get("errorCode").asText()).isEqualTo("NOT_FOUND_AFTER_RECONCILE");
        assertThat(itemA.get("actions").toString()).contains("RECREATE");
        String taskA = itemA.get("taskId").asText();
        long version = jdbc.queryForObject("select version from tasks where id = ?::uuid", Long.class, taskA);
        call(token, post("/api/v1/sync-items/{id}/recreate", a), Map.of("expectedVersion", version)).andExpect(status().isBadRequest());
        ok(call(token, post("/api/v1/sync-items/{id}/recreate", a), Map.of("expectedVersion", version, "acknowledgementDuplicateRisk", true)));
        drainSync();
        assertThat(TRELLO.cards).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_events where task_id = ?::uuid and event_type = 'RECREATE_AFTER_RECONCILE'", Integer.class, taskA)).isEqualTo(1);

        // Item B: the user finds the card on Trello and links it; cards on another Board are refused.
        TRELLO.cards.add(new LinkedHashMap<>(Map.of("id", "c99999999999999999999999", "shortLink", "zzzzzzzz", "url", "https://trello.com/c/zzzzzzzz/9",
                "shortUrl", "https://trello.com/c/zzzzzzzz", "name", "x", "desc", "no marker", "idList", FakeTrello.OTHER_LIST, "idBoard", FakeTrello.OTHER_BOARD, "closed", false)));
        call(token, post("/api/v1/sync-items/{id}/link-card", b), Map.of("card", "https://trello.com/c/zzzzzzzz/9"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CARD_NOT_ON_BOARD"));
        String taskB = afterReconcile.get("results").get(1).get("taskId").asText();
        TRELLO.cards.add(new LinkedHashMap<>(Map.of("id", "c88888888888888888888888", "shortLink", "yyyyyyyy", "url", "https://trello.com/c/yyyyyyyy/8",
                "shortUrl", "https://trello.com/c/yyyyyyyy", "name", "x", "desc", "AI_MTT_REF=" + taskB, "idList", FakeTrello.LIST, "idBoard", FakeTrello.BOARD, "closed", false)));
        var linked = ok(call(token, post("/api/v1/sync-items/{id}/link-card", b), Map.of("card", "https://trello.com/c/yyyyyyyy/8")));
        assertThat(linked.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select trello_card_id from tasks where id = ?::uuid", String.class, taskB)).isEqualTo("c88888888888888888888888");
    }

    @Test void otherUsersCannotSeeOrActOnSyncResources() throws Exception {   // AT27
        String token = user(), other = user();
        var meeting = standard(token);
        String connection = connectToken(token);
        destination(token, meeting.id(), connection, 0);
        var t3 = ready(token, task(token, meeting.id(), "Gửi biên bản họp"), null);
        var accepted = approve(token, meeting.id(), 1, List.of(t3), "owner-key-00001");
        String item = jdbc.queryForObject("select id from sync_items where sync_job_id = ?::uuid", String.class, accepted.get("syncJobId").asText());
        call(other, get("/api/v1/sync-jobs/{id}", accepted.get("syncJobId").asText()), null).andExpect(status().isNotFound());
        for (String action : List.of("retry", "reconcile")) call(other, post("/api/v1/sync-items/{id}/" + action, item), null).andExpect(status().isNotFound());
        call(other, post("/api/v1/sync-items/{id}/link-card", item), Map.of("card", "abcdefgh")).andExpect(status().isNotFound());
        call(other, get("/api/v1/meetings/{id}/destination", meeting.id()), null).andExpect(status().isNotFound());
        call(other, put("/api/v1/meetings/{id}/destination", meeting.id()), Map.of("connectionId", connection, "boardId", FakeTrello.BOARD, "listId", FakeTrello.LIST, "expectedVersion", 1))
                .andExpect(status().isNotFound());
        call(other, delete("/api/v1/trello/connections/{id}", connection), null).andExpect(status().isNotFound());
        call(other, get("/api/v1/tasks/{id}/card-preview", t3.get("taskId").asText()), null).andExpect(status().isNotFound());
        call(other, post("/api/v1/meetings/{id}/approve-and-sync", meeting.id()).header("Idempotency-Key", "owner-key-00002"),
                Map.of("expectedDestinationVersion", 1, "tasks", List.of())).andExpect(status().is4xxClientError());
    }

    @Test void oauthTransactionIsSingleUseAndPkceBoundAndRefreshRotatesTokens() throws Exception {
        String token = user();
        var start = ok(call(token, post("/api/v1/trello/connections/authorize"), null));
        String url = start.get("authorizationUrl").asText();
        var query = FakeTrello.query(url);
        assertThat(query).containsEntry("code_challenge_method", "S256").containsEntry("response_type", "code").containsKey("state").containsKey("code_challenge");
        assertThat(url).doesNotContain("client-secret-test");
        mvc.perform(get("/api/v1/trello/oauth/callback").param("code", "abc").param("state", "forged")).andExpect(status().isBadRequest());
        TRELLO.codeChallenges.put("code-1", query.get("code_challenge"));
        mvc.perform(get("/api/v1/trello/oauth/callback").param("code", "code-1").param("state", query.get("state"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Đã kết nối Trello")));
        var tx = ok(call(token, get("/api/v1/trello/authorizations/{id}", start.get("transactionId").asText()), null));
        assertThat(tx.get("status").asText()).isEqualTo("COMPLETED");
        // Replay of the same state is refused.
        TRELLO.codeChallenges.put("code-2", query.get("code_challenge"));
        mvc.perform(get("/api/v1/trello/oauth/callback").param("code", "code-2").param("state", query.get("state"))).andExpect(status().isBadRequest());
        var connections = ok(call(token, get("/api/v1/trello/connections"), null));
        assertThat(connections.get(0).get("authType").asText()).isEqualTo("OAUTH2");
        String connection = connections.get(0).get("connectionId").asText();
        // Denied consent ends the transaction without touching the connection.
        var denied = ok(call(token, post("/api/v1/trello/connections/authorize"), null));
        mvc.perform(get("/api/v1/trello/oauth/callback").param("error", "access_denied").param("state", FakeTrello.query(denied.get("authorizationUrl").asText()).get("state")))
                .andExpect(status().isBadRequest());
        assertThat(ok(call(token, get("/api/v1/trello/authorizations/{id}", denied.get("transactionId").asText()), null)).get("status").asText()).isEqualTo("DENIED");
        // Expired access token → one serialized refresh, then the call succeeds.
        jdbc.update("update trello_connections set expires_at = now() - interval '1 minute' where id = ?::uuid", UUID.fromString(connection));
        int before = TRELLO.tokenCalls.get();
        assertThat(ok(call(token, get("/api/v1/trello/connections/{id}/boards", connection), null))).hasSize(2);
        assertThat(TRELLO.tokenCalls.get()).isEqualTo(before + 1);
        // Refresh token already used elsewhere → REAUTH_REQUIRED, no secret in the error.
        TRELLO.refreshTokens.clear();
        jdbc.update("update trello_connections set expires_at = now() - interval '1 minute' where id = ?::uuid", UUID.fromString(connection));
        var failed = body(call(token, get("/api/v1/trello/connections/{id}/boards", connection), null).andExpect(status().isConflict()));
        assertThat(failed.get("code").asText()).isEqualTo("TRELLO_REAUTH_REQUIRED");
        assertThat(ok(call(token, get("/api/v1/trello/connections"), null)).get(0).get("status").asText()).isEqualTo("REAUTH_REQUIRED");
        // Disconnect clears the stored tokens.
        call(token, delete("/api/v1/trello/connections/{id}", connection), null).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select access_token_enc is null and refresh_token_enc is null from trello_connections where id = ?::uuid", Boolean.class, connection)).isTrue();
    }
}
