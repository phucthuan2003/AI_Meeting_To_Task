import { ApiError } from './api.mjs';

const active = new Set(['QUEUED', 'PROCESSING', 'CANCEL_REQUESTED']);
export function isActiveJob(jobOrStatus) { return active.has(typeof jobOrStatus === 'string' ? jobOrStatus : jobOrStatus?.status); }
export function assertJobSnapshot(job, meeting) {
  if (job.meetingId !== meeting.meetingId || job.transcriptRevision !== meeting.transcriptRevision || job.inputVersion !== meeting.inputVersion) {
    throw new ApiError('Job thuộc input khác. Tải lại meeting để theo dõi đúng phiên bản.', { status: 409, code: 'STALE_VIEW' });
  }
  return job;
}

// Only GET is retried automatically. Closing/reopening the panel never starts a new job.
export function pollJob({ load, onJob, onError, intervalMs = 2000, maxFailures = 3,
  schedule = setTimeout, unschedule = clearTimeout }) {
  let stopped = false;
  let timer;
  let failures = 0;
  async function tick() {
    if (stopped) return;
    try {
      const job = await load();
      if (stopped) return;
      failures = 0;
      onJob(job);
      if (isActiveJob(job)) timer = schedule(tick, intervalMs);
      else stopped = true;
    } catch (error) {
      if (stopped) return;
      failures++;
      const willRetry = ![401, 403, 404, 409].includes(error.status) && failures < maxFailures;
      const delay = Math.min(15000, intervalMs * 2 ** failures);
      onError(error, { willRetry, delay });
      if (willRetry) timer = schedule(tick, delay);
      else stopped = true;
    }
  }
  return {
    initial: tick(),
    stop() { stopped = true; if (timer !== undefined) unschedule(timer); },
  };
}
