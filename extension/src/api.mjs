export class ApiError extends Error {
  constructor(message, { status = 0, code = 'NETWORK_ERROR', traceId, retryAfter, details = [] } = {}) {
    super(message);
    Object.assign(this, { status, code, traceId, retryAfter, details });
  }
}

export function createApi(origin, { fetchImpl = fetch, onUnauthorized = async () => {}, timeoutMs = 30000 } = {}) {
  async function request(path, { method = 'GET', token, json, form, idempotencyKey } = {}) {
    const headers = {};
    if (token) headers.Authorization = `Bearer ${token}`;
    if (json !== undefined) headers['Content-Type'] = 'application/json';
    if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetchImpl(`${origin}${path}`, {
        method, headers, body: form ?? (json === undefined ? undefined : JSON.stringify(json)),
        credentials: 'omit', cache: 'no-store', redirect: 'error', signal: controller.signal,
      });
      const raw = await response.text();
      let body;
      try { body = raw ? JSON.parse(raw) : null; } catch { body = null; }
      if (!response.ok) {
        if (response.status === 401 && token) await onUnauthorized(token);
        const message = body?.message ?? (response.status === 403
          ? 'Backend từ chối truy cập. Kiểm tra extension origin trong CORS allowlist.'
          : `Request thất bại (HTTP ${response.status}).`);
        throw new ApiError(message, {
          status: response.status, code: body?.code ?? 'HTTP_ERROR',
          traceId: body?.traceId ?? response.headers.get('X-Trace-Id'),
          retryAfter: response.headers.get('Retry-After'), details: body?.details ?? [],
        });
      }
      if (response.status !== 204 && body === null) throw new ApiError('Backend trả response không hợp lệ.', { code: 'INVALID_RESPONSE' });
      return body;
    } catch (error) {
      if (error instanceof ApiError) throw error;
      throw new ApiError(controller.signal.aborted
        ? 'Request hết thời gian chờ. Nếu đang lưu, kiểm tra lịch sử trước khi gửi lại.'
        : 'Không kết nối được backend. Kiểm tra server, cổng, host permission và CORS. Nếu đang lưu, kiểm tra lịch sử trước khi gửi lại.',
      { code: controller.signal.aborted ? 'TIMEOUT' : 'NETWORK_ERROR' });
    } finally { clearTimeout(timer); }
  }
  const meetingPath = (id) => `/api/v1/meetings/${encodeURIComponent(id)}`;
  return {
    health: () => request('/actuator/health'),
    register: (input) => request('/api/v1/auth/register', { method: 'POST', json: input }),
    login: (input) => request('/api/v1/auth/login', { method: 'POST', json: input }),
    me: (token) => request('/api/v1/auth/me', { token }),
    logout: (token) => request('/api/v1/auth/logout', { method: 'POST', token }),
    create: (token, input) => request('/api/v1/meetings', { method: 'POST', token, json: input }),
    upload: (token, file, metadata) => {
      const form = new FormData();
      form.append('file', file);
      for (const [key, value] of Object.entries(metadata)) {
        if (['title', 'meetingDate', 'timezone'].includes(key) && value) form.append(key, value);
      }
      return request('/api/v1/meetings', { method: 'POST', token, form });
    },
    history: (token, cursor = null) => {
      const query = new URLSearchParams({ limit: '20' });
      if (cursor !== null) query.set('cursor', cursor);
      return request(`/api/v1/meetings?${query}`, { token });
    },
    meeting: (token, id) => request(meetingPath(id), { token }),
    transcript: (token, id, cursor = -1) => request(`${meetingPath(id)}/transcript?cursor=${cursor}&limit=50`, { token }),
    replace: (token, id, input) => request(`${meetingPath(id)}/input`, { method: 'PATCH', token, json: input }),
    analysisPolicies: token => request('/api/v1/analysis-policies', { token }),
    startAnalysis: (token, id, input, idempotencyKey) => request(`${meetingPath(id)}/analysis-jobs`, { method: 'POST', token, json: input, idempotencyKey }),
    job: (token, id) => request(`/api/v1/jobs/${encodeURIComponent(id)}`, { token }),
    // Review drafts (SDS §6.5). A job ID pins the read so a late response cannot show another analysis.
    tasks: (token, id, jobId = null) => request(`${meetingPath(id)}/tasks${jobId ? `?analysisJobId=${encodeURIComponent(jobId)}` : ''}`, { token }),
    createTask: (token, id, input, idempotencyKey) => request(`${meetingPath(id)}/tasks`, { method: 'POST', token, json: input, idempotencyKey }),
    patchTask: (token, taskId, input) => request(`/api/v1/tasks/${encodeURIComponent(taskId)}`, { method: 'PATCH', token, json: input }),
    rejectTask: (token, taskId, expectedVersion) => request(`/api/v1/tasks/${encodeURIComponent(taskId)}?expectedVersion=${encodeURIComponent(expectedVersion)}`, { method: 'DELETE', token }),
    restoreTask: (token, taskId, expectedVersion) => request(`/api/v1/tasks/${encodeURIComponent(taskId)}/restore`, { method: 'POST', token, json: { expectedVersion } }),
    taskEvidence: (token, taskId) => request(`/api/v1/tasks/${encodeURIComponent(taskId)}/evidence`, { token }),
    cardPreview: (token, taskId) => request(`/api/v1/tasks/${encodeURIComponent(taskId)}/card-preview`, { token }),
    // Trello (SDS §6.6–6.8). The extension never receives Trello tokens; a pasted token goes to the backend once.
    trelloConfig: token => request('/api/v1/trello/config', { token }),
    trelloConnections: token => request('/api/v1/trello/connections', { token }),
    trelloAuthorize: token => request('/api/v1/trello/connections/authorize', { method: 'POST', token }),
    trelloTransaction: (token, id) => request(`/api/v1/trello/authorizations/${encodeURIComponent(id)}`, { token }),
    trelloConnectToken: (token, trelloToken) => request('/api/v1/trello/connections/token', { method: 'POST', token, json: { token: trelloToken } }),
    trelloDisconnect: (token, id) => request(`/api/v1/trello/connections/${encodeURIComponent(id)}`, { method: 'DELETE', token }),
    trelloBoards: (token, connectionId) => request(`/api/v1/trello/connections/${encodeURIComponent(connectionId)}/boards`, { token }),
    trelloLists: (token, connectionId, boardId) => request(`/api/v1/trello/boards/${encodeURIComponent(boardId)}/lists?connectionId=${encodeURIComponent(connectionId)}`, { token }),
    destination: (token, id) => request(`${meetingPath(id)}/destination`, { token }),
    saveDestination: (token, id, input) => request(`${meetingPath(id)}/destination`, { method: 'PUT', token, json: input }),
    resolveMembers: (token, id, expectedDestinationVersion) => request(`${meetingPath(id)}/resolve-members`, { method: 'POST', token, json: { expectedDestinationVersion } }),
    resolveDeadlines: (token, id) => request(`${meetingPath(id)}/resolve-deadlines`, { method: 'POST', token }),
    approveAndSync: (token, id, input, idempotencyKey) => request(`${meetingPath(id)}/approve-and-sync`, { method: 'POST', token, json: input, idempotencyKey }),
    syncJob: (token, jobId) => request(`/api/v1/sync-jobs/${encodeURIComponent(jobId)}`, { token }),
    retrySyncItem: (token, itemId) => request(`/api/v1/sync-items/${encodeURIComponent(itemId)}/retry`, { method: 'POST', token }),
    reconcileSyncItem: (token, itemId) => request(`/api/v1/sync-items/${encodeURIComponent(itemId)}/reconcile`, { method: 'POST', token }),
    linkCard: (token, itemId, card, acknowledgeNoMarker) => request(`/api/v1/sync-items/${encodeURIComponent(itemId)}/link-card`, { method: 'POST', token, json: { card, acknowledgeNoMarker } }),
    recreateSyncItem: (token, itemId, expectedVersion) => request(`/api/v1/sync-items/${encodeURIComponent(itemId)}/recreate`, { method: 'POST', token, json: { expectedVersion, acknowledgementDuplicateRisk: true } }),
    // Privacy (SDS §6.3, §10.3)
    deleteTranscript: (token, id) => request(`${meetingPath(id)}/transcript`, { method: 'DELETE', token }),
    deleteMeeting: (token, id) => request(meetingPath(id), { method: 'DELETE', token }),
    cancelJob: (token, id) => request(`/api/v1/jobs/${encodeURIComponent(id)}/cancel`, { method: 'POST', token }),
    retryJob: (token, id) => request(`/api/v1/jobs/${encodeURIComponent(id)}/retry`, { method: 'POST', token }),
  };
}
