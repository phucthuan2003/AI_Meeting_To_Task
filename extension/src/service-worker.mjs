import { installSelectionMenu, captureSelection } from './selection.mjs';
chrome.runtime.onInstalled.addListener(() => {
  installSelectionMenu(chrome);
  chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true }).catch(() => {});
});
chrome.runtime.onStartup.addListener(() => {
  chrome.sidePanel.setPanelBehavior({ openPanelOnActionClick: true }).catch(() => {});
});
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
      const streamId = await chrome.tabCapture.getMediaStreamId({ targetTabId: message.tabId });
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
