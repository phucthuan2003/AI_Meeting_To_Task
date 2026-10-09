import test from 'node:test';
import assert from 'node:assert/strict';
import { ApiError, createApi } from '../src/api.mjs';
const origin = 'http://127.0.0.1:8080';
const response = (body, status = 200, headers = {}) => new Response(body === null ? null : JSON.stringify(body), { status, headers });

test('login excludes credentials/token; protected request uses Bearer and rejects redirects', async () => {
  const calls = [];
  const api = createApi(origin, { fetchImpl: async (url, options) => { calls.push({ url, options }); return response({ id: 'owner' }); } });
  await api.login({ email: 'test@example.com', password: 'dummy-password' });
  await api.me('test-token');
  assert.equal(calls[0].url, `${origin}/api/v1/auth/login`);
  assert.equal(calls[0].options.headers.Authorization, undefined);
  assert.equal(calls[1].options.headers.Authorization, 'Bearer test-token');
  assert.equal(calls[1].options.credentials, 'omit');
  assert.equal(calls[1].options.redirect, 'error');
  assert.equal(calls[1].options.cache, 'no-store');
});

test('401 invalidates authenticated session; invalid login does not erase session', async () => {
  const expired = [];
  const api = createApi(origin, { fetchImpl: async () => response({ code: 'SESSION_EXPIRED', message: 'Đăng nhập lại', traceId: 'trace-1' }, 401), onUnauthorized: async token => expired.push(token) });
  await assert.rejects(api.me('old-token'), error => error.code === 'SESSION_EXPIRED' && error.traceId === 'trace-1');
  await assert.rejects(api.login({ email: 'wrong', password: 'incorrect' }), ApiError);
  assert.deepEqual(expired, ['old-token']);
});

test('logout handles empty 204 response', async () => {
  assert.equal(await createApi(origin, { fetchImpl: async () => response(null, 204) }).logout('token'), null);
});

test('multipart sends exactly one file, metadata allowlist, browser-generated boundary', async () => {
  let request;
  const api = createApi(origin, { fetchImpl: async (url, options) => { request = options; return response({ meetingId: 'meeting' }, 201); } });
  await api.upload('token', new File(['Nam: Sửa login'], 'meeting.txt', { type: 'text/plain' }), {
    title: 'Planning', meetingDate: '', timezone: 'Asia/Ho_Chi_Minh', transcriptText: 'Must not send', ignored: 'wrong',
  });
  assert.deepEqual([...request.body.keys()], ['file', 'title', 'timezone']);
  assert.equal(request.body.getAll('file').length, 1);
  assert.equal(request.headers['Content-Type'], undefined);
});

test('pagination preserves numeric cursor zero and encodes opaque history cursor', async () => {
  const urls = [];
  const api = createApi(origin, { fetchImpl: async url => { urls.push(url); return response({}); } });
  await api.transcript('token', 'meeting', 0);
  await api.history('token', 'a+b=/');
  assert.equal(new URL(urls[0]).searchParams.get('cursor'), '0');
  assert.equal(new URL(urls[1]).searchParams.get('cursor'), 'a+b=/');
});

test('stale PATCH keeps expectedVersion; conflict is not retried', async () => {
  const requests = [];
  const api = createApi(origin, { fetchImpl: async (url, options) => { requests.push(options); return response({ code: 'STALE_VERSION', message: 'Tải lại' }, 409); } });
  await assert.rejects(api.replace('token', 'meeting', { expectedVersion: 3, transcriptText: 'New input' }), error => error.status === 409 && error.code === 'STALE_VERSION');
  assert.equal(requests.length, 1);
  assert.equal(JSON.parse(requests[0].body).expectedVersion, 3);
});

test('429 preserves Retry-After and plain CORS rejection remains readable', async () => {
  const limited = createApi(origin, { fetchImpl: async () => response({ code: 'RATE_LIMIT', message: 'Chờ' }, 429, { 'Retry-After': '60' }) });
  await assert.rejects(limited.login({}), error => error.retryAfter === '60');
  const cors = createApi(origin, { fetchImpl: async () => new Response('Invalid CORS request', { status: 403 }) });
  await assert.rejects(cors.login({}), error => error.status === 403 && /CORS/.test(error.message));
});

test('timeout aborts request and never retries a possibly committed write', async () => {
  let attempts = 0;
  const api = createApi(origin, { timeoutMs: 10, fetchImpl: async (_, options) => {
    attempts++;
    return new Promise((_, reject) => options.signal.addEventListener('abort', () => reject(new Error('aborted'))));
  } });
  await assert.rejects(api.create('token', { transcriptText: 'input' }), error => error.code === 'TIMEOUT' && /lịch sử/.test(error.message));
  assert.equal(attempts, 1);
});

test('unexpected successful HTML is reported as invalid response', async () => {
  await assert.rejects(createApi(origin, { fetchImpl: async () => new Response('<html>proxy</html>') }).health(), error => error.code === 'INVALID_RESPONSE');
});

test('analysis POST pins version/policy and sends idempotency key; network loss is not retried automatically', async () => {
  const calls = [];
  const api = createApi(origin, { fetchImpl: async (url, options) => { calls.push({ url, options }); throw new Error('lost response'); } });
  for (const providerId of ['openai', 'gemini']) {
    const before = calls.length;
    const input = { expectedInputVersion: 7, providerId, processingPolicyId: 'foundation-v1' };
    const key = `request-key-${providerId}`;
    await assert.rejects(api.startAnalysis('token', 'meeting-a', input, key), error => error.code === 'NETWORK_ERROR');
    assert.equal(calls.length, before + 1);
    assert.equal(calls[before].url, `${origin}/api/v1/meetings/meeting-a/analysis-jobs`);
    assert.equal(calls[before].options.headers['Idempotency-Key'], key);
    assert.deepEqual(JSON.parse(calls[before].options.body), input);
  }
});

test('policy/get/cancel/retry job requests use authenticated paths without extra input or keys', async () => {
  const calls = [];
  const api = createApi(origin, { fetchImpl: async (url, options) => { calls.push({ url, options }); return response({}); } });
  await api.analysisPolicies('token'); await api.job('token', 'job-a'); await api.cancelJob('token', 'job-a'); await api.retryJob('token', 'job-a');
  assert.deepEqual(calls.map(c => [new URL(c.url).pathname, c.options.method]), [
    ['/api/v1/analysis-policies', 'GET'], ['/api/v1/jobs/job-a', 'GET'], ['/api/v1/jobs/job-a/cancel', 'POST'], ['/api/v1/jobs/job-a/retry', 'POST'],
  ]);
  assert.ok(calls.every(call => call.options.headers.Authorization === 'Bearer token' && call.options.body === undefined));
});

test('task review requests are authenticated, pin the job when given and never retry writes', async () => {
  const calls = [];
  const api = createApi(origin, { fetchImpl: async (url, options) => { calls.push({ url, options }); return response(options.method === 'DELETE' ? null : {}, options.method === 'DELETE' ? 204 : 200); } });
  await api.tasks('token', 'meeting-a', 'job-b');
  await api.tasks('token', 'meeting-a');
  await api.createTask('token', 'meeting-a', { taskName: 'x' }, 'create-key-1');
  await api.patchTask('token', 'task-1', { expectedVersion: 3, taskName: 'y' });
  await api.rejectTask('token', 'task-1', 4);
  await api.restoreTask('token', 'task-1', 5);
  await api.taskEvidence('token', 'task-1');
  assert.deepEqual(calls.map(c => [c.url.replace(origin, ''), c.options.method]), [
    ['/api/v1/meetings/meeting-a/tasks?analysisJobId=job-b', 'GET'], ['/api/v1/meetings/meeting-a/tasks', 'GET'],
    ['/api/v1/meetings/meeting-a/tasks', 'POST'], ['/api/v1/tasks/task-1', 'PATCH'], ['/api/v1/tasks/task-1?expectedVersion=4', 'DELETE'],
    ['/api/v1/tasks/task-1/restore', 'POST'], ['/api/v1/tasks/task-1/evidence', 'GET'],
  ]);
  assert.ok(calls.every(c => c.options.headers.Authorization === 'Bearer token'));
  assert.equal(calls[2].options.headers['Idempotency-Key'], 'create-key-1');
  assert.deepEqual(JSON.parse(calls[3].options.body), { expectedVersion: 3, taskName: 'y' });
  assert.deepEqual(JSON.parse(calls[5].options.body), { expectedVersion: 5 });
});

test('task conflict details survive in the error for the review screen', async () => {
  const api = createApi(origin, { fetchImpl: async () => response({ code: 'STALE_VERSION', message: 'Task đã thay đổi', details: [{ taskId: 'task-1', currentVersion: 5 }] }, 409) });
  await assert.rejects(api.patchTask('token', 'task-1', { expectedVersion: 4 }), error => error.code === 'STALE_VERSION' && error.details[0].currentVersion === 5);
});
