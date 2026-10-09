export const SESSION_KEY = 'aiMttSession';

export function createStorage(storage, origin, now = Date.now) {
  const pointerKey = (userId) => `activeMeeting:${origin}:${userId}`;
  return {
    async init() {
      await storage.session.setAccessLevel({ accessLevel: 'TRUSTED_CONTEXTS' });
    },
    async session() {
      const session = (await storage.session.get(SESSION_KEY))[SESSION_KEY];
      if (!session) return null;
      if (session.origin !== origin || !session.user?.id || !session.accessToken
          || !Number.isFinite(Date.parse(session.expiresAt)) || Date.parse(session.expiresAt) <= now()) {
        await storage.session.remove(SESSION_KEY);
        return null;
      }
      return session;
    },
    async saveSession(auth, user) {
      await storage.session.set({ [SESSION_KEY]: {
        origin, accessToken: auth.accessToken, expiresAt: auth.expiresAt,
        sessionId: auth.sessionId, user: { id: user.id, email: user.email },
      } });
    },
    // Clear only the token that failed: an old request cannot erase a newer login.
    async clearSession(token) {
      const session = (await storage.session.get(SESSION_KEY))[SESSION_KEY];
      if (!token || session?.accessToken === token) await storage.session.remove(SESSION_KEY);
    },
    async active(userId) { return (await storage.local.get(pointerKey(userId)))[pointerKey(userId)] ?? null; },
    async setActive(userId, meetingId) {
      if (meetingId) await storage.local.set({ [pointerKey(userId)]: meetingId });
      else await storage.local.remove(pointerKey(userId));
    },
  };
}
