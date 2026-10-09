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
    name: 'AI Meeting to Task',
    description: 'Nhập transcript và mở lại meeting đã lưu trên backend của bạn.',
    version: '0.3.0',
    minimum_chrome_version: '116',
    permissions: ['storage', 'sidePanel'],
    host_permissions: [`${url.protocol}//${url.hostname}/*`],
    action: { default_title: 'Mở Meeting to Task' },
    side_panel: { default_path: 'sidepanel.html' },
    background: { service_worker: 'service-worker.js', type: 'module' },
    content_security_policy: {
      extension_pages: `script-src 'self'; object-src 'none'; connect-src ${url.origin}; base-uri 'none'; frame-ancestors 'none'`,
    },
  };
}
