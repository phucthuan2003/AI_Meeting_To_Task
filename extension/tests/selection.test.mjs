import test from 'node:test';
import assert from 'node:assert/strict';
import { selectedInput, belongsToSession, captureSelection, SELECTION_MENU, SELECTION_KEY } from '../src/selection.mjs';
import { validateFile, MAX_TEXT } from '../src/input.mjs';
const session = { user: { id: 'a' }, origin: 'http://127.0.0.1:8080', sessionId: 'session-a', expiresAt: new Date(Date.now() + 60000).toISOString() };
test('selected text is ephemeral and scoped to login/account/backend; no silent truncate', () => {
  const item = selectedInput('Mai: Viết API.', session);
  assert.equal(belongsToSession(item, session), true);
  for (const other of [{ ...session, user: { id: 'b' } }, { ...session, sessionId: 'new-login' }, { ...session, origin: 'https://other.test' }]) {
    assert.equal(belongsToSession(item, other), false);
  }
  assert.equal(belongsToSession(item, session, item.expiresAt), false);
  assert.throws(() => selectedInput('x', null), /Đăng nhập/);
  assert.throws(() => selectedInput('x'.repeat(MAX_TEXT + 1), session), /200.000/);
});
test('menu opens panel before async reads, stores no page URL, makes no backend request', async () => {
  const calls = []; let saved;
  const chrome = {
    sidePanel: { open: async () => { calls.push('open'); } },
    storage: { session: { setAccessLevel: async () => calls.push('access'), get: async () => { calls.push('get'); return { aiMttSession: session }; }, set: async value => { saved = value; } } },
  };
  await captureSelection(chrome, { menuItemId: SELECTION_MENU, selectionText: 'Nội dung', pageUrl: 'https://private.test' }, { windowId: 1 });
  assert.equal(calls[0], 'open');
  assert.equal(saved[SELECTION_KEY].text, 'Nội dung');
  assert.equal(JSON.stringify(saved).includes('private.test'), false);
  await captureSelection(chrome, { menuItemId: SELECTION_MENU, selectionText: 'x'.repeat(MAX_TEXT + 1) }, { windowId: 1 });
  assert.equal(saved[SELECTION_KEY].text, undefined);
  assert.match(saved[SELECTION_KEY].error, /200.000/);
});
test('subtitle upload client allows VTT/SRT within file limit', () => {
  for (const name of ['meeting.vtt', 'meeting.SRT']) validateFile({ name, size: 100 });
});
