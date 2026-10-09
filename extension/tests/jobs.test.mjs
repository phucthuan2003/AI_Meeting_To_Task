import test from 'node:test';
import assert from 'node:assert/strict';
import { pollJob, isActiveJob, assertJobSnapshot } from '../src/jobs.mjs';
import { createApi, ApiError } from '../src/api.mjs';

const meeting = { meetingId: 'meeting-a', transcriptRevision: 'revision-2', inputVersion: 7 };
const job = status => ({ ...meeting, jobId: 'job-a', status, stage: status === 'QUEUED' ? 'WAITING' : 'PREPARING_INPUT' });
function timers() {
  let id = 0;
  const tasks = new Map();
  return {
    tasks,
    schedule(callback, delay) { const next = ++id; tasks.set(next, { callback, delay }); return next; },
    unschedule(id) { tasks.delete(id); },
    async next() { const [id, task] = tasks.entries().next().value; tasks.delete(id); await task.callback(); return task.delay; },
  };
}

test('poll follows QUEUED/PROCESSING and stops on terminal status', async () => {
  const clock = timers(); const updates = []; const queue = ['QUEUED', 'PROCESSING', 'FAILED'];
  const poller = pollJob({ ...clock, load: async () => job(queue.shift()), onJob: value => updates.push(value.status), onError: assert.fail });
  await poller.initial;
  assert.equal(clock.tasks.size, 1);
  await clock.next(); await clock.next();
  assert.deepEqual(updates, ['QUEUED', 'PROCESSING', 'FAILED']);
  assert.equal(clock.tasks.size, 0);
});

test('closing panel stops timers and ignores late response from previous meeting', async () => {
  const clock = timers(); const updates = [];
  let resolve;
  const poller = pollJob({ ...clock, load: () => new Promise(done => { resolve = done; }), onJob: value => updates.push(value), onError: assert.fail });
  poller.stop(); resolve(job('PROCESSING')); await poller.initial;
  assert.equal(updates.length, 0); assert.equal(clock.tasks.size, 0);
});

test('poll errors retry GET with bounded backoff then pause instead of spinning', async () => {
  const clock = timers(); const retries = [];
  let calls = 0;
  const poller = pollJob({ ...clock, load: async () => { calls++; throw new ApiError('network'); }, onJob: assert.fail,
    onError: (_, state) => retries.push(state.willRetry) });
  await poller.initial;
  assert.equal(await clock.next(), 4000);
  assert.equal(await clock.next(), 8000);
  assert.equal(calls, 3); assert.deepEqual(retries, [true, true, false]); assert.equal(clock.tasks.size, 0);
});

test('401/403/404/409 stop polling immediately without retrying another owner/revision', async () => {
  for (const status of [401, 403, 404, 409]) {
    const clock = timers(); let retry;
    const poller = pollJob({ ...clock, load: async () => { throw new ApiError('blocked', { status }); }, onJob: assert.fail,
      onError: (_, state) => { retry = state.willRetry; } });
    await poller.initial; assert.equal(retry, false); assert.equal(clock.tasks.size, 0);
  }
});

test('a successful poll resets consecutive failures and resumes progress', async () => {
  const clock = timers(); let calls = 0; const updates = [];
  const poller = pollJob({ ...clock, load: async () => {
    calls++; if (calls === 1 || calls === 3) throw new ApiError('network'); return job(calls === 2 ? 'PROCESSING' : 'CANCELLED');
  }, onJob: value => updates.push(value.status), onError: () => {} });
  await poller.initial; await clock.next(); await clock.next(); await clock.next();
  assert.deepEqual(updates, ['PROCESSING', 'CANCELLED']); assert.equal(clock.tasks.size, 0);
});

test('reopen restores existing job using GET only; it never enqueues a new job', async () => {
  const requests = [];
  const api = createApi('http://127.0.0.1:8080', { fetchImpl: async (url, options) => {
    requests.push({ url, method: options.method }); return new Response(JSON.stringify(job('FAILED')));
  } });
  for (let reopen = 0; reopen < 2; reopen++) {
    await pollJob({ load: () => api.job('token', 'job-a'), onJob: () => {}, onError: assert.fail }).initial;
  }
  assert.equal(requests.length, 2);
  assert.ok(requests.every(item => item.method === 'GET' && item.url.endsWith('/api/v1/jobs/job-a')));
});

test('job must match meeting ID, revision and input version before rendering', () => {
  assert.equal(assertJobSnapshot(job('PROCESSING'), meeting).jobId, 'job-a');
  for (const changed of [{ meetingId: 'meeting-b' }, { transcriptRevision: 'revision-1' }, { inputVersion: 6 }]) {
    assert.throws(() => assertJobSnapshot({ ...job('PROCESSING'), ...changed }, meeting), error => error.code === 'STALE_VIEW');
  }
});

test('CANCEL_REQUESTED is active; terminal statuses release input lock', () => {
  for (const status of ['QUEUED', 'PROCESSING', 'CANCEL_REQUESTED']) assert.equal(isActiveJob(status), true);
  for (const status of ['FAILED', 'PARTIAL_FAILED', 'CANCELLED', 'COMPLETED', 'NOT_STARTED']) assert.equal(isActiveJob(status), false);
});
