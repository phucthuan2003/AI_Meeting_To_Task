import test from 'node:test';
import assert from 'node:assert/strict';
import { assertRevision, openSavedMeeting } from '../src/meeting.mjs';
import { ApiError } from '../src/api.mjs';
const session = { accessToken: 'token', user: { id: 'owner-a' } };

test('reopen queries backend metadata and source rather than cached transcript', async () => {
  const calls = [];
  const api = {
    async meeting(token, id) { calls.push(['metadata', token, id]); return { meetingId: id, transcriptRevision: 'r2' }; },
    async transcript(token, id) { calls.push(['source', token, id]); return { transcriptRevision: 'r2', segments: [] }; },
  };
  const result = await openSavedMeeting(api, {}, session, 'meeting-a');
  assert.equal(result.meeting.transcriptRevision, 'r2');
  assert.deepEqual(calls, [['metadata', 'token', 'meeting-a'], ['source', 'token', 'meeting-a']]);
});

test('source from concurrent revision cannot be displayed as current meeting', () => {
  assert.throws(() => assertRevision({ transcriptRevision: 'r2' }, { transcriptRevision: 'r3' }), error => error.code === 'STALE_VIEW');
});

test('404 clears unavailable pointer for its owner; 401 and temporary failures retain pointer', async () => {
  const cleared = [];
  const storage = { async setActive(...args) { cleared.push(args); } };
  for (const status of [401, 503, 404]) {
    await assert.rejects(openSavedMeeting({ meeting: async () => { throw new ApiError('Unavailable', { status }); } }, storage, session, 'meeting-a'));
  }
  assert.deepEqual(cleared, [['owner-a', null]]);
});
