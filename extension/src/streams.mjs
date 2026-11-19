import { ApiError } from './api.mjs';
/** Incremental SSE parser: network chunks and UTF-8 characters need not align with event boundaries. */
export function createSseParser(onEvent) {
  let buffer = '';
  return text => {
    buffer += text;
    if (buffer.length > 2 * 1024 * 1024) throw new Error('Sự kiện vượt giới hạn.');
    let match;
    while ((match = /\r?\n\r?\n/.exec(buffer))) {
      const block = buffer.slice(0, match.index); buffer = buffer.slice(match.index + match[0].length);
      let type = 'message', id = ''; const data = [];
      for (const line of block.split(/\r?\n/)) {
        if (line.startsWith('event:')) type = line.slice(6).trim();
        else if (line.startsWith('id:')) id = line.slice(3).trim();
        else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
      }
      if (data.length) onEvent({ type, id, data: data.join('\n') });
    }
  };
}
export function watchSnapshots(origin, path, token, { onSnapshot, onError, onUnauthorized = () => {}, fetchImpl = fetch }) {
  let stopped = false, controller, timer, failures = 0;
  async function connect() {
    controller = new AbortController();
    try {
      const headers = { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' };
      if (origin.includes('ngrok')) headers['ngrok-skip-browser-warning'] = 'true';
      const response = await fetchImpl(origin + path, { headers,
        credentials: 'omit', cache: 'no-store', redirect: 'error', signal: controller.signal });
      if (!response.ok) throw new ApiError('Không mở được luồng trạng thái.', { status: response.status });
      if (!response.headers.get('Content-Type')?.includes('text/event-stream') || !response.body) throw new Error('Luồng SSE không hợp lệ.');
      const parse = createSseParser(event => {
        if (event.type === 'auth-expired') throw new ApiError('Đăng nhập lại để tiếp tục.', { status: 401 });
        if (event.type === 'snapshot' && !stopped) { onSnapshot(JSON.parse(event.data)); failures = 0; }
      });
      const reader = response.body.getReader(), decoder = new TextDecoder();
      try { while (!stopped) { const part = await reader.read(); if (part.done) break; parse(decoder.decode(part.value, { stream: true })); } }
      finally { await reader.cancel().catch(() => {}); }
      if (!stopped) timer = setTimeout(connect, 1000); // server intentionally closes every 60s; next snapshot restores current state
    } catch (error) {
      if (stopped) return;
      if (error.status === 401) onUnauthorized(token);
      const again = ![401,403,404,409,429].includes(error.status) && ++failures <= 3;
      onError?.(error, { willRetry: again });
      if (again) timer = setTimeout(connect, Math.min(8000, 1000 * 2 ** failures));
    }
  }
  connect();
  return { stop() { stopped = true; clearTimeout(timer); controller?.abort(); } };
}
