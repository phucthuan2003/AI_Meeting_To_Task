import React, { useEffect, useMemo, useRef, useState } from 'react';
import { ErrorNotice } from './components.jsx';
import { isActiveJob } from './jobs.mjs';
import {
  PRIORITY_LABELS, assertTaskList, assigneeSummary, createGate, createSaveQueue, deadlineSummary, draftOf,
  formatDue, joinDue, manualPayload, patchBody, safeTrelloUrl, splitDue,
} from './review.mjs';
import { DestinationPicker, TrelloConnection } from './TrelloPanel.jsx';
import SyncPanel from './SyncPanel.jsx';

const SAVE_LABELS = { dirty: 'Chưa lưu', saving: 'Đang lưu…', saved: 'Đã lưu', error: 'Lỗi lưu', conflict: 'Xung đột phiên bản' };
const FIELD_LABELS = { TASK: 'Công việc', ASSIGNEE: 'Người phụ trách', DEADLINE: 'Hạn', PRIORITY: 'Ưu tiên' };
const EDIT_LABELS = { taskName: 'tên', description: 'mô tả', assigneeRaw: 'người phụ trách', deadlineRaw: 'câu hạn', priority: 'ưu tiên',
  dueLocal: 'ngày giờ hạn', deadlineDecision: 'quyết định hạn', memberDecision: 'quyết định người', includeEvidenceInCard: 'đính kèm bằng chứng',
  timezone: 'múi giờ', trelloMemberId: 'thành viên Trello', rememberAlias: 'ghi nhớ tên' };
const SYNC_LABELS = { QUEUED: 'Chờ tạo card', SYNCING: 'Đang tạo card', SYNCED: 'Đã tạo card', FAILED: 'Tạo card lỗi', UNKNOWN: 'Chưa rõ card' };
const serverValue = value => value === null || value === undefined || value === '' ? 'trống' : value === true ? 'có' : value === false ? 'không' : String(value);
const browserZone = () => { try { return Intl.DateTimeFormat().resolvedOptions().timeZone ?? ''; } catch { return ''; } };

export function Evidence({ api, token, task }) {
  const [detail, setDetail] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);
  if (!task.evidence.length) return task.origin === 'USER' ? <p className="small muted">Task thủ công không có bằng chứng AI.</p> : null;
  return <details className="evidence"><summary>Bằng chứng từ transcript ({task.evidence.length})</summary>
    {task.evidence.map((item, index) => <div className="segment" key={index}>
      <p className="small muted">Segment #{item.sequence + 1} · {FIELD_LABELS[item.field] ?? item.field}{item.speaker && ` · ${item.speaker}`}{item.timestamp && ` · ${item.timestamp}`}</p>
      {item.sourceAvailable ? <p>{item.quote}</p> : <p className="small muted">Nguồn không còn khả dụng.</p>}
    </div>)}
    {api && !detail && <button className="text" disabled={loading} onClick={async () => {
      setLoading(true); setError(null);
      try { setDetail(await api.taskEvidence(token, task.taskId)); } catch (failure) { setError(failure); } finally { setLoading(false); }
    }}>{loading ? 'Đang tải…' : 'Xem vị trí trong nguồn'}</button>}
    <ErrorNotice error={error} />
    {detail && detail.evidence.map((item, index) => <pre className="small locator" key={index}>
      {`#${item.sequence + 1} ${item.field}: ${item.sourceLocator ? JSON.stringify(item.sourceLocator) : 'không còn vị trí nguồn'}`}
    </pre>)}
  </details>;
}

export function TaskSummary({ task, display = draftOf(task), saveState }) {
  const shown = { ...task, ...display,
    deadlineResolution: display.deadlineDecision ?? (task.deadlineResolution === 'RESOLVED' || task.deadlineResolution === 'NONE_SELECTED'
      ? (display.deadlineRaw ? 'AMBIGUOUS' : 'MISSING') : task.deadlineResolution),
    memberResolution: display.memberDecision ?? (['NONE_SELECTED', 'RESOLVED'].includes(task.memberResolution) ? (task.memberCandidates?.length ? 'AMBIGUOUS' : 'MISSING') : task.memberResolution),
    trelloMemberName: display.trelloMemberId && display.trelloMemberId !== task.trelloMemberId ? display.trelloMemberName : task.trelloMemberName };
  const cardUrl = safeTrelloUrl(task.trelloCardUrl);
  return <>
    <div className="task-badges">
      <span className={`badge ${task.origin === 'AI' ? 'ai' : 'user'}`}>{task.origin === 'AI' ? 'Đề xuất AI' : 'Thủ công'}</span>
      {task.editedFields.length > 0 && <span className="badge edited" title={task.editedFields.map(f => EDIT_LABELS[f] ?? f).join(', ')}>Đã sửa</span>}
      {task.reviewStatus === 'REJECTED' && <span className="badge rejected">Đã loại bỏ</span>}
      {saveState && saveState !== 'saved' && <span className={`badge save ${saveState}`} role="status">{SAVE_LABELS[saveState]}</span>}
      {task.syncStatus && task.syncStatus !== 'NOT_SYNCED' && <span className={`badge sync ${task.syncStatus.toLowerCase()}`}>{SYNC_LABELS[task.syncStatus]}</span>}
    </div>
    {cardUrl && <p className="small"><a href={cardUrl} target="_blank" rel="noopener noreferrer">Mở card trên Trello</a></p>}
    {task.sync && task.syncStatus !== 'SYNCED' && task.sync.message && <p className="small muted">{task.sync.message}</p>}
    <p className="small">Người phụ trách: <strong>{assigneeSummary(shown)}</strong></p>
    <p className="small">Hạn: <strong>{deadlineSummary(shown)}</strong></p>
    {shown.deadlineRaw && shown.deadlineResolution !== 'AMBIGUOUS' && <p className="small muted">Câu gốc về hạn: “{shown.deadlineRaw}”</p>}
    {shown.priority && <p className="small">Ưu tiên: <strong>{PRIORITY_LABELS[shown.priority]}</strong></p>}
    {saveState === 'saved' || !saveState ? task.warnings.length > 0 && <ul className="warnings">
      {task.warnings.map(warning => <li key={warning.code} className={warning.blocking ? 'blocking' : ''}>{warning.message}</li>)}
    </ul> : <p className="small muted">Cảnh báo được tính lại sau khi lưu.</p>}
    {task.aiNotes.length > 0 && <p className="small muted">Ghi chú của AI: {task.aiNotes.join('; ')}</p>}
  </>;
}

function MemberChoice({ task, display, members, onEdit }) {
  const [remember, setRemember] = useState(true);
  if (!members) return <div className="choices" role="radiogroup" aria-label="Quyết định người phụ trách">
    <label className="choice"><input type="radio" checked={display.memberDecision !== 'NONE_SELECTED'} onChange={() => onEdit({ memberDecision: null })} />Chọn thành viên Trello sau khi chọn Board</label>
    <label className="choice"><input type="radio" checked={display.memberDecision === 'NONE_SELECTED'} onChange={() => onEdit({ memberDecision: 'NONE_SELECTED' })} />Không giao người</label>
  </div>;
  const candidates = new Set((task.memberCandidates ?? []).map(c => c.id));
  const ordered = [...members.filter(m => candidates.has(m.id)), ...members.filter(m => !candidates.has(m.id))];
  const value = display.memberDecision === 'NONE_SELECTED' ? '__none' : display.memberDecision === 'RESOLVED' ? display.trelloMemberId ?? '' : '';
  const choose = id => {
    if (id === '__none') onEdit({ memberDecision: 'NONE_SELECTED', trelloMemberId: null });
    else if (!id) onEdit({ memberDecision: null, trelloMemberId: null });
    else onEdit({ memberDecision: 'RESOLVED', trelloMemberId: id, rememberAlias: Boolean(remember && task.assigneeRaw) });
  };
  const suggested = task.memberResolution === 'SUGGESTED' && display.memberDecision !== 'RESOLVED' ? members.find(m => m.id === task.trelloMemberId) : null;
  return <>
    {suggested && <button type="button" className="text" onClick={() => choose(suggested.id)}>Xác nhận gợi ý: {suggested.fullName ?? suggested.username}</button>}
    <label>Thành viên Trello<select value={value} onChange={e => choose(e.target.value)}>
      <option value="">Chưa chọn</option>
      <option value="__none">Không giao người</option>
      {ordered.map(m => <option key={m.id} value={m.id}>{m.fullName ?? m.username}{m.username ? ` (@${m.username})` : ''}{candidates.has(m.id) ? ' — gợi ý theo tên' : ''}</option>)}
    </select></label>
    {task.assigneeRaw && <label className="choice"><input type="checkbox" checked={remember} onChange={e => setRemember(e.target.checked)} />Ghi nhớ “{task.assigneeRaw}” là thành viên đã chọn cho Board này</label>}
    <p className="small muted">Tên gần giống chỉ là gợi ý; hệ thống không tự giao người.</p>
  </>;
}

function TaskEditor({ task, display, meetingTimezone, onEdit, disabled, members, suggestion }) {
  const ai = task.aiSuggestion;
  const [name, setName] = useState(display.taskName);
  const [pickDue, setPickDue] = useState(false);
  const [due, setDue] = useState(splitDue(display.dueLocal));
  useEffect(() => { setName(display.taskName); }, [task.version]);
  useEffect(() => { setDue(splitDue(display.dueLocal)); }, [display.dueLocal]);
  const mode = display.deadlineDecision ?? (pickDue ? 'RESOLVED' : 'UNDECIDED');
  const zone = display.timezone || meetingTimezone || browserZone();
  const setDueParts = (next) => {
    setDue(next);
    const local = joinDue(next.date, next.time);
    if (local) onEdit({ deadlineDecision: 'RESOLVED', dueLocal: local, timezone: zone });
  };
  return <fieldset className="task-editor" disabled={disabled}>
    <label>Tên công việc<input maxLength={255} value={name} onChange={e => { setName(e.target.value); if (e.target.value.trim()) onEdit({ taskName: e.target.value }); }} /></label>
    {!name.trim() && <p className="small danger">Tên không được để trống; thay đổi này chưa được lưu.</p>}
    {ai && ai.taskName !== display.taskName && <p className="small muted">AI đề xuất: “{ai.taskName}”</p>}
    <label>Mô tả <span className="optional">(không bắt buộc)</span><textarea rows={3} maxLength={5000} value={display.description ?? ''}
      onChange={e => onEdit({ description: e.target.value === '' ? null : e.target.value })} /></label>

    <div className="field-group"><p className="group-title">Người phụ trách</p>
      <label>Tên trong cuộc họp<input maxLength={255} value={display.assigneeRaw ?? ''} placeholder="Chưa rõ"
        onChange={e => onEdit({ assigneeRaw: e.target.value === '' ? null : e.target.value })} /></label>
      {ai?.assigneeRaw && ai.assigneeRaw !== display.assigneeRaw && <p className="small muted">AI đề xuất: “{ai.assigneeRaw}”</p>}
      <MemberChoice task={task} display={display} members={members} onEdit={onEdit} />
    </div>

    <div className="field-group"><p className="group-title">Hạn</p>
      {display.deadlineRaw ? <p className="small">Câu gốc: “{display.deadlineRaw}”</p> : <p className="small muted">Cuộc họp không nêu hạn.</p>}
      <div className="choices" role="radiogroup" aria-label="Quyết định hạn">
        <label className="choice"><input type="radio" checked={mode === 'UNDECIDED'} onChange={() => { setPickDue(false); onEdit({ deadlineDecision: null, dueLocal: null }); }} />Chưa quyết định</label>
        <label className="choice"><input type="radio" checked={mode === 'RESOLVED'} onChange={() => setPickDue(true)} />Đặt ngày giờ</label>
        <label className="choice"><input type="radio" checked={mode === 'NONE_SELECTED'} onChange={() => { setPickDue(false); onEdit({ deadlineDecision: 'NONE_SELECTED', dueLocal: null }); }} />Không đặt hạn</label>
      </div>
      {mode === 'RESOLVED' && <>
        <div className="columns">
          <label>Ngày<input type="date" value={due.date} onChange={e => setDueParts({ ...due, date: e.target.value })} /></label>
          <label>Giờ<input type="time" value={due.time} onChange={e => setDueParts({ ...due, time: e.target.value })} /></label>
        </div>
        <label>Múi giờ<input maxLength={64} value={display.timezone ?? zone} onChange={e => onEdit({ timezone: e.target.value || null })} /></label>
        <p className="small muted">Chỉ có ngày thì giờ mặc định 17:00; bạn có thể sửa.</p>
      </>}
      {ai?.dueLocal && display.dueLocal !== ai.dueLocal && <button type="button" className="text" onClick={() => {
        setPickDue(true); onEdit({ deadlineDecision: 'RESOLVED', dueLocal: ai.dueLocal, timezone: ai.timezone || zone });
      }}>Dùng đề xuất AI: {formatDue(ai.dueLocal, ai.timezone || zone)}</button>}
      {suggestion?.suggestedDueLocal && display.dueLocal !== suggestion.suggestedDueLocal && <button type="button" className="text" onClick={() => {
        setPickDue(true); onEdit({ deadlineDecision: 'RESOLVED', dueLocal: suggestion.suggestedDueLocal, timezone: suggestion.timezone || zone });
      }}>Dùng gợi ý: {formatDue(suggestion.suggestedDueLocal, suggestion.timezone || zone)}</button>}
      {suggestion?.note && <p className="small muted">{suggestion.note}</p>}
      {display.deadlineDecision === 'RESOLVED' && display.dueLocal && <p className="notice">Hạn sẽ dùng: <strong>{formatDue(display.dueLocal, display.timezone ?? zone)}</strong></p>}
    </div>

    <label>Ưu tiên<select value={display.priority ?? ''} onChange={e => onEdit({ priority: e.target.value || null })}>
      <option value="">Không đặt</option>{Object.entries(PRIORITY_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
    </select></label>
    {ai?.priority === null && display.priority && <p className="small muted">Cuộc họp không nêu mức ưu tiên; đây là lựa chọn của bạn.</p>}
    {task.origin === 'AI' && <label className="choice"><input type="checkbox" checked={display.includeEvidenceInCard}
      onChange={e => onEdit({ includeEvidenceInCard: e.target.checked })} />Đính kèm trích dẫn bằng chứng vào card (xem trước ở bước tạo card)</label>}
  </fieldset>;
}

export function TaskCard({ api, token, task, selected, onSelect, onSaved, onStateChange, onConflict, onReject, onRestore, meetingTimezone, disabled, members, suggestion }) {
  const [open, setOpen] = useState(false);
  const [overlay, setOverlay] = useState({});
  const [saveState, setSaveState] = useState('saved');
  const [saveError, setSaveError] = useState(null);
  const taskRef = useRef(task);
  taskRef.current = task;
  const queue = useRef(null);
  if (!queue.current) queue.current = createSaveQueue({
    save: pending => api.patchTask(token, taskRef.current.taskId, patchBody(taskRef.current, pending)),
    onSaved: view => { taskRef.current = view; onSaved(view); },
    onState: (state, error) => {
      setSaveState(state); setSaveError(error); onStateChange(task.taskId, state);
      if (state === 'saved') setOverlay({});
      if (state === 'conflict' || error?.code === 'TASK_NOT_CURRENT' || error?.code === 'TASK_REJECTED') onConflict();
    },
  });
  useEffect(() => () => { queue.current.flush(); queue.current.stop(); onStateChange(task.taskId, null); }, []);
  const display = { ...draftOf(task), ...overlay };
  const edit = fields => {
    const shown = { ...fields };
    if (fields.trelloMemberId) shown.trelloMemberName = members?.find(m => m.id === fields.trelloMemberId)?.fullName;
    delete shown.rememberAlias;
    setOverlay(current => ({ ...current, ...shown })); queue.current.edit(fields);
  };
  const rejected = task.reviewStatus === 'REJECTED';
  const pendingFields = Object.keys(overlay).map(f => EDIT_LABELS[f] ?? f);
  const busy = saveState === 'dirty' || saveState === 'saving';
  return <article className={`task-card${rejected ? ' is-rejected' : ''}`}>
    <div className="task-head">
      {!rejected && <input type="checkbox" aria-label={`Chọn ${display.taskName}`} checked={selected} disabled={!task.editable}
        onChange={e => onSelect(task.taskId, e.target.checked)} />}
      <h3>{display.taskName || task.taskName}</h3>
    </div>
    <TaskSummary task={task} display={display} saveState={saveState} />
    {saveState === 'conflict' && <div className="notice error" role="alert">
      <strong>Task đã được sửa ở cửa sổ khác.</strong>
      <div className="small">Thay đổi của bạn ({pendingFields.join(', ') || 'không còn'}) chưa được lưu và vẫn đang hiển thị.</div>
      {Object.keys(overlay).length > 0 && <ul className="small">{Object.keys(overlay).map(field => <li key={field}>
        Trên server (version {task.version}), {EDIT_LABELS[field] ?? field}: “{serverValue(draftOf(task)[field])}”</li>)}</ul>}
      <div className="actions"><button onClick={() => queue.current.overwrite()}>Giữ thay đổi của tôi và lưu</button>
        <button onClick={() => { queue.current.discard(); setOverlay({}); onConflict(); }}>Bỏ thay đổi của tôi</button></div>
    </div>}
    {saveState === 'error' && <><ErrorNotice error={saveError} />
      <div className="actions"><button onClick={() => queue.current.overwrite()}>Thử lưu lại</button>
        <button onClick={() => { queue.current.discard(); setOverlay({}); onConflict(); }}>Bỏ thay đổi chưa lưu</button></div></>}
    <Evidence api={api} token={token} task={task} />
    {open && task.editable && <TaskEditor task={task} display={display} meetingTimezone={meetingTimezone} onEdit={edit} disabled={disabled} members={members} suggestion={suggestion} />}
    <div className="actions">
      {task.editable && <button onClick={() => { if (open) queue.current.flush(); setOpen(!open); }}>{open ? 'Thu gọn' : 'Sửa'}</button>}
      {!rejected && task.editable && <button disabled={disabled || busy || saveState === 'conflict'} onClick={() => onReject(task)}>Loại bỏ</button>}
      {rejected && <button disabled={disabled} onClick={() => onRestore(task)}>Khôi phục</button>}
    </div>
    {!task.editable && !rejected && <p className="small muted">{task.syncStatus === 'SYNCED' ? 'Card đã tạo; chỉ xem và mở trên Trello.' : ['QUEUED', 'SYNCING', 'UNKNOWN'].includes(task.syncStatus) ? 'Task đang tạo card hoặc chờ đối soát nên tạm khóa sửa.' : 'Task không còn chỉnh sửa được (không thuộc kết quả hiện hành).'}</p>}
    {task.syncStatus === 'FAILED' && task.editable && <p className="small muted">Tạo card lỗi chắc chắn: sửa task sẽ đưa về trạng thái chờ duyệt để tạo lại.</p>}
  </article>;
}

export function ManualTaskForm({ api, token, meeting, onCreated, onCancel }) {
  const [form, setForm] = useState({ taskName: '', description: '', assigneeRaw: '', priority: '', date: '', time: '17:00',
    timezone: meeting.timezone || browserZone(), noDeadline: false, noAssignee: false });
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState(false);
  const pending = useRef(null);
  const update = (key, value) => setForm(current => ({ ...current, [key]: value }));
  return <form className="card manual" onSubmit={async event => {
    event.preventDefault(); setError(null);
    let payload;
    try { payload = manualPayload(form, meeting.timezone); } catch (failure) { setError(failure); return; }
    // One key per intended task: resending after a lost response returns the same task instead of a duplicate.
    if (!pending.current || JSON.stringify(pending.current.payload) !== JSON.stringify(payload)) pending.current = { key: crypto.randomUUID(), payload };
    setSaving(true);
    try { onCreated(await api.createTask(token, meeting.meetingId, payload, pending.current.key)); pending.current = null; }
    catch (failure) { if (failure.status >= 400 && failure.status < 500 && failure.status !== 408) pending.current = null; setError(failure); }
    finally { setSaving(false); }
  }}>
    <p className="eyebrow">TASK THỦ CÔNG</p>
    <fieldset disabled={saving}>
      <label>Tên công việc<input maxLength={255} required value={form.taskName} onChange={e => update('taskName', e.target.value)} placeholder="Ví dụ: Gửi biên bản họp" /></label>
      <label>Người phụ trách <span className="optional">(tên, không bắt buộc)</span><input maxLength={255} value={form.assigneeRaw} onChange={e => update('assigneeRaw', e.target.value)} /></label>
      <label className="choice"><input type="checkbox" checked={form.noAssignee} onChange={e => update('noAssignee', e.target.checked)} />Không giao người</label>
      <label>Mô tả <span className="optional">(không bắt buộc)</span><textarea rows={2} maxLength={5000} value={form.description} onChange={e => update('description', e.target.value)} /></label>
      <label className="choice"><input type="checkbox" checked={form.noDeadline} onChange={e => update('noDeadline', e.target.checked)} />Không đặt hạn</label>
      {!form.noDeadline && <><div className="columns">
        <label>Ngày hạn<input type="date" value={form.date} onChange={e => update('date', e.target.value)} /></label>
        <label>Giờ<input type="time" value={form.time} onChange={e => update('time', e.target.value)} /></label>
      </div>
        <label>Múi giờ<input maxLength={64} value={form.timezone} onChange={e => update('timezone', e.target.value)} /></label>
        {form.date && <p className="small muted">Hạn: {formatDue(joinDue(form.date, form.time), form.timezone || meeting.timezone)}</p>}</>}
      <label>Ưu tiên<select value={form.priority} onChange={e => update('priority', e.target.value)}>
        <option value="">Không đặt</option>{Object.entries(PRIORITY_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
      </select></label>
      <ErrorNotice error={error} />
      <div className="actions"><button className="primary" type="submit">{saving ? 'Đang lưu…' : 'Thêm task'}</button>
        <button type="button" onClick={onCancel}>Hủy</button></div>
    </fieldset>
  </form>;
}

export function CreateBar({ gate, onCreate }) {
  return <div className="create-bar">
    <button className="primary full" disabled={gate.reasons.length > 0 || !onCreate} onClick={onCreate}>Tạo {gate.count} card trên Trello</button>
    {gate.reasons.length > 0 && <ul className="small muted">{gate.reasons.map(reason => <li key={reason}>{reason}</li>)}</ul>}
  </div>;
}

/** Last check before the single confirming action (SDS §5.10, §5.14): full date/time/zone, member and destination per card. */
export function ConfirmCreate({ api, token, tasks, destination, onConfirm, onCancel, busy, error }) {
  const [previews, setPreviews] = useState({});
  return <div className="confirm-create" role="dialog" aria-label="Xác nhận tạo card">
    <p className="eyebrow">XÁC NHẬN</p>
    <h3>Tạo {tasks.length} card trong {destination.boardName} › {destination.listName}</h3>
    <ol className="confirm-list">{tasks.map(task => <li key={task.taskId}>
      <strong className="break">{task.taskName}</strong>
      <div className="small">Giao cho: {task.memberResolution === 'RESOLVED' ? task.trelloMemberName ?? task.trelloMemberId : 'Không giao người'}</div>
      <div className="small">Hạn: {task.deadlineResolution === 'RESOLVED' ? formatDue(task.dueLocal, task.timezone) : 'Không đặt hạn'}</div>
      {task.priority && <div className="small">Ưu tiên (ghi vào mô tả): {PRIORITY_LABELS[task.priority]}</div>}
      {task.includeEvidenceInCard && <div className="small">Kèm trích dẫn bằng chứng trong mô tả</div>}
      {previews[task.taskId] ? <pre className="small locator">{previews[task.taskId].payload.desc}</pre>
        : <button className="text" onClick={async () => {
          try { const value = await api.cardPreview(token, task.taskId); setPreviews(current => ({ ...current, [task.taskId]: value })); } catch { /* preview is optional */ }
        }}>Xem trước mô tả card</button>}
    </li>)}</ol>
    <p className="small muted">Sau khi xác nhận, backend lưu bản chụp đúng nội dung trên; task bị khóa sửa trong lúc tạo card. Thay đổi trên Trello không được đồng bộ ngược.</p>
    <ErrorNotice error={error} />
    {error?.details?.length > 0 && <ul className="small danger">{error.details.map((d, i) => <li key={i}>{tasks.find(t => t.taskId === d.taskId)?.taskName ?? d.taskId}: {d.code}</li>)}</ul>}
    <div className="actions"><button className="primary" disabled={busy} onClick={onConfirm}>{busy ? 'Đang gửi…' : `Xác nhận tạo ${tasks.length} card`}</button>
      <button disabled={busy} onClick={onCancel}>Quay lại</button></div>
  </div>;
}

export function AnalysisNote({ analysis, aiCount }) {
  if (!analysis) return <p className="small muted">Chưa có lượt phân tích hiện hành. Bạn vẫn có thể thêm task thủ công.</p>;
  if (isActiveJob(analysis.status)) return <p className="notice">AI đang phân tích. Đề xuất sẽ hiện khi job hoàn tất; task thủ công vẫn thêm được.</p>;
  if (analysis.status === 'COMPLETED') return <>
    {aiCount === 0 && <p className="notice">Không tìm thấy công việc cần thực hiện trong transcript. Đọc lại nội dung hoặc thêm task thủ công.</p>}
    {analysis.warnings.map((warning, index) => <p className="notice" key={index}>Kết quả AI có tham chiếu chưa liên kết: {warning}</p>)}
    <p className="small muted">{analysis.providerId === 'openai' ? 'OpenAI' : 'Gemini'} · {analysis.model} · input {analysis.inputTokens ?? '—'}, output {analysis.outputTokens ?? '—'} tokens · {analysis.latencyMs ?? '—'} ms</p>
  </>;
  return <p className="notice">Phân tích chưa hoàn tất nên chưa có đề xuất AI. Bạn có thể thử lại job hoặc thêm task thủ công.</p>;
}

export default function ReviewPanel({ api, token, meeting, job, onDirty, disabled }) {
  const [list, setList] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [selected, setSelected] = useState(new Set());
  const [states, setStates] = useState({});
  const [manualOpen, setManualOpen] = useState(false);
  const [showRejected, setShowRejected] = useState(false);
  const [acting, setActing] = useState(false);
  const [config, setConfig] = useState(null);
  const [connection, setConnection] = useState(null);
  const [destination, setDestination] = useState(null);
  const [suggestions, setSuggestions] = useState({});
  const [confirming, setConfirming] = useState(false);
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState(null);
  const [syncJobId, setSyncJobId] = useState(meeting.latestSyncJobId ?? null);
  const generation = useRef(0);
  const known = useRef(new Set());
  const pendingCreate = useRef(null);
  const pinned = job?.status === 'COMPLETED' ? job.jobId : null;
  const jobKey = job ? `${job.jobId}:${isActiveJob(job) ? 'active' : job.status}` : 'none';

  async function load() {
    const current = ++generation.current;
    setLoading(true); setError(null);
    try {
      const [value, dest, connections, cfg] = await Promise.all([api.tasks(token, meeting.meetingId, pinned), api.destination(token, meeting.meetingId),
        api.trelloConnections(token), config ? Promise.resolve(config) : api.trelloConfig(token)]);
      assertTaskList(value, meeting);
      if (current !== generation.current) return;
      setList(value); setDestination(dest); setConnection(connections[0] ?? null); setConfig(cfg);
      // New pending tasks start selected; the user's earlier choices are kept across reloads.
      setSelected(previous => {
        const next = new Set([...previous].filter(id => value.tasks.some(t => t.taskId === id && t.editable)));
        for (const task of value.tasks) if (!known.current.has(task.taskId)) { known.current.add(task.taskId); if (task.editable && task.reviewStatus === 'PENDING_REVIEW') next.add(task.taskId); }
        return next;
      });
    } catch (failure) { if (current === generation.current) setError(failure); }
    finally { if (current === generation.current) setLoading(false); }
  }
  const latestLoad = useRef(load);
  latestLoad.current = load;
  const reload = () => latestLoad.current();
  useEffect(() => { load(); return () => { generation.current++; }; }, [meeting.meetingId, meeting.inputVersion, jobKey]);
  const unsaved = Object.values(states).some(state => state && state !== 'saved');
  useEffect(() => { onDirty?.(unsaved); }, [unsaved]);
  useEffect(() => () => onDirty?.(false), []);

  const replace = view => setList(current => current && ({ ...current, tasks: current.tasks.map(task => task.taskId === view.taskId ? view : task) }));
  const setState = (taskId, state) => setStates(current => {
    if (state === null) { const { [taskId]: _, ...rest } = current; return rest; }
    return current[taskId] === state ? current : { ...current, [taskId]: state };
  });
  async function act(request) {
    setActing(true); setError(null);
    try { await request(); } catch (failure) { setError(failure); }
    finally { setActing(false); }
    await reload();
  }

  const tasks = list?.tasks ?? [];
  const pending = tasks.filter(task => task.reviewStatus !== 'REJECTED');
  const rejected = tasks.filter(task => task.reviewStatus === 'REJECTED');
  const inFlight = tasks.some(task => ['QUEUED', 'SYNCING', 'UNKNOWN'].includes(task.syncStatus));
  const gate = useMemo(() => createGate(tasks, selected, states, { connection, destination, busy: creating }), [tasks, selected, states, connection, destination, creating]);
  const needsAttention = pending.filter(task => task.editable && task.reviewStatus === 'PENDING_REVIEW' && !task.readyForApproval).length;
  const members = destination && connection?.status === 'ACTIVE' ? destination.members : null;
  const versions = Object.fromEntries(tasks.map(t => [t.taskId, t.version]));
  const cardProps = task => ({ api, token, task, meetingTimezone: meeting.timezone, disabled: disabled || acting,
    selected: selected.has(task.taskId), onSaved: replace, onStateChange: setState, onConflict: reload, members, suggestion: suggestions[task.taskId],
    onSelect: (id, value) => setSelected(current => { const next = new Set(current); value ? next.add(id) : next.delete(id); return next; }),
    onReject: target => act(() => api.rejectTask(token, target.taskId, target.version)),
    onRestore: target => act(() => api.restoreTask(token, target.taskId, target.version)) });

  async function create() {
    const body = { expectedDestinationVersion: destination.version, tasks: gate.tasks.map(t => ({ taskId: t.taskId, expectedVersion: t.version })) };
    // One key per intended confirmation; a lost response is resent with the same key and returns the same sync job.
    if (!pendingCreate.current || JSON.stringify(pendingCreate.current.body) !== JSON.stringify(body)) pendingCreate.current = { key: crypto.randomUUID(), body };
    setCreating(true); setCreateError(null);
    try {
      const accepted = await api.approveAndSync(token, meeting.meetingId, body, pendingCreate.current.key);
      pendingCreate.current = null; setConfirming(false); setSyncJobId(accepted.syncJobId);
      setSelected(new Set());
    } catch (failure) {
      if (failure.status >= 400 && failure.status < 500 && failure.status !== 408) pendingCreate.current = null;
      setCreateError(failure);
    } finally { setCreating(false); await reload(); }
  }

  return <>
  <section className="card trello">
    <p className="eyebrow">TRELLO</p>
    <TrelloConnection api={api} token={token} config={config} connection={connection} onChanged={reload} disabled={disabled || acting} />
    <DestinationPicker api={api} token={token} meeting={meeting} connection={connection} destination={destination} disabled={disabled || acting} locked={inFlight}
      onSaved={async saved => { setDestination(saved); setSuggestions({}); await reload(); }} />
    {destination && connection?.status === 'ACTIVE' && <div className="actions">
      <button disabled={disabled || acting} onClick={() => act(async () => { await api.resolveMembers(token, meeting.meetingId, destination.version); })}>Đối chiếu người phụ trách</button>
      <button disabled={disabled || acting} onClick={() => act(async () => {
        const result = await api.resolveDeadlines(token, meeting.meetingId);
        setSuggestions(Object.fromEntries(result.map(s => [s.taskId, s])));
      })}>Gợi ý hạn từ ngày họp</button>
    </div>}
  </section>
  <section className="card review">
    <div className="row"><div><p className="eyebrow">REVIEW CÔNG VIỆC</p><h3>{pending.length} task · {needsAttention} cần xử lý</h3></div>
      <button disabled={loading || acting} onClick={reload}>Tải lại</button></div>
    <p className="small muted">Bản nháp lưu tự động trên backend. AI chỉ đề xuất; bạn kiểm tra trước khi tạo card.</p>
    {loading && !list && <p className="small muted">Đang tải danh sách task…</p>}
    <ErrorNotice error={error} />
    {list && <AnalysisNote analysis={list.analysis} aiCount={tasks.filter(t => t.origin === 'AI').length} />}
    {pending.map(task => <TaskCard key={task.taskId} {...cardProps(task)} />)}
    {manualOpen ? <ManualTaskForm api={api} token={token} meeting={meeting} onCancel={() => setManualOpen(false)} onCreated={async view => {
      setManualOpen(false); known.current.add(view.taskId); setSelected(current => new Set(current).add(view.taskId)); await reload();
    }} /> : <button className="full" disabled={disabled || !list} onClick={() => setManualOpen(true)}>+ Thêm task thủ công</button>}
    {rejected.length > 0 && <details className="rejected" open={showRejected} onToggle={e => setShowRejected(e.currentTarget.open)}>
      <summary>Đã loại bỏ ({rejected.length})</summary>
      {rejected.map(task => <TaskCard key={task.taskId} {...cardProps(task)} />)}
    </details>}
    {confirming && destination ? <ConfirmCreate api={api} token={token} tasks={gate.tasks} destination={destination} busy={creating} error={createError}
      onConfirm={create} onCancel={() => { setConfirming(false); setCreateError(null); }} />
      : list && <CreateBar gate={gate} onCreate={() => { setCreateError(null); setConfirming(true); }} />}
  </section>
  {syncJobId && <SyncPanel api={api} token={token} jobId={syncJobId} taskVersions={versions} onChanged={reload} disabled={disabled} />}
  </>;
}
