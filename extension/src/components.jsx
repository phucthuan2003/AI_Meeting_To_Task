import React, { useEffect, useState } from 'react';
import { MAX_TEXT, pastePayload, validateFile, validateCredentials } from './input.mjs';
import Icon from './icons.jsx';

export function ErrorNotice({ error }) {
  const [open, setOpen] = useState(false);
  if (!error) return null;
  const technical = error.code || error.traceId;
  return <div className="notice error" role="alert">
    <div className="notice-row"><Icon name="warning" size={16} /><strong>{error.message}</strong></div>
    {error.retryAfter && <div>Chờ {error.retryAfter} giây trước khi thử lại.</div>}
    {error.details?.map((item, index) => <div key={index}>{item.field}: {item.message}</div>)}
    {technical && <>
      <button type="button" className="link-button" onClick={() => setOpen(!open)}>Chi tiết kỹ thuật</button>
      {(open || !error.message) && <div className="small mono">{error.code}{error.traceId && ` · Trace: ${error.traceId}`}</div>}
      {!open && error.message && <span className="sr-only">{error.code}{error.traceId && ` · Trace: ${error.traceId}`}</span>}
    </>}
  </div>;
}

/** Slide-up panel used for task edit, confirmation and menus. */
export function Sheet({ title, onClose, children, footer }) {
  useEffect(() => {
    const key = event => { if (event.key === 'Escape') onClose?.(); };
    window.addEventListener('keydown', key);
    return () => window.removeEventListener('keydown', key);
  }, [onClose]);
  return <div className="sheet-backdrop" onClick={onClose}>
    <div className="sheet" role="dialog" aria-label={title} onClick={event => event.stopPropagation()}>
      <div className="sheet-handle" />
      <div className="sheet-head"><h3>{title}</h3>{onClose && <button type="button" className="icon-button" aria-label="Đóng" onClick={onClose}><Icon name="close" size={16} /></button>}</div>
      <div className="sheet-body">{children}</div>
      {footer && <div className="sheet-foot">{footer}</div>}
    </div>
  </div>;
}

export function Avatar({ name, tone = 0 }) {
  const letter = (name ?? '?').trim().split(/\s+/).pop()?.[0]?.toUpperCase() ?? '?';
  return <span className={`avatar tone-${tone % 3}`} aria-hidden="true">{letter}</span>;
}

export function AuthForm({ busy, onSubmit, health }) {
  const [register, setRegister] = useState(false);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [show, setShow] = useState(false);
  const [error, setError] = useState(null);
  return <div className="auth">
    <div className="hero">
      <div className="hero-logo">M</div>
      <h1>Biến cuộc họp thành việc cần làm</h1>
      <p className="muted">Dán transcript hoặc ghi âm — AI tách việc, bạn duyệt, card lên Trello.</p>
    </div>
    <section className="card">
      <form onSubmit={(event) => {
        event.preventDefault(); setError(null);
        try { onSubmit(validateCredentials(email, password), register, () => setPassword('')); }
        catch (failure) { setError(failure); }
      }}>
        <fieldset disabled={busy}>
          <label>Email<span className="field"><Icon name="mail" size={16} /><input type="email" maxLength={254} autoComplete="username" required value={email}
            placeholder="ten@email.com" onChange={e => setEmail(e.target.value)} /></span></label>
          <label>Mật khẩu<span className="field"><Icon name="lock" size={16} /><input type={show ? 'text' : 'password'} maxLength={72} minLength={10}
            autoComplete={register ? 'new-password' : 'current-password'} required value={password} onChange={e => setPassword(e.target.value)} />
            <button type="button" className="icon-button" aria-label={show ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'} onClick={() => setShow(!show)}><Icon name="eye" size={16} /></button></span></label>
          <p className="small muted">10–72 ký tự, tối đa 72 byte UTF-8.</p>
          <ErrorNotice error={error} />
          <button className="primary full big" type="submit">{busy ? 'Đang kết nối…' : register ? 'Đăng ký và đăng nhập' : 'Đăng nhập'}{!busy && <Icon name="arrow" size={16} />}</button>
          <p className="center small">{register ? 'Đã có tài khoản? ' : 'Chưa có tài khoản? '}
            <button className="link-button" type="button" onClick={() => { setRegister(!register); setError(null); }}>{register ? 'Đăng nhập' : 'Tạo tài khoản'}</button></p>
        </fieldset>
      </form>
    </section>
    {health}
  </div>;
}

/** Paste or file input. `tabs` (source switcher) is rendered by the caller so audio can share the same card. */
export function InputForm({ initial, version, busy, onSave, onDirty, onCancel, mode = 'paste', tabs }) {
  const [draft, setDraft] = useState(initial ?? {
    title: '', meetingDate: '', timezone: Intl.DateTimeFormat().resolvedOptions().timeZone ?? '', transcriptText: '',
  });
  const [file, setFile] = useState(null);
  const [error, setError] = useState(null);
  const editing = version !== undefined;
  const kind = editing ? 'paste' : mode;
  const update = (key, value) => { setDraft(current => ({ ...current, [key]: value })); onDirty?.(); };
  return <form className="input-form" onSubmit={event => {
    event.preventDefault(); setError(null);
    try {
      if (kind === 'paste') onSave({ kind: 'paste', input: pastePayload(draft, version) });
      else { validateFile(file); onSave({ kind: 'file', file, metadata: { title: draft.title.trim(), meetingDate: draft.meetingDate, timezone: draft.timezone.trim() } }); }
    } catch (failure) { setError(failure); }
  }}>
    <section className="card">
      {editing && <p className="eyebrow">THAY NỘI DUNG · PHIÊN BẢN {version}</p>}
      {!editing && tabs}
      {editing && <p className="notice">Lưu sẽ tạo revision mới từ văn bản bạn nhập. Nguồn file cũ vẫn thuộc revision trước. Tải lại nếu gặp version conflict.</p>}
      <fieldset disabled={busy}>
        {kind === 'paste' ? <div className="workspace">
          <textarea rows={8} aria-label="Nội dung cuộc họp" value={draft.transcriptText} onChange={e => update('transcriptText', e.target.value)}
            placeholder="Dán nội dung cuộc họp… (giữ tên người nói và timestamp nếu có)" required />
          <div className="workspace-foot"><span className="small muted"><Icon name="quote" size={13} /> Ví dụ: “Minh: Lan gửi biên bản trước thứ Sáu nhé.”</span>
            <span className={`small mono ${draft.transcriptText.length > MAX_TEXT ? 'danger' : 'muted'}`}>{draft.transcriptText.length.toLocaleString('vi-VN')} / 200.000</span></div>
        </div> : <label className="dropzone">
          <Icon name="upload" size={26} />
          <strong>{file ? file.name : 'Chọn file transcript'}</strong>
          <span className="small muted">TXT, DOCX, VTT, SRT (UTF-8) · tối đa 10 MiB</span>
          <input type="file" accept=".txt,.docx,.vtt,.srt" onChange={e => { setFile(e.target.files?.[0] ?? null); onDirty?.(); }} required />
        </label>}
        <label className="pill-field" title="Tên cuộc họp"><Icon name="edit" size={15} /><input maxLength={255} aria-label="Tên cuộc họp" value={draft.title}
          onChange={e => update('title', e.target.value)} placeholder="Tên cuộc họp (tuỳ chọn)" /></label>
        <div className="meta-row">
          <label className="pill-field" title="Ngày họp"><Icon name="calendar" size={15} /><input type="date" aria-label="Ngày họp" value={draft.meetingDate} onChange={e => update('meetingDate', e.target.value)} /></label>
          <label className="pill-field" title="Múi giờ"><Icon name="globe" size={15} /><input maxLength={64} aria-label="Múi giờ" value={draft.timezone}
            onChange={e => update('timezone', e.target.value)} placeholder="Asia/Ho_Chi_Minh" /></label>
        </div>
        <ErrorNotice error={error} />
      </fieldset>
    </section>
    <div className="action-bar">
      <button className="primary full big" type="submit" disabled={busy}>{busy ? 'Đang lưu…' : editing ? 'Lưu revision mới' : <><Icon name="sparkle" size={18} />Lưu & phân tích bằng AI</>}</button>
      {onCancel && <button type="button" className="quiet full" onClick={onCancel}>Hủy sửa</button>}
    </div>
  </form>;
}

export function MeetingDetail({ meeting, source, busy, inputLocked, onMore, onReload, onEdit, onDeleteTranscript, onDeleteMeeting }) {
  return <>
    <section className="card">
      <p className="eyebrow">NỘI DUNG GỐC · VERSION {meeting.inputVersion}</p>
      <h2>{meeting.title || 'Cuộc họp chưa đặt tên'}</h2>
      <p className="muted small">{meeting.meetingDate || 'Chưa có ngày họp'} · {meeting.timezone || 'Chưa có múi giờ'}</p>
      <div className="stats"><div><strong>{meeting.characterCount.toLocaleString('vi-VN')}</strong><span>ký tự UTF-16</span></div><div><strong>{meeting.segmentCount}</strong><span>segments</span></div></div>
      <h4>Preview văn bản chuẩn hóa</h4><pre className="transcript">{meeting.preview}</pre>
      {meeting.characterCount > meeting.preview.length && <p className="small muted">Preview tối đa 4.000 ký tự. Xem segments bên dưới để đọc tiếp.</p>}
      {meeting.warnings.map((warning, index) => <p className="notice" key={index}>{warning === 'DOCX_NON_BODY_CONTENT_OMITTED' ? 'Header, footer hoặc comment của DOCX không được nhập vào transcript.' : warning}</p>)}
      <div className="actions"><button disabled={busy} onClick={onReload}>Tải lại</button><button disabled={busy || inputLocked} onClick={onEdit}>Thay input</button></div>
    </section>
    <section className="card">
      <h4>Nội dung & vị trí nguồn</h4>
      {!source ? <p className="muted small">Chưa tải nguồn. Bấm Tải lại.</p> : !source.sourceAvailable ? <p className="notice">Nguồn hiện không còn khả dụng.</p> : <>
        {source.segments.map(segment => <details key={segment.segmentId} className="segment">
          <summary><span className="small muted">#{segment.sequence + 1}{segment.timestamp && ` · ${segment.timestamp}`}</span><p>{segment.text}</p></summary>
          <h4>Văn bản nguồn</h4><pre className="transcript">{segment.sourceText}</pre>
        </details>)}
        <p className="small muted">Đã tải {source.segments.length} / {meeting.segmentCount} segments.</p>
        {source.nextCursor !== null && <button className="full" disabled={busy} onClick={onMore}>Xem thêm nội dung</button>}
      </>}
    </section>
    {(onDeleteTranscript || onDeleteMeeting) && <section className="card danger-zone">
      <p className="eyebrow">DỮ LIỆU</p>
      <div className="actions">
        {onDeleteTranscript && meeting.sourceAvailable !== false && <button className="danger-button" disabled={busy} onClick={onDeleteTranscript}><Icon name="trash" size={15} />Xóa nội dung transcript</button>}
        {onDeleteMeeting && <button className="danger-button" disabled={busy} onClick={onDeleteMeeting}><Icon name="trash" size={15} />Xóa meeting</button>}
      </div>
    </section>}
  </>;
}

export const historyStatus = { NOT_STARTED: 'Chưa phân tích', QUEUED: 'Đang chờ phân tích', PROCESSING: 'Đang phân tích', CANCEL_REQUESTED: 'Đang hủy',
  COMPLETED: 'Đã phân tích', PARTIAL_FAILED: 'Phân tích chưa đủ', FAILED: 'Phân tích lỗi', CANCELLED: 'Đã hủy phân tích' };
const ACTIVE = ['QUEUED', 'PROCESSING', 'CANCEL_REQUESTED'];

function statusTone(item) {
  if (ACTIVE.includes(item.analysisStatus)) return 'busy';
  if (item.analysisStatus === 'COMPLETED') return item.pendingTasks > 0 ? 'warn' : 'ok';
  if (['FAILED', 'PARTIAL_FAILED'].includes(item.analysisStatus)) return 'bad';
  return 'idle';
}

export function MeetingRow({ item, busy, onOpen }) {
  const tone = statusTone(item);
  return <button className="meeting-row" disabled={busy} onClick={() => onOpen(item.meetingId)}>
    <span className={`row-icon ${tone}`}><Icon name={tone === 'ok' ? 'checkCircle' : tone === 'busy' ? 'refresh' : tone === 'bad' ? 'warning' : 'list'} size={17} className={tone === 'busy' ? 'spin' : ''} /></span>
    <span className="row-main">
      <strong>{item.title || 'Cuộc họp chưa đặt tên'}</strong>
      <span className="small muted">{item.meetingDate || 'Chưa có ngày họp'} · v{item.inputVersion}
        {item.pendingTasks > 0 && ` · ${item.pendingTasks} task chờ duyệt`}{item.rejectedTasks > 0 && ` · ${item.rejectedTasks} đã loại`}</span>
      <span className="tiny muted">Lưu {new Date(item.createdAt).toLocaleString('vi-VN')}</span>
    </span>
    {item.analysisStatus && <span className={`pill ${tone}`}>{historyStatus[item.analysisStatus] ?? item.analysisStatus}</span>}
  </button>;
}

export function History({ history, busy, onRefresh, onMore, onOpen, onBack }) {
  return <section>
    <div className="page-head">
      {onBack && <button className="icon-button" aria-label="Quay lại" onClick={onBack}><Icon name="back" size={18} /></button>}
      <h1>Lịch sử cuộc họp</h1>
      <button className="icon-button" disabled={busy} onClick={onRefresh} aria-label="Làm mới" title="Làm mới"><Icon name="refresh" size={16} /><span className="sr-only">Làm mới</span></button>
    </div>
    {!history ? <div className="skeleton-list"><div /><div /><div /></div> : <div className="stack">
      {!history.items.length && <div className="empty"><Icon name="history" size={22} /><p>Chưa có meeting. Nhập transcript để bắt đầu.</p></div>}
      {history.items.map(item => <MeetingRow key={item.meetingId} item={item} busy={busy} onOpen={onOpen} />)}
      {history.nextCursor !== null && <button className="full" disabled={busy} onClick={onMore}>Xem thêm cuộc họp</button>}
    </div>}
  </section>;
}
