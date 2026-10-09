import { ApiError } from './api.mjs';

// Fields the user can change. Everything else (origin, status, warnings, Trello card) is server-owned.
export const DRAFT_FIELDS = ['taskName', 'description', 'assigneeRaw', 'deadlineRaw', 'dueLocal', 'timezone',
  'deadlineDecision', 'memberDecision', 'trelloMemberId', 'rememberAlias', 'priority', 'includeEvidenceInCard'];
export const DEFAULT_TIME = '17:00';
export const PRIORITY_LABELS = { LOW: 'Thấp', MEDIUM: 'Trung bình', HIGH: 'Cao' };
const WEEKDAYS = ['Chủ nhật', 'Thứ Hai', 'Thứ Ba', 'Thứ Tư', 'Thứ Năm', 'Thứ Sáu', 'Thứ Bảy'];

/** Server task → the values the editor shows. Decisions are derived from resolution states. */
export function draftOf(task) {
  return {
    taskName: task.taskName ?? '', description: task.description ?? null, assigneeRaw: task.assigneeRaw ?? null,
    deadlineRaw: task.deadlineRaw ?? null, dueLocal: task.dueLocal ?? null, timezone: task.timezone ?? null,
    deadlineDecision: ['RESOLVED', 'NONE_SELECTED'].includes(task.deadlineResolution) ? task.deadlineResolution : null,
    memberDecision: ['NONE_SELECTED', 'RESOLVED'].includes(task.memberResolution) ? task.memberResolution : null,
    trelloMemberId: task.memberResolution === 'RESOLVED' ? task.trelloMemberId ?? null : null,
    priority: task.priority ?? null, includeEvidenceInCard: Boolean(task.includeEvidenceInCard),
  };
}

/** Builds the PATCH body for a pending edit. Deadline fields travel together so the server never mixes versions. */
export function patchBody(task, pending) {
  const body = { expectedVersion: task.version };
  for (const [key, value] of Object.entries(pending)) if (DRAFT_FIELDS.includes(key)) body[key] = value;
  if ('memberDecision' in body || 'trelloMemberId' in body) {
    const merged = { ...draftOf(task), ...pending };
    body.memberDecision = merged.memberDecision;
    if (merged.memberDecision === 'RESOLVED') body.trelloMemberId = merged.trelloMemberId; else delete body.trelloMemberId;
    if (merged.memberDecision !== 'RESOLVED') delete body.rememberAlias;
  }
  if ('dueLocal' in body || 'deadlineDecision' in body || 'timezone' in body) {
    const merged = { ...draftOf(task), ...pending };
    body.deadlineDecision = merged.deadlineDecision;
    if (merged.deadlineDecision === 'RESOLVED') { body.dueLocal = merged.dueLocal; body.timezone = merged.timezone; }
    else delete body.dueLocal;
  }
  return body;
}

export function splitDue(dueLocal) {
  if (!dueLocal) return { date: '', time: DEFAULT_TIME };
  const [date, time = DEFAULT_TIME] = dueLocal.split('T');
  return { date, time: time.slice(0, 5) };
}
export function joinDue(date, time) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date ?? '')) return null;
  return `${date}T${/^\d{2}:\d{2}$/.test(time ?? '') ? time : DEFAULT_TIME}`;
}
/** Full year, time and zone are always shown before confirmation (SDS §5.10). */
export function formatDue(dueLocal, timezone) {
  if (!dueLocal) return null;
  const { date, time } = splitDue(dueLocal);
  const [year, month, day] = date.split('-').map(Number);
  const weekday = WEEKDAYS[new Date(Date.UTC(year, month - 1, day)).getUTCDay()];
  return `${weekday}, ${String(day).padStart(2, '0')}/${String(month).padStart(2, '0')}/${year} lúc ${time} (${timezone ?? 'chưa có múi giờ'})`;
}

export function deadlineSummary(task) {
  switch (task.deadlineResolution) {
    case 'RESOLVED': return formatDue(task.dueLocal, task.timezone);
    case 'NONE_SELECTED': return 'Không đặt hạn';
    case 'AMBIGUOUS': return `“${task.deadlineRaw}” · cần xác nhận`;
    default: return 'Chưa có hạn';
  }
}
export function assigneeSummary(task) {
  if (task.memberResolution === 'NONE_SELECTED') return task.assigneeRaw ? `Không giao người (ghi chú: ${task.assigneeRaw})` : 'Không giao người';
  if (task.memberResolution === 'RESOLVED') return `${task.trelloMemberName ?? task.trelloMemberId} (thành viên Trello)`;
  if (task.memberResolution === 'SUGGESTED') return `${task.assigneeRaw} · gợi ý ${task.trelloMemberName ?? ''}, cần xác nhận`;
  if (task.memberResolution === 'AMBIGUOUS') return `${task.assigneeRaw} · nhiều thành viên trùng tên, cần chọn`;
  return task.assigneeRaw ? `${task.assigneeRaw} · chờ đối chiếu thành viên Trello` : 'Chưa rõ';
}

export function manualPayload(form, fallbackTimezone) {
  const taskName = form.taskName.trim();
  if (!taskName) throw new ApiError('Nhập tên công việc.', { code: 'TASK_NAME_REQUIRED' });
  if (taskName.length > 255) throw new ApiError('Tên công việc tối đa 255 ký tự.', { code: 'INVALID_INPUT' });
  const payload = { taskName };
  if (form.description?.trim()) payload.description = form.description.trim();
  if (form.assigneeRaw?.trim()) payload.assigneeRaw = form.assigneeRaw.trim();
  if (form.priority) payload.priority = form.priority;
  if (form.noDeadline) payload.deadlineDecision = 'NONE_SELECTED';
  else if (form.date) {
    payload.dueLocal = joinDue(form.date, form.time);
    payload.timezone = (form.timezone || fallbackTimezone || '').trim() || null;
    payload.deadlineDecision = 'RESOLVED';
    if (!payload.timezone) throw new ApiError('Chọn múi giờ cho hạn.', { code: 'UNRESOLVED_DEADLINE' });
  }
  if (form.noAssignee) payload.memberDecision = 'NONE_SELECTED';
  return payload;
}

export function assertTaskList(list, meeting) {
  if (list.meetingId !== meeting.meetingId || list.transcriptRevision !== meeting.transcriptRevision || list.inputVersion !== meeting.inputVersion) {
    throw new ApiError('Danh sách task thuộc input khác. Tải lại meeting.', { status: 409, code: 'STALE_VIEW' });
  }
  return list;
}

/** Why the create-card button is locked; empty reasons means the user may confirm (SDS §5.10, §5.14). */
export function createGate(tasks, selected, editorStates, trello = {}) {
  const chosen = tasks.filter(task => selected.has(task.taskId) && task.reviewStatus === 'PENDING_REVIEW' && task.editable);
  const reasons = [];
  if (!chosen.length) reasons.push('Chọn ít nhất một task.');
  const states = Object.values(editorStates);
  if (states.some(s => s === 'dirty' || s === 'saving')) reasons.push('Còn thay đổi đang chờ lưu.');
  if (states.some(s => s === 'error' || s === 'conflict')) reasons.push('Có task lưu lỗi hoặc bị xung đột phiên bản.');
  const blocked = chosen.filter(task => !task.readyForApproval).length;
  if (blocked) reasons.push(`${blocked} task còn cảnh báo cần xử lý (người phụ trách/hạn).`);
  if (!trello.connection) reasons.push('Kết nối Trello.');
  else if (trello.connection.status !== 'ACTIVE') reasons.push('Kết nối Trello đã hết quyền; kết nối lại.');
  if (!trello.destination) reasons.push('Chọn Board/List đích.');
  else if (trello.connection && trello.destination.trelloIdentity !== trello.connection.trelloMemberId) reasons.push('Board đích thuộc tài khoản Trello khác; chọn lại đích.');
  if (trello.busy) reasons.push('Đang gửi yêu cầu tạo card.');
  return { count: chosen.length, tasks: chosen, reasons };
}

/** Only real Trello links (or the local demo double on loopback) are rendered as links (SDS §10.4). */
export function safeTrelloUrl(url) {
  try {
    const parsed = new URL(url);
    if (parsed.username || parsed.password) return null;
    const trello = parsed.protocol === 'https:' && parsed.hostname === 'trello.com';
    const loopback = ['http:', 'https:'].includes(parsed.protocol) && ['127.0.0.1', 'localhost'].includes(parsed.hostname);
    return trello || loopback ? parsed.href : null;
  } catch { return null; }
}

/**
 * Debounced autosave for one task. Edits merge into `pending`; one request is in flight at a time and later edits
 * are sent after it with the newest version. Conflicts and errors are never retried automatically.
 */
export function createSaveQueue({ save, onSaved, onState, delayMs = 800, schedule = setTimeout, unschedule = clearTimeout }) {
  let pending = {};
  let inFlight = null;
  let timer;
  let state = 'saved';
  let lastError = null;
  const set = (next, error = null) => { state = next; lastError = error; onState(next, error); };
  const hasPending = () => Object.keys(pending).length > 0;
  function clear() { if (timer !== undefined) { unschedule(timer); timer = undefined; } }
  async function flush() {
    clear();
    if (inFlight || !hasPending() || state === 'conflict') return inFlight;
    const sent = pending; pending = {};
    set('saving');
    inFlight = (async () => {
      try {
        const task = await save(sent);
        inFlight = null;
        onSaved(task);
        if (hasPending()) { set('dirty'); timer = schedule(flush, delayMs); } else set('saved');
      } catch (error) {
        inFlight = null;
        pending = { ...sent, ...pending };
        set(error.code === 'STALE_VERSION' ? 'conflict' : 'error', error);
      }
    })();
    return inFlight;
  }
  return {
    edit(fields) {
      pending = { ...pending, ...fields };
      if (state === 'conflict' || state === 'error') { onState(state, lastError); return; }
      if (!inFlight && state !== 'dirty') set('dirty');
      clear(); timer = schedule(flush, delayMs);
    },
    flush,
    /** User chose to keep their edits after a conflict: resend against the version now loaded from the server. */
    overwrite() { if (state === 'conflict' || state === 'error') { set('dirty'); return flush(); } return flush(); },
    discard() { clear(); pending = {}; set('saved'); },
    pending: () => ({ ...pending }),
    state: () => state,
    stop() { clear(); },
  };
}
