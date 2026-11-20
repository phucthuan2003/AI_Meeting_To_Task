import { installSelectionMenu, captureSelection } from './selection.mjs';
import { CAPTURE_STATE } from './audio.mjs';
import { SESSION_KEY } from './storage.mjs';
// The panel is opened from action.onClicked (not openPanelOnActionClick): Chrome only grants activeTab, which
// tabCapture needs, when the click is delivered to the extension. Ctrl/Cmd+Shift+Y (_execute_action) does the same.
chrome.runtime.onInstalled.addListener(() => {
  installSelectionMenu(chrome);
  chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: false }).catch(() => {});
});
chrome.runtime.onStartup.addListener(() => {
  chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: false }).catch(() => {});
});
chrome.action.onClicked.addListener(tab => {
  // Must run synchronously inside the click (user gesture); no await before open().
  chrome.sidePanel.open({ windowId: tab.windowId }).catch(() => {});
});

// Chrome's English tabCapture errors → what the user should do.
function captureError(error) {
  const text = String(error?.message ?? error);
  if (/cannot be captured|chrome:\/\/|Chrome pages/i.test(text)) {
    return 'Không thể thu trang của Chrome (tab mới, chrome://, Web Store). Mở tab cuộc họp (Meet, Zoom web…) rồi thử lại.';
  }
  if (/not been invoked|activeTab/i.test(text)) {
    return 'Chrome chưa cho phép thu tab này. Ở tab cuộc họp, bấm icon Meeting to Task (hoặc Ctrl/Cmd+Shift+Y) rồi bấm Bắt đầu thu lại ngay, không chuyển tab.';
  }
  return text;
}
// Selected text is held only in trusted session storage until explicitly imported or dismissed.
chrome.storage.session.setAccessLevel({ accessLevel: 'TRUSTED_CONTEXTS' }).catch(() => {});

chrome.contextMenus.onClicked.addListener((info, tab) => {
  captureSelection(chrome, info, tab).catch(() => {});
});

// Media belongs to an offscreen document so closing the panel does not end recording.
chrome.runtime.onMessage.addListener((message, sender, reply) => {
  if (sender.id !== chrome.runtime.id || message.target !== 'capture-worker') return;
  (async () => {
    if (message.command === 'start') {
      let streamId;
      try { streamId = await chrome.tabCapture.getMediaStreamId({ targetTabId: message.tabId }); }
      catch (error) { return { ok: false, error: captureError(error) }; }
      if (!(await chrome.runtime.getContexts({ contextTypes: ['OFFSCREEN_DOCUMENT'] })).length) {
        await chrome.offscreen.createDocument({ url: 'offscreen.html', reasons: ['USER_MEDIA'], justification: 'Thu âm tab và microphone theo yêu cầu người dùng.' });
      }
      return chrome.runtime.sendMessage({ ...message, target: 'capture-offscreen', streamId });
    }
    const contexts = await chrome.runtime.getContexts({ contextTypes: ['OFFSCREEN_DOCUMENT'] });
    if (!contexts.length) return { ok: false, error: 'Phiên thu cục bộ đã dừng. Chốt các đoạn đã gửi từ panel.' };
    return chrome.runtime.sendMessage({ ...message, target: 'capture-offscreen' });
  })().then(reply, error => reply({ ok: false, error: error.message }));
  return true;
});

// Offscreen documents have runtime messaging, but cannot access extension storage.
chrome.runtime.onMessage.addListener((message, sender, reply) => {
  if (message.target !== 'capture-storage' || sender.id !== chrome.runtime.id
      || sender.url !== chrome.runtime.getURL('offscreen.html')) return;
  (async () => {
    if (message.command === 'status') {
      await chrome.storage.session.set({ [CAPTURE_STATE]: message.status });
      return { ok: true };
    }
    if (message.command === 'auth') {
      const session = (await chrome.storage.session.get(SESSION_KEY))[SESSION_KEY];
      if (session?.user?.id === message.userId && session.origin === message.origin
          && Date.parse(session.expiresAt) > Date.now()) {
        return { ok: true, accessToken: session.accessToken };
      }
      return { ok: false, error: 'Phiên đăng nhập hết hạn. Dừng thu; đăng nhập lại rồi bấm Gửi lại đoạn đang chờ.' };
    }
    return { ok: false, error: 'Yêu cầu lưu trữ phiên thu không hợp lệ.' };
  })().then(reply, error => reply({ ok: false, error: error.message }));
  return true;
});

chrome.storage.onChanged.addListener((changes, area) => {
  if (area !== 'session' || !changes[SESSION_KEY]) return;
  const next = changes[SESSION_KEY].newValue;
  chrome.runtime.sendMessage({
    target: 'capture-offscreen', command: 'session-changed',
    userId: next?.user?.id, origin: next?.origin, expiresAt: next?.expiresAt,
  }).catch(() => {});
});
