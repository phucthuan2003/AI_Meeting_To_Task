import test from 'node:test';
import assert from 'node:assert/strict';
import { assertTaskList, createGate, createSaveQueue, draftOf, formatDue, joinDue, manualPayload, patchBody, safeTrelloUrl, splitDue } from '../src/review.mjs';

const task = (overrides = {}) => ({ taskId: 't1', version: 4, taskName: 'Sửa login', description: null, assigneeRaw: 'Long', deadlineRaw: 'thứ Sáu',
  dueLocal: null, timezone: 'Asia/Ho_Chi_Minh', deadlineResolution: 'AMBIGUOUS', memberResolution: 'MISSING', priority: null,
  includeEvidenceInCard: false, reviewStatus: 'PENDING_REVIEW', editable: true, readyForApproval: false, ...overrides });

test('patch body carries expectedVersion, only editable fields and a consistent deadline decision', () => {
  assert.deepEqual(patchBody(task(), { taskName: 'Sửa Login API', reviewStatus: 'APPROVED' }), { expectedVersion: 4, taskName: 'Sửa Login API' });
  assert.deepEqual(patchBody(task(), { dueLocal: '2026-10-16T17:00', deadlineDecision: 'RESOLVED', timezone: 'Asia/Ho_Chi_Minh' }),
    { expectedVersion: 4, dueLocal: '2026-10-16T17:00', deadlineDecision: 'RESOLVED', timezone: 'Asia/Ho_Chi_Minh' });
  // Changing only the zone of a confirmed deadline resends the date so the server recomputes UTC from one version.
  const resolved = task({ deadlineResolution: 'RESOLVED', dueLocal: '2026-10-16T17:00' });
  assert.deepEqual(patchBody(resolved, { timezone: 'Europe/London' }), { expectedVersion: 4, timezone: 'Europe/London', deadlineDecision: 'RESOLVED', dueLocal: '2026-10-16T17:00' });
  assert.deepEqual(patchBody(resolved, { deadlineDecision: 'NONE_SELECTED', dueLocal: null }), { expectedVersion: 4, deadlineDecision: 'NONE_SELECTED' });
  assert.deepEqual(patchBody(resolved, { deadlineDecision: null, dueLocal: null }), { expectedVersion: 4, deadlineDecision: null });
});

test('draft decisions are derived from server resolution states', () => {
  assert.equal(draftOf(task()).deadlineDecision, null);
  assert.equal(draftOf(task({ deadlineResolution: 'NONE_SELECTED' })).deadlineDecision, 'NONE_SELECTED');
  assert.equal(draftOf(task({ memberResolution: 'NONE_SELECTED' })).memberDecision, 'NONE_SELECTED');
  assert.equal(draftOf(task({ memberResolution: 'SUGGESTED' })).memberDecision, null);
});

test('date helpers default to 17:00 and format with weekday, full year and zone', () => {
  assert.deepEqual(splitDue(null), { date: '', time: '17:00' });
  assert.deepEqual(splitDue('2026-10-16T09:30:00'), { date: '2026-10-16', time: '09:30' });
  assert.equal(joinDue('2026-10-16', ''), '2026-10-16T17:00');
  assert.equal(joinDue('', '09:00'), null);
  assert.equal(formatDue('2026-10-12T17:00', 'Asia/Ho_Chi_Minh'), 'Thứ Hai, 12/10/2026 lúc 17:00 (Asia/Ho_Chi_Minh)');
});

test('manual payload needs a name and a timezone for a dated deadline', () => {
  const base = { taskName: '  Gửi biên bản ', description: '', assigneeRaw: ' Nam ', priority: '', date: '2026-10-15', time: '', timezone: '', noDeadline: false, noAssignee: false };
  assert.deepEqual(manualPayload(base, 'Asia/Ho_Chi_Minh'), { taskName: 'Gửi biên bản', assigneeRaw: 'Nam', dueLocal: '2026-10-15T17:00', timezone: 'Asia/Ho_Chi_Minh', deadlineDecision: 'RESOLVED' });
  assert.deepEqual(manualPayload({ ...base, noDeadline: true, noAssignee: true, priority: 'LOW' }, null),
    { taskName: 'Gửi biên bản', assigneeRaw: 'Nam', priority: 'LOW', deadlineDecision: 'NONE_SELECTED', memberDecision: 'NONE_SELECTED' });
  assert.throws(() => manualPayload({ ...base, taskName: '   ' }), error => error.code === 'TASK_NAME_REQUIRED');
  assert.throws(() => manualPayload(base, null), error => error.code === 'UNRESOLVED_DEADLINE');
});

test('task list from another input version is rejected', () => {
  const meeting = { meetingId: 'm', transcriptRevision: 'r2', inputVersion: 2 };
  assert.throws(() => assertTaskList({ meetingId: 'm', transcriptRevision: 'r1', inputVersion: 1, tasks: [] }, meeting), error => error.code === 'STALE_VIEW');
  assert.ok(assertTaskList({ meetingId: 'm', transcriptRevision: 'r2', inputVersion: 2, tasks: [] }, meeting));
});

test('create gate locks for unsaved edits, save errors, unresolved warnings and missing Trello', () => {
  const tasks = [task(), task({ taskId: 't2', readyForApproval: true }), task({ taskId: 't3', reviewStatus: 'REJECTED' })];
  const gate = createGate(tasks, new Set(['t1', 't2', 't3']), { t1: 'saving', t2: 'conflict' });
  assert.equal(gate.count, 2);
  assert.ok(gate.reasons.some(r => r.includes('chờ lưu')) && gate.reasons.some(r => r.includes('xung đột')) && gate.reasons.some(r => r.startsWith('1 task')));
  assert.ok(gate.reasons.some(r => r.includes('Kết nối Trello')) && gate.reasons.some(r => r.includes('Board/List')));
  assert.ok(createGate(tasks, new Set(), {}).reasons[0].includes('Chọn ít nhất'));
  const connection = { status: 'ACTIVE', trelloMemberId: 'me' }, destination = { trelloIdentity: 'me', version: 1 };
  const ok = createGate(tasks, new Set(['t2']), {}, { connection, destination });
  assert.deepEqual(ok.reasons, []); assert.equal(ok.tasks[0].taskId, 't2');
  assert.ok(createGate(tasks, new Set(['t2']), {}, { connection: { ...connection, status: 'REAUTH_REQUIRED' }, destination }).reasons[0].includes('hết quyền'));
  assert.ok(createGate(tasks, new Set(['t2']), {}, { connection, destination: { trelloIdentity: 'other' } }).reasons[0].includes('tài khoản Trello khác'));
});

test('member decision travels with the chosen Trello member; alias flag only with RESOLVED', () => {
  assert.deepEqual(patchBody(task(), { memberDecision: 'RESOLVED', trelloMemberId: 'm1', rememberAlias: true }),
    { expectedVersion: 4, memberDecision: 'RESOLVED', trelloMemberId: 'm1', rememberAlias: true });
  assert.deepEqual(patchBody(task({ memberResolution: 'RESOLVED', trelloMemberId: 'm1' }), { memberDecision: 'NONE_SELECTED', trelloMemberId: null, rememberAlias: true }),
    { expectedVersion: 4, memberDecision: 'NONE_SELECTED' });
  assert.equal(draftOf(task({ memberResolution: 'RESOLVED', trelloMemberId: 'm1' })).trelloMemberId, 'm1');
  assert.equal(draftOf(task({ memberResolution: 'SUGGESTED', trelloMemberId: 'm1' })).trelloMemberId, null);
});

test('only https trello.com (or the loopback demo double) links are rendered as links', () => {
  assert.equal(safeTrelloUrl('https://trello.com/c/abc/1'), 'https://trello.com/c/abc/1');
  assert.equal(safeTrelloUrl('http://127.0.0.1:9999/c/d0000001'), 'http://127.0.0.1:9999/c/d0000001');
  for (const bad of ['javascript:alert(1)', 'http://trello.com/c/x', 'https://trello.com.evil.test/c/x', 'https://user@trello.com/c/x', null]) assert.equal(safeTrelloUrl(bad), null);
});

function fakeClock() {
  const timers = new Map(); let id = 0;
  return { timers, schedule(callback, delay) { timers.set(++id, { callback, delay }); return id; }, unschedule(timer) { timers.delete(timer); },
    async fire() { const [timer, entry] = timers.entries().next().value; timers.delete(timer); await entry.callback(); } };
}
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; }

test('autosave debounces, merges edits and keeps one request in flight with later edits sent afterwards', async () => {
  const clock = fakeClock(); const sent = []; const states = []; const saved = []; let version = 4; const calls = [];
  const queue = createSaveQueue({ ...clock, delayMs: 800, onState: s => states.push(s), onSaved: v => saved.push(v),
    save: pending => { sent.push({ ...pending, expectedVersion: version }); const d = deferred(); calls.push(d); return d.promise; } });
  queue.edit({ taskName: 'A' }); queue.edit({ priority: 'HIGH' });
  assert.equal(clock.timers.size, 1); assert.equal(sent.length, 0);
  const first = queue.flush();
  assert.deepEqual(sent[0], { taskName: 'A', priority: 'HIGH', expectedVersion: 4 });
  queue.edit({ taskName: 'AB' });            // typed while the first save is in flight
  assert.equal(sent.length, 1);
  version = 5; calls[0].resolve({ version: 5 }); await first;
  assert.equal(queue.state(), 'dirty');
  const second = clock.fire();
  assert.deepEqual(sent[1], { taskName: 'AB', expectedVersion: 5 });
  calls[1].resolve({ version: 6 }); await second;
  assert.equal(queue.state(), 'saved'); assert.equal(saved.length, 2);
  assert.deepEqual(states.slice(0, 2), ['dirty', 'saving']);
});

test('version conflict is never retried automatically; user decides to overwrite or discard', async () => {
  const clock = fakeClock(); let attempts = 0;
  const queue = createSaveQueue({ ...clock, onState() {}, onSaved() {}, save: async () => {
    attempts++; if (attempts === 1) throw Object.assign(new Error('stale'), { code: 'STALE_VERSION', status: 409 }); return { version: 9 };
  } });
  queue.edit({ taskName: 'Tab B' }); await queue.flush();
  assert.equal(queue.state(), 'conflict'); assert.deepEqual(queue.pending(), { taskName: 'Tab B' });
  queue.edit({ priority: 'LOW' });
  assert.equal(clock.timers.size, 0, 'no autosave while in conflict');
  await queue.flush(); assert.equal(attempts, 1);
  await queue.overwrite();
  assert.equal(attempts, 2); assert.equal(queue.state(), 'saved');

  const other = createSaveQueue({ ...clock, onState() {}, onSaved() {}, save: async () => { throw Object.assign(new Error('down'), { code: 'NETWORK_ERROR' }); } });
  other.edit({ taskName: 'x' }); await other.flush();
  assert.equal(other.state(), 'error'); assert.deepEqual(other.pending(), { taskName: 'x' });
  other.discard(); assert.equal(other.state(), 'saved'); assert.deepEqual(other.pending(), {});
});
