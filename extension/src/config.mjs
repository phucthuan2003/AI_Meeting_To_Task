export function backendOrigin(value = 'http://127.0.0.1:8080') {
  const url = new URL(value);
  if (url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
    throw new Error('Backend phải là origin, không có credentials/path/query/hash.');
  }
  const local = ['localhost', '127.0.0.1'].includes(url.hostname);
  if (url.protocol !== 'https:' && !(local && url.protocol === 'http:')) {
    throw new Error('Chỉ dùng HTTPS, hoặc HTTP localhost cho development.');
  }
  return url.origin;
}

export function extensionManifest(origin) {
  const url = new URL(backendOrigin(origin));
  return {
    manifest_version: 3,
    key: 'MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAjlUQlCnu7jAj4B3UNBhNAGEyRL1odx77MNnz6DOepQZfivW++t1WwdkVM0PLLLjEPjvXyR61G08EuR/+gUBKIRm1Uel5IWCk1+rbQDrn8G0hzQHjEVasNl0gjxNJlRggrO1M9e9TUXRoKGVlhXX/KOkXvR7dmRfw/LXHsydARduq0BFub8r8oThJVzjtVvJAo6XGf3zd8F5i7xGMElvdh4wW8sO1WoQB95KGu84vn9KNAaimwTQ9vjyElcHSAkvTbVkBI+lCQNd6O8N0sMpqsNpbqxYNWafrQAic+7LFLMNIIhgy+GzCrrRi+ALV9mOlqhNT15+ayWahlwOc4FenDQIDAQAB',
    name: 'Meeting to Task',
    description: 'Nhập bản ghi, rà soát công việc và tạo thẻ Trello sau khi xác nhận.',
    version: '0.7.0',
    minimum_chrome_version: '116',
    permissions: ['storage', 'sidePanel', 'contextMenus', 'tabCapture', 'offscreen', 'activeTab'],
    host_permissions: [`${url.protocol}//${url.hostname}/*`],
    action: { default_title: 'Mở Meeting to Task' },
    // Same as clicking the icon: opens the panel and grants activeTab for tab recording.
    commands: { _execute_action: { suggested_key: { default: 'Ctrl+Shift+Y', mac: 'Command+Shift+Y' }, description: 'Mở Meeting to Task trên tab này' } },
    side_panel: { default_path: 'sidepanel.html' },
    background: { service_worker: 'service-worker.js', type: 'module' },
    content_security_policy: {
      extension_pages: `script-src 'self'; object-src 'none'; connect-src ${url.origin}; base-uri 'none'; frame-ancestors 'none'`,
    },
  };
}
