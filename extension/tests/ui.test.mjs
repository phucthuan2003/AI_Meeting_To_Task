import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, rm } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';

// Exercise React's actual rendering of untrusted API text. No Chrome/DB mock is bundled in dist.
const directory = new URL('../node_modules/.cache/ui-tests/', import.meta.url);
await mkdir(directory, { recursive: true });
const output = new URL('components.mjs', directory);
await build({ entryPoints: [fileURLToPath(new URL('./ui-entry.mjs', import.meta.url))], outfile: fileURLToPath(output),
  bundle: true, platform: 'node', format: 'esm', external: ['react'], logLevel: 'silent' });
const { MeetingDetail, History, ErrorNotice, InputForm, AnalysisJobStatus, ProviderChoice, TaskCard, TaskSummary, CreateBar, AnalysisNote, ManualTaskForm, SyncItem, TrelloConnection, ConfirmCreate } = await import(output.href);
test.after(async () => rm(directory, { recursive: true, force: true }));
const injection = '<img src=x onerror="alert(1)"><script>steal()</script>';
const meeting = { meetingId: 'meeting-1', title: injection, inputVersion: 2, preview: injection,
  characterCount: 5000, segmentCount: 2, warnings: [injection], sourceExpiresAt: '2026-11-09T00:00:00Z' };
const source = { sourceAvailable: true, nextCursor: 0, segments: [{ segmentId: 's1', sequence: 0,
  text: injection, sourceText: injection, normalizedStart: 0, normalizedEnd: 10, sourceLocator: { lineIndex: 0 } }] };

test('transcript/title/warnings/error/history render as text, never executable HTML', () => {
  for (const component of [
    React.createElement(MeetingDetail, { meeting, source }),
    React.createElement(ErrorNotice, { error: { message: injection, details: [{ field: 'title', message: injection }] } }),
    React.createElement(History, { history: { nextCursor: null, items: [{ meetingId: '1', title: injection, inputVersion: 1, createdAt: '2026-10-09T00:00:00Z' }] } }),
  ]) {
    const html = renderToStaticMarkup(component);
    assert.ok(html.includes('&lt;img'));
    assert.ok(!html.includes('<img') && !html.includes('<script'));
  }
});

test('cursor zero offers next-page button; preview limit and source-unavailable are explicit', () => {
  const html = renderToStaticMarkup(React.createElement(MeetingDetail, { meeting, source }));
  assert.ok(html.includes('Xem thêm nội dung'));
  assert.ok(html.includes('4.000 ký tự'));
  const unavailable = renderToStaticMarkup(React.createElement(MeetingDetail, { meeting, source: { sourceAvailable: false } }));
  assert.ok(unavailable.includes('Nguồn hiện không còn khả dụng'));
  assert.ok(!unavailable.includes('Văn bản nguồn'));
});

test('replacement requires complete user input rather than silently writing the truncated preview', () => {
  const html = renderToStaticMarkup(React.createElement(InputForm, {
    initial: { title: 'Planning', meetingDate: '', timezone: '', transcriptText: '' }, version: 2,
  }));
  assert.ok(!html.includes('TXT / DOCX'));
  assert.match(html, /<textarea[^>]*required[^>]*><\/textarea>/);
  assert.ok(html.includes('Lưu revision mới'));
});

test('job progress reports actual counters without fabricated percentage; unavailable provider error is text', () => {
  const job = { jobId: 'job-a', inputVersion: 7, status: 'PROCESSING', stage: 'PREPARING_INPUT', preparedSegments: 2,
    completedChunks: 0, totalChunks: null, attemptCount: 1 };
  const html = renderToStaticMarkup(React.createElement(AnalysisJobStatus, { job, segmentCount: 20 }));
  assert.ok(html.includes('2 / 20 segments'));
  assert.ok(!html.includes('%') && !html.includes('Phần phân tích hoàn tất'));
  const failed = renderToStaticMarkup(React.createElement(AnalysisJobStatus, { job: { ...job, status: 'FAILED',
    error: { code: 'PROVIDER_NOT_CONFIGURED', message: injection } }, segmentCount: 20 }));
  assert.ok(failed.includes('PROVIDER_NOT_CONFIGURED') && failed.includes('&lt;img'));
  assert.ok(!failed.includes('<script'));
});

test('active job disables only input replacement while source reload remains available', () => {
  const html = renderToStaticMarkup(React.createElement(MeetingDetail, { meeting, source, inputLocked: true }));
  assert.match(html, /<button disabled="">Thay input<\/button>/);
  assert.match(html, /<button>Tải lại<\/button>/);
});

test('AI selector offers both choices, marks unconfigured providers and requires explicit selection', () => {
  const policies = [
    { providerId: 'openai', displayName: 'OpenAI', providerReady: false },
    { providerId: 'gemini', displayName: 'Gemini', providerReady: false },
  ];
  const html = renderToStaticMarkup(React.createElement(ProviderChoice, { policies, value: '', onChange() {} }));
  assert.match(html, /<option value="" disabled="" selected="">Chọn OpenAI hoặc Gemini/);
  assert.ok(html.includes('OpenAI — chưa kết nối') && html.includes('Gemini — chưa kết nối'));
  assert.equal((html.match(/<option /g) ?? []).length, 3);
  const locked = renderToStaticMarkup(React.createElement(ProviderChoice, { policies, value: 'gemini', disabled: true }));
  assert.match(locked, /<select disabled="">/);
  assert.match(locked, /<option value="gemini" selected="">/);
});

test('job provider remains visible independently of the next choice and terminal label is not repeated', () => {
  const html = renderToStaticMarkup(React.createElement(AnalysisJobStatus, { segmentCount: 2,
    job: { jobId: 'job-a', providerId: 'openai', status: 'FAILED', stage: 'FAILED', preparedSegments: 2,
      completedChunks: 0, totalChunks: 1, inputVersion: 7, attemptCount: 1 } }));
  assert.ok(html.includes('AI của job: OpenAI'));
  assert.equal((html.match(/Chưa hoàn tất/g) ?? []).length, 1);
});

const aiTask = (overrides = {}) => ({ taskId: 'task-a', meetingId: 'meeting-1', origin: 'AI', analysisJobId: 'job-1', version: 3,
  taskName: injection, description: null, assigneeRaw: 'Mai', trelloMemberId: null, memberResolution: 'MISSING', deadlineRaw: 'trước thứ Sáu',
  dueLocal: null, dueAt: null, timezone: 'Asia/Ho_Chi_Minh', deadlineResolution: 'AMBIGUOUS', priority: null, includeEvidenceInCard: false,
  reviewStatus: 'PENDING_REVIEW', syncStatus: 'NOT_SYNCED', current: true, editable: true, readyForApproval: false, needsConfirmation: true,
  warnings: [{ code: 'DEADLINE_NEEDS_CONFIRMATION', field: 'DEADLINE', message: injection, blocking: true }],
  aiSuggestion: { taskName: 'Sửa login', assigneeRaw: 'Mai', deadlineRaw: 'trước thứ Sáu', priority: null, dueLocal: null, timezone: 'Asia/Ho_Chi_Minh' },
  aiNotes: [], editedFields: [], evidence: [{ segmentId: 's1', sequence: 0, field: 'TASK', quote: injection, sourceAvailable: true }], ...overrides });
const cardProps = task => ({ task, api: {}, token: 't', selected: true, onSelect() {}, onSaved() {}, onStateChange() {}, onConflict() {}, onReject() {}, onRestore() {} });

test('task card escapes AI text, shows server warnings, evidence and provenance badge without auto-creating cards', () => {
  const html = renderToStaticMarkup(React.createElement(TaskCard, cardProps(aiTask())));
  assert.ok(html.includes('&lt;img') && !html.includes('<img') && !html.includes('<script'));
  assert.ok(html.includes('Đề xuất AI') && html.includes('Bằng chứng từ transcript (1)'));
  assert.ok(html.includes('“trước thứ Sáu” · cần xác nhận'));
  assert.ok(html.includes('Mai · chờ đối chiếu thành viên Trello'));
  assert.match(html, /<input type="checkbox" aria-label="[^"]*" checked=""\/>/);
  assert.ok(html.includes('>Sửa<') && html.includes('>Loại bỏ<'));
  const edited = renderToStaticMarkup(React.createElement(TaskCard, cardProps(aiTask({ editedFields: ['taskName'] }))));
  assert.ok(edited.includes('Đã sửa'));
});

test('rejected and non-current tasks cannot be selected or edited; manual task shows no AI evidence', () => {
  const rejected = renderToStaticMarkup(React.createElement(TaskCard, cardProps(aiTask({ reviewStatus: 'REJECTED', editable: false }))));
  assert.ok(rejected.includes('Đã loại bỏ') && rejected.includes('>Khôi phục<'));
  assert.ok(!rejected.includes('type="checkbox"') && !rejected.includes('>Sửa<'));
  const manual = renderToStaticMarkup(React.createElement(TaskCard, cardProps(aiTask({ origin: 'USER', analysisJobId: null, aiSuggestion: null, evidence: [] }))));
  assert.ok(manual.includes('Thủ công') && manual.includes('không có bằng chứng AI'));
});

test('confirmed deadline shows weekday, full year, time and timezone before any card is created', () => {
  const html = renderToStaticMarkup(React.createElement(TaskSummary, { task: aiTask({ deadlineResolution: 'RESOLVED', dueLocal: '2026-10-16T17:00',
    dueAt: '2026-10-16T10:00:00Z', memberResolution: 'NONE_SELECTED', warnings: [] }) }));
  assert.ok(html.includes('Thứ Sáu, 16/10/2026 lúc 17:00 (Asia/Ho_Chi_Minh)'));
  assert.ok(html.includes('Không giao người (ghi chú: Mai)'));
  const none = renderToStaticMarkup(React.createElement(TaskSummary, { task: aiTask({ deadlineResolution: 'NONE_SELECTED', warnings: [] }) }));
  assert.ok(none.includes('Không đặt hạn') && none.includes('Câu gốc về hạn'));
  const pending = renderToStaticMarkup(React.createElement(TaskSummary, { task: aiTask(), saveState: 'dirty' }));
  assert.ok(pending.includes('Chưa lưu') && pending.includes('tính lại sau khi lưu'));
});

test('create button is enabled only without blocking reasons', () => {
  const enabled = renderToStaticMarkup(React.createElement(CreateBar, { gate: { count: 1, reasons: [] }, onCreate() {} }));
  assert.match(enabled, /<button class="primary full">Tạo 1 card trên Trello<\/button>/);
});

test('create button stays locked and explains every reason', () => {
  const html = renderToStaticMarkup(React.createElement(CreateBar, { gate: { count: 2, reasons: ['Còn thay đổi đang chờ lưu.', 'Kết nối Trello'] } }));
  assert.match(html, /<button class="primary full" disabled="">Tạo 2 card trên Trello<\/button>/);
  assert.ok(html.includes('Còn thay đổi đang chờ lưu.'));
});

test('analysis note distinguishes no action items, running and failed analysis', () => {
  const empty = renderToStaticMarkup(React.createElement(AnalysisNote, { aiCount: 0, analysis: { status: 'COMPLETED', providerId: 'openai', model: 'fixture', warnings: [injection] } }));
  assert.ok(empty.includes('Không tìm thấy công việc') && empty.includes('&lt;img'));
  assert.ok(renderToStaticMarkup(React.createElement(AnalysisNote, { analysis: { status: 'PROCESSING' } })).includes('task thủ công vẫn thêm được'));
  assert.ok(renderToStaticMarkup(React.createElement(AnalysisNote, { analysis: { status: 'FAILED' } })).includes('thêm task thủ công'));
  assert.ok(renderToStaticMarkup(React.createElement(AnalysisNote, { analysis: null })).includes('Chưa có lượt phân tích'));
});

test('manual task form needs only a name and defaults the deadline time to 17:00', () => {
  const html = renderToStaticMarkup(React.createElement(ManualTaskForm, { api: {}, token: 't', meeting: { meetingId: 'm', timezone: 'Asia/Ho_Chi_Minh' } }));
  assert.match(html, /<input maxLength="255" required=""/);
  assert.ok(html.includes('value="17:00"') && html.includes('value="Asia/Ho_Chi_Minh"') && html.includes('Không giao người'));
});

test('history shows analysis status and draft counts', () => {
  const html = renderToStaticMarkup(React.createElement(History, { history: { nextCursor: null, items: [{ meetingId: 'm', title: 'Planning', meetingDate: null,
    inputVersion: 1, createdAt: '2026-10-09T00:00:00Z', analysisStatus: 'COMPLETED', pendingTasks: 3, rejectedTasks: 1 }] } }));
  assert.ok(html.includes('Đã phân tích') && html.includes('3 task chờ duyệt') && html.includes('1 đã loại'));
});

test('configured providers are not labelled as disconnected', () => {
  const selector = renderToStaticMarkup(React.createElement(ProviderChoice, { value: 'openai', onChange() {}, policies: [{ providerId: 'openai', displayName: 'OpenAI', providerReady: true }] }));
  assert.ok(!selector.includes('chưa kết nối'));
});

test('unknown sync item offers reconcile/link but never a plain retry; card links are restricted to trello.com', () => {
  const unknown = renderToStaticMarkup(React.createElement(SyncItem, { item: { syncItemId: 'i', taskId: 't', taskName: injection, status: 'UNKNOWN',
    errorCode: 'DISPATCH_TIMEOUT', message: 'Có thể card đã được tạo', actions: ['RECONCILE', 'LINK_CARD', 'OPEN_BOARD'], attemptCount: 1, reconcileCount: 0,
    boardName: 'Board', listName: 'To Do', boardUrl: 'https://trello.com/b/x', cardUrl: 'javascript:alert(1)' }, onAction() {} }));
  assert.ok(unknown.includes('Đối soát ngay') && unknown.includes('Đã có card') && !unknown.includes('>Thử lại<') && !unknown.includes('Tạo lại'));
  assert.ok(!unknown.includes('javascript:') && unknown.includes('&lt;img'));
  const synced = renderToStaticMarkup(React.createElement(SyncItem, { item: { syncItemId: 'i', taskId: 't', taskName: 'x', status: 'SYNCED', actions: ['OPEN_CARD'],
    cardUrl: 'https://trello.com/c/abc/1', attemptCount: 1, reconcileCount: 0 }, onAction() {} }));
  assert.ok(synced.includes('href="https://trello.com/c/abc/1"') && synced.includes('rel="noopener noreferrer"'));
});

test('trello connection never renders a token and explains disabled backend config', () => {
  const connected = renderToStaticMarkup(React.createElement(TrelloConnection, { config: { encryptionReady: true, oauthAvailable: true, tokenModeAvailable: true, tokenAuthorizeUrl: 'https://trello.com/1/authorize?key=k' },
    connection: { connectionId: 'c', fullName: 'Demo User', username: 'demo', authType: 'OAUTH2', status: 'REAUTH_REQUIRED', trelloMemberId: 'me' } }));
  assert.ok(connected.includes('Demo User') && connected.includes('hết quyền') && connected.includes('Kết nối Trello (OAuth)'));
  assert.match(connected, /<input type="password"/);
  const missing = renderToStaticMarkup(React.createElement(TrelloConnection, { config: { encryptionReady: false, oauthAvailable: false, tokenModeAvailable: false }, connection: null }));
  assert.ok(missing.includes('TOKEN_ENCRYPTION_KEY'));
});

test('confirmation lists destination, member and full deadline for every card', () => {
  const html = renderToStaticMarkup(React.createElement(ConfirmCreate, { destination: { boardName: 'Demo Board', listName: 'To Do' }, tasks: [
    { taskId: 'a', taskName: 'Sửa login', memberResolution: 'RESOLVED', trelloMemberName: 'Nguyễn Thị Mai', deadlineResolution: 'RESOLVED', dueLocal: '2026-10-16T17:00', timezone: 'Asia/Ho_Chi_Minh', priority: 'HIGH' },
    { taskId: 'b', taskName: 'Gửi biên bản', memberResolution: 'NONE_SELECTED', deadlineResolution: 'NONE_SELECTED' }] }));
  assert.ok(html.includes('Tạo 2 card trong Demo Board › To Do') && html.includes('Thứ Sáu, 16/10/2026 lúc 17:00 (Asia/Ho_Chi_Minh)'));
  assert.ok(html.includes('Nguyễn Thị Mai') && html.includes('Không giao người') && html.includes('Không đặt hạn') && html.includes('Xác nhận tạo 2 card'));
});
