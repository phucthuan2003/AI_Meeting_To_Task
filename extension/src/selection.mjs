import { MAX_TEXT } from './input.mjs';
import { SESSION_KEY } from './storage.mjs';
export const SELECTION_KEY = 'aiMttSelection';
export const SELECTION_MENU = 'ai-mtt-extract-selection';
export function selectedInput(text, session, now = Date.now()) {
  if (!session?.user?.id || !session.sessionId || !session.origin || Date.parse(session.expiresAt) <= now
      || !Number.isFinite(Date.parse(session.expiresAt))) throw new Error('Đăng nhập trong panel rồi chọn lại văn bản.');
  if (typeof text !== 'string' || !text.trim()) throw new Error('Chọn đoạn văn bản trước.');
  if (text.length > MAX_TEXT) throw new Error('Đoạn chọn vượt 200.000 ký tự. Chọn đoạn ngắn hơn.');
  return { id: crypto.randomUUID(), text, userId: session.user.id, sessionId: session.sessionId,
    origin: session.origin, expiresAt: now + 10 * 60 * 1000 };
}
export function belongsToSession(item, session, now = Date.now()) {
  return Boolean(item && session && Date.parse(session.expiresAt) > now && item.userId === session.user.id && item.origin === session.origin
    && item.sessionId === session.sessionId && item.expiresAt > now);
}
export function installSelectionMenu(chromeApi) {
  chromeApi.contextMenus.removeAll(() => {
    chromeApi.contextMenus.create({ id: SELECTION_MENU, title: 'Đưa văn bản vào Meeting to Task', contexts: ['selection'] });
  });
}
export async function captureSelection(chromeApi, info, tab) {
  if (info.menuItemId !== SELECTION_MENU || !Number.isInteger(tab?.windowId)) return;
  // Call before any await so Chrome still sees the explicit context-menu user gesture.
  const opening = chromeApi.sidePanel.open({ windowId: tab.windowId }).catch(() => {});
  await chromeApi.storage.session.setAccessLevel({ accessLevel: 'TRUSTED_CONTEXTS' });
  const session = (await chromeApi.storage.session.get(SESSION_KEY))[SESSION_KEY];
  let item;
  try { item = selectedInput(info.selectionText, session); }
  catch (failure) { item = { id: crypto.randomUUID(), error: failure.message, expiresAt: Date.now() + 60000 }; }
  await chromeApi.storage.session.set({ [SELECTION_KEY]: item });
  await opening;
}
