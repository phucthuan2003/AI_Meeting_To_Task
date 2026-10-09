import test from 'node:test';
import assert from 'node:assert/strict';
import { createStorage, SESSION_KEY } from '../src/storage.mjs';

function fakeArea() {
  const data = {};
  return {
    data, access: null,
    async setAccessLevel(value) { this.access = value; },
    async get(key) { return { [key]: data[key] }; },
    async set(value) { Object.assign(data, value); },
    async remove(key) { delete data[key]; },
  };
}
function fixture(origin = 'http://127.0.0.1:8080') {
  const chromeStorage = { session: fakeArea(), local: fakeArea() };
  const store = createStorage(chromeStorage, origin, () => 1000);
  return { chromeStorage, store };
}
const user = { id: 'owner-a', email: 'a@example.com' };
const auth = { accessToken: 'token-a', expiresAt: new Date(2000).toISOString(), sessionId: 'session-a' };

test('JWT remains in trusted session storage; durable storage holds only scoped meeting ID', async () => {
  const { chromeStorage, store } = fixture();
  await store.init(); await store.saveSession(auth, user); await store.setActive(user.id, 'meeting-a');
  assert.deepEqual(chromeStorage.session.access, { accessLevel: 'TRUSTED_CONTEXTS' });
  assert.equal((await store.session()).accessToken, auth.accessToken);
  assert.deepEqual(Object.values(chromeStorage.local.data), ['meeting-a']);
  assert.ok(!JSON.stringify(chromeStorage.local.data).includes(auth.accessToken));
  assert.equal(await store.active('owner-b'), null);
});

test('panel reopen restores pointer; logout/expiry preserves saved meeting without storing transcript', async () => {
  const { chromeStorage, store } = fixture();
  await store.saveSession(auth, user); await store.setActive(user.id, 'meeting-a');
  await store.clearSession(auth.accessToken);
  const reopened = createStorage(chromeStorage, 'http://127.0.0.1:8080', () => 3000);
  assert.equal(await reopened.session(), null);
  assert.equal(await reopened.active(user.id), 'meeting-a');
  await reopened.saveSession(auth, user);
  assert.equal(await reopened.session(), null);
  assert.equal(chromeStorage.session.data[SESSION_KEY], undefined);
});

test('a stale 401 cannot clear newer login; switching backend cannot use previous token or meeting pointer', async () => {
  const { chromeStorage, store } = fixture();
  await store.saveSession({ ...auth, accessToken: 'new-token' }, user);
  await store.setActive(user.id, 'meeting-a');
  await store.clearSession('old-token');
  assert.equal((await store.session()).accessToken, 'new-token');
  const other = createStorage(chromeStorage, 'https://api.example.com', () => 1000);
  assert.equal(await other.active(user.id), null);
  assert.equal(await other.session(), null);
});
