import React, { useState } from 'react';
import { MAX_TEXT, pastePayload, validateFile, validateCredentials } from './input.mjs';

export function ErrorNotice({ error }) {
  if (!error) return null;
  return <div className="notice error" role="alert">
    <strong>{error.message}</strong>
    {error.code && <div className="small">{error.code}{error.traceId && ` · Trace: ${error.traceId}`}</div>}
    {error.retryAfter && <div>Chờ {error.retryAfter} giây trước khi thử lại.</div>}
    {error.details?.map((item, index) => <div key={index}>{item.field}: {item.message}</div>)}
  </div>;
}

export function AuthForm({ busy, onSubmit }) {
  const [register, setRegister] = useState(false);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState(null);
  return <section className="card">
    <p className="eyebrow">BẮT ĐẦU</p><h2>{register ? 'Tạo tài khoản' : 'Đăng nhập'}</h2>
    <p className="muted">Meeting đã lưu sẽ được khôi phục khi bạn đăng nhập cùng tài khoản.</p>
    <form onSubmit={(event) => {
      event.preventDefault(); setError(null);
      try { onSubmit(validateCredentials(email, password), register, () => setPassword('')); }
      catch (failure) { setError(failure); }
    }}>
      <fieldset disabled={busy}>
        <label>Email<input type="email" maxLength={254} autoComplete="username" required value={email} onChange={e => setEmail(e.target.value)} /></label>
        <label>Mật khẩu<input type="password" maxLength={72} minLength={10} autoComplete={register ? 'new-password' : 'current-password'} required value={password} onChange={e => setPassword(e.target.value)} /></label>
        <p className="small muted">10–72 ký tự, tối đa 72 byte UTF-8.</p>
        <ErrorNotice error={error} />
        <button className="primary full" type="submit">{busy ? 'Đang kết nối…' : register ? 'Đăng ký và đăng nhập' : 'Đăng nhập'}</button>
        <button className="text full" type="button" onClick={() => { setRegister(!register); setError(null); }}>{register ? 'Đã có tài khoản? Đăng nhập' : 'Chưa có tài khoản? Đăng ký'}</button>
      </fieldset>
    </form>
  </section>;
}

export function InputForm({ initial, version, busy, onSave, onDirty, onCancel }) {
  const [mode, setMode] = useState('paste');
  const [draft, setDraft] = useState(initial ?? {
    title: '', meetingDate: '', timezone: Intl.DateTimeFormat().resolvedOptions().timeZone ?? '', transcriptText: '',
  });
  const [file, setFile] = useState(null);
  const [error, setError] = useState(null);
  const editing = version !== undefined;
  const update = (key, value) => { setDraft(current => ({ ...current, [key]: value })); onDirty(); };
  return <section className="card">
    <p className="eyebrow">{editing ? `SỬA INPUT · VERSION ${version}` : 'TRANSCRIPT'}</p>
    <h2>{editing ? 'Thay input đã lưu' : 'Nhập nội dung cuộc họp'}</h2>
    {editing && <p className="notice">Lưu sẽ tạo revision mới từ văn bản bạn nhập. Nguồn TXT/DOCX cũ vẫn thuộc revision trước. Tải lại nếu gặp version conflict.</p>}
    <form onSubmit={event => {
      event.preventDefault(); setError(null);
      try {
        if (mode === 'paste') onSave({ kind: 'paste', input: pastePayload(draft, version) });
        else { validateFile(file); onSave({ kind: 'file', file, metadata: { title: draft.title.trim(), meetingDate: draft.meetingDate, timezone: draft.timezone.trim() } }); }
      } catch (failure) { setError(failure); }
    }}>
      <fieldset disabled={busy}>
        <label>Tiêu đề <span className="optional">(không bắt buộc)</span><input maxLength={255} value={draft.title} onChange={e => update('title', e.target.value)} placeholder="Ví dụ: Sprint planning" /></label>
        <div className="columns">
          <label>Ngày họp<input type="date" value={draft.meetingDate} onChange={e => update('meetingDate', e.target.value)} /></label>
          <label>Múi giờ<input maxLength={64} value={draft.timezone} onChange={e => update('timezone', e.target.value)} placeholder="Asia/Ho_Chi_Minh" /></label>
        </div>
        <p className="small muted">Ngày họp để trống nếu chưa biết. Kiểm tra lại múi giờ gợi ý từ trình duyệt.</p>
        {!editing && <div className="switch" role="group" aria-label="Cách nhập">
          <button type="button" aria-pressed={mode === 'paste'} onClick={() => { setMode('paste'); setError(null); }}>Dán văn bản</button>
          <button type="button" aria-pressed={mode === 'file'} onClick={() => { setMode('file'); setError(null); }}>TXT / DOCX</button>
        </div>}
        {mode === 'paste' ? <>
          <label>Nội dung<textarea rows={10} value={draft.transcriptText} onChange={e => update('transcriptText', e.target.value)} placeholder="Dán transcript, giữ nhãn người nói và timestamp nếu có…" required /></label>
          <p className={`small ${draft.transcriptText.length > MAX_TEXT ? 'danger' : 'muted'}`}>{draft.transcriptText.length.toLocaleString('vi-VN')} / 200.000 ký tự UTF-16</p>
        </> : <>
          <label className="upload">Chọn một file<input type="file" accept=".txt,.docx" onChange={e => { setFile(e.target.files?.[0] ?? null); onDirty(); }} required /></label>
          <p className="small muted">Tối đa 10 MiB. TXT dùng UTF-8; DOCX chỉ đọc nội dung body. Backend kiểm tra và chuẩn hóa văn bản.</p>
        </>}
        <p className="small muted">Bấm lưu để gửi transcript tới backend và xem preview. Nội dung chưa lưu sẽ mất khi đóng panel.</p>
        <ErrorNotice error={error} />
        <div className="actions"><button className="primary" type="submit">{busy ? 'Đang lưu…' : editing ? 'Lưu revision mới' : 'Lưu & xem preview'}</button>
          {onCancel && <button type="button" onClick={onCancel}>Hủy sửa</button>}</div>
      </fieldset>
    </form>
  </section>;
}

export function MeetingDetail({ meeting, source, busy, inputLocked, onMore, onReload, onEdit, onDeleteTranscript, onDeleteMeeting }) {
  return <>
    <section className="card">
      <p className="eyebrow">ĐÃ LƯU · VERSION {meeting.inputVersion}</p>
      <h2>{meeting.title || 'Cuộc họp chưa đặt tên'}</h2>
      <p className="muted">{meeting.meetingDate || 'Chưa có ngày họp'} · {meeting.timezone || 'Chưa có múi giờ'}</p>
      <div className="stats"><div><strong>{meeting.characterCount.toLocaleString('vi-VN')}</strong><span>ký tự UTF-16</span></div><div><strong>{meeting.segmentCount}</strong><span>segments</span></div></div>
      <h3>Preview văn bản chuẩn hóa</h3><pre className="transcript">{meeting.preview}</pre>
      {meeting.characterCount > meeting.preview.length && <p className="small muted">Preview tối đa 4.000 ký tự. Xem segments bên dưới để đọc tiếp.</p>}
      {meeting.warnings.map((warning, index) => <p className="notice" key={index}>{warning === 'DOCX_NON_BODY_CONTENT_OMITTED' ? 'Header, footer hoặc comment của DOCX không được nhập vào transcript.' : warning}</p>)}
      <div className="actions"><button disabled={busy} onClick={onReload}>Tải lại</button><button disabled={busy || inputLocked} onClick={onEdit}>Thay input</button></div>
      <p className="small muted break">Meeting ID: {meeting.meetingId}<br />Nguồn dự kiến hết hạn: {new Date(meeting.sourceExpiresAt).toLocaleString('vi-VN')}</p>
    </section>
    <section className="card">
      <h3>Nội dung & vị trí nguồn</h3>
      {!source ? <p className="muted">Chưa tải nguồn. Bấm Tải lại.</p> : !source.sourceAvailable ? <p className="notice">Nguồn hiện không còn khả dụng.</p> : <>
        {source.segments.map(segment => <details key={segment.segmentId} className="segment">
          <summary><span className="small muted">#{segment.sequence + 1}{segment.timestamp && ` · ${segment.timestamp}`}</span><p>{segment.text}</p></summary>
          <h4>Văn bản nguồn</h4><pre className="transcript">{segment.sourceText}</pre>
          <pre className="small locator">{JSON.stringify(segment.sourceLocator, null, 2)}</pre>
          <p className="small muted">Vị trí chuẩn hóa: [{segment.normalizedStart}, {segment.normalizedEnd}) UTF-16</p>
        </details>)}
        <p className="small muted">Đã tải {source.segments.length} / {meeting.segmentCount} segments.</p>
        {source.nextCursor !== null && <button className="full" disabled={busy} onClick={onMore}>Xem thêm nội dung</button>}
      </>}
    </section>
    {(onDeleteTranscript || onDeleteMeeting) && <section className="card privacy">
      <p className="eyebrow">DỮ LIỆU</p>
      <p className="small muted">Nội dung nguồn tự xóa sau {new Date(meeting.sourceExpiresAt).toLocaleDateString('vi-VN')}. Task đã duyệt và liên kết card được giữ để không tạo trùng; xóa trong hệ thống không xóa card trên Trello.</p>
      <div className="actions">
        {onDeleteTranscript && meeting.sourceAvailable !== false && <button className="danger-button" disabled={busy} onClick={onDeleteTranscript}>Xóa nội dung transcript</button>}
        {onDeleteMeeting && <button className="danger-button" disabled={busy} onClick={onDeleteMeeting}>Xóa meeting</button>}
      </div>
    </section>}
  </>;
}

const historyStatus = { NOT_STARTED: 'Chưa phân tích', QUEUED: 'Đang chờ phân tích', PROCESSING: 'Đang phân tích', CANCEL_REQUESTED: 'Đang hủy',
  COMPLETED: 'Đã phân tích', PARTIAL_FAILED: 'Phân tích chưa đủ', FAILED: 'Phân tích lỗi', CANCELLED: 'Đã hủy phân tích' };

export function History({ history, busy, onRefresh, onMore, onOpen }) {
  return <section className="card">
    <div className="row"><h2>Lịch sử cuộc họp</h2><button disabled={busy} onClick={onRefresh}>Làm mới</button></div>
    {!history ? <p className="muted">Đang tải…</p> : <>
      {!history.items.length && <p className="muted">Chưa có meeting. Nhập transcript để bắt đầu.</p>}
      {history.items.map(item => <button className="history-item full" disabled={busy} key={item.meetingId} onClick={() => onOpen(item.meetingId)}>
        <strong>{item.title || 'Cuộc họp chưa đặt tên'}</strong>
        <span className="small muted">{item.meetingDate || 'Chưa có ngày họp'} · v{item.inputVersion}</span>
        {item.analysisStatus && <span className="small">{historyStatus[item.analysisStatus] ?? item.analysisStatus}
          {item.pendingTasks > 0 && ` · ${item.pendingTasks} task chờ duyệt`}{item.rejectedTasks > 0 && ` · ${item.rejectedTasks} đã loại`}</span>}
        <span className="small muted">Lưu {new Date(item.createdAt).toLocaleString('vi-VN')}</span>
      </button>)}
      {history.nextCursor !== null && <button className="full" disabled={busy} onClick={onMore}>Xem thêm cuộc họp</button>}
    </>}
  </section>;
}
