import React, { useEffect, useRef, useState } from 'react';
import { ApiError, createApi } from './api.mjs';
import { createStorage, SESSION_KEY } from './storage.mjs';
import { assertRevision, openSavedMeeting } from './meeting.mjs';
import { AuthForm, ErrorNotice, History, InputForm, MeetingDetail } from './components.jsx';
import AnalysisPanel from './AnalysisPanel.jsx';
import AudioPanel from './AudioPanel.jsx';
import { isActiveJob } from './jobs.mjs';
import { SELECTION_KEY, belongsToSession } from './selection.mjs';

const origin = __API_ORIGIN__;
const storage = globalThis.chrome?.storage ? createStorage(chrome.storage, origin) : null;

export default function App() {
  const [booting, setBooting] = useState(true);
  const [session, setSession] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState('');
  const [health, setHealth] = useState('Chưa kiểm tra');
  const [view, setView] = useState('new');
  const [meeting, setMeeting] = useState(null);
  const [source, setSource] = useState(null);
  const [history, setHistory] = useState(null);
  const [editing, setEditing] = useState(false);
  const [jobActive, setJobActive] = useState(false);
  const [formKey, setFormKey] = useState(0);
  const [selection, setSelection] = useState(null);
  const [selectionInitial, setSelectionInitial] = useState(null);
  const dirty = useRef(false);
  const reviewDirty = useRef(false);
  const epoch = useRef(0);
  const currentSession = useRef(null);
  const api = useRef(createApi(origin, { onUnauthorized: token => storage?.clearSession(token) })).current;

  function setAuth(value) { currentSession.current = value; setSession(value); }
  function resetDisplay() {
    setMeeting(null); setSource(null); setHistory(null); setEditing(false);
    setJobActive(false);
    setSelectionInitial(null); setView('new'); setFormKey(key => key + 1); dirty.current = false;
  }
  async function run(task, success = () => {}) {
    const generation = epoch.current;
    setBusy(true); setError(null); setNotice('');
    try {
      const result = await task();
      if (generation === epoch.current) await success(result);
    } catch (failure) {
      if (generation === epoch.current) setError(failure);
    } finally { if (generation === epoch.current) { setBusy(false); setBooting(false); } }
  }
  function canLeave() {
    if (reviewDirty.current) return window.confirm('Còn chỉnh sửa task chưa lưu. Rời màn hình có thể mất phần chưa lưu. Tiếp tục?');
    return !dirty.current || window.confirm('Bạn có thay đổi chưa lưu. Bỏ thay đổi và tiếp tục?');
  }
  function applyMeeting(data) {
    setMeeting(data.meeting); setSource(data.source); setView('meeting'); setEditing(false); dirty.current = false;
    setJobActive(isActiveJob(data.meeting.analysisStatus));
  }
  async function restore(value) {
    const generation = epoch.current;
    const id = await storage.active(value.user.id);
    if (id) {
      const data = await openSavedMeeting(api, storage, value, id);
      if (generation === epoch.current) applyMeeting(data);
    }
  }
  useEffect(() => {
    if (!storage) {
      setError(new Error('Hãy Load unpacked thư mục extension/dist trong Chrome rồi mở Side Panel. Trang web thông thường không có Chrome storage.'));
      setBooting(false); return;
    }
    run(async () => {
      await storage.init();
      const saved = await storage.session();
      if (!saved) return null;
      const user = await api.me(saved.accessToken);
      if (user.id !== saved.user.id) { await storage.clearSession(saved.accessToken); throw new Error('Session không khớp tài khoản. Đăng nhập lại.'); }
      return { ...saved, user };
    }, async value => { setAuth(value); if (value) await restore(value); });
    const changed = (changes, area) => {
      if (area !== 'session' || !changes[SESSION_KEY] || !currentSession.current) return;
      const next = changes[SESSION_KEY].newValue;
      if (next?.accessToken === currentSession.current.accessToken) return;
      epoch.current++; setAuth(null); resetDisplay(); setBusy(false); setBooting(false);
      setNotice('Phiên đăng nhập đã thay đổi hoặc hết hạn. Đăng nhập lại để mở meeting đã lưu.');
    };
    chrome.storage.onChanged.addListener(changed);
    return () => { epoch.current++; chrome.storage.onChanged.removeListener(changed); };
  }, []);

  useEffect(() => {
    if (!session) return;
    const timer = setTimeout(() => { storage.clearSession(session.accessToken).catch(setError); }, Math.max(0, Date.parse(session.expiresAt) - Date.now()));
    return () => clearTimeout(timer);
  }, [session]);

  useEffect(() => {
    let mounted = true;
    if (!storage) return;
    const read = async () => {
      const item = (await chrome.storage.session.get(SELECTION_KEY))[SELECTION_KEY];
      if (mounted) setSelection(item?.expiresAt > Date.now() && (item.error || belongsToSession(item, session)) ? item : null);
    };
    const changed = (changes, area) => { if (area === 'session' && changes[SELECTION_KEY]) read().catch(setError); };
    read().catch(setError);
    const timer = setInterval(() => { read().catch(setError); }, 10000);
    chrome.storage.onChanged.addListener(changed);
    return () => { mounted = false; clearInterval(timer); chrome.storage.onChanged.removeListener(changed); };
  }, [session]);
  function importSelection() {
    if (!belongsToSession(selection, session) || busy || !canLeave()) return;
    resetDisplay();
    setSelectionInitial({ title: '', meetingDate: '', timezone: Intl.DateTimeFormat().resolvedOptions().timeZone ?? '', transcriptText: selection.text });
    dirty.current = true;
    setNotice('Đã đưa văn bản vào bản nháp mới. Kiểm tra rồi bấm Lưu khi đã sẵn sàng.');
    dismissSelection();
  }
  function dismissSelection() {
    setSelection(null);
    chrome.storage.session.remove(SELECTION_KEY).catch(setError);
  }

  const token = session?.accessToken;
  function login(credentials, register, clearPassword) {
    run(async () => {
      if (register) await api.register(credentials);
      clearPassword();
      const auth = await api.login(credentials);
      const user = await api.me(auth.accessToken);
      await storage.saveSession(auth, user);
      return { ...auth, user, origin };
    }, async value => { resetDisplay(); setAuth(value); await restore(value); });
  }
  function logout() {
    if (!canLeave()) return;
    run(() => api.logout(token), async () => {
      await storage.clearSession(token);
      await chrome.storage.session.remove(SELECTION_KEY);
      setSelection(null); setAuth(null); resetDisplay();
      setNotice('Đã đăng xuất và thu hồi session. Meeting đã lưu vẫn còn trên backend.');
    });
  }
  function openMeeting(id) {
    if (!canLeave()) return;
    run(() => openSavedMeeting(api, storage, session, id), async data => {
      applyMeeting(data); await storage.setActive(session.user.id, id);
    });
  }
  function showHistory(cursor = null) {
    if (!canLeave()) return;
    dirty.current = false; setEditing(false); setView('history');
    run(() => api.history(token, cursor), data => setHistory(previous => cursor === null ? data : {
      items: [...previous.items, ...data.items.filter(item => !previous.items.some(old => old.meetingId === item.meetingId))], nextCursor: data.nextCursor,
    }));
  }
  function save(payload) {
    const updating = editing;
    run(() => updating ? api.replace(token, meeting.meetingId, payload.input)
      : payload.kind === 'file' ? api.upload(token, payload.file, payload.metadata) : api.create(token, payload.input), async saved => {
      // Adopt the committed meeting before querying sources. A source-read error must not cause a second create.
      setSelectionInitial(null); setMeeting(saved); setSource(null); setView('meeting'); setEditing(false); dirty.current = false;
      setJobActive(false);
      setNotice('Đã lưu nội dung. Bạn có thể bắt đầu phân tích.');
      await storage.setActive(session.user.id, saved.meetingId);
      const page = assertRevision(saved, await api.transcript(token, saved.meetingId));
      if (currentSession.current?.accessToken === token) setSource(page);
    });
  }
  function edit() {
    if (jobActive) { setError(new ApiError('Hủy hoặc chờ job kết thúc trước khi thay input.', { status: 409, code: 'RESOURCE_BUSY' })); return; }
    if (!canLeave()) return;
    setEditing(true); setFormKey(key => key + 1); dirty.current = false;
  }
  // Editing intentionally starts with empty text: loaded segments are not the original complete upload.
  const editInitial = meeting ? { title: meeting.title ?? '', meetingDate: meeting.meetingDate ?? '', timezone: meeting.timezone ?? '', transcriptText: '' } : null;

  return <main>
    <header className="app-header"><div className="brand-mark">M</div><div><h1>Meeting to Task</h1><p>Transcript → preview → công việc</p></div></header>
    {selection && <section className="card">
      <h3>Văn bản từ menu chuột phải</h3>
      {selection.error ? <p className="notice">{selection.error}</p> : <>
        <p className="small muted">{selection.text.length.toLocaleString('vi-VN')} ký tự</p>
        <pre className="transcript">{selection.text.slice(0, 1000)}</pre>
        <button disabled={busy || booting || !session} onClick={importSelection}>Dùng cho meeting mới</button>
      </>}
      <button onClick={dismissSelection}>Bỏ đoạn chọn</button>
    </section>}
    <details className="connection"><summary>Backend · {health}</summary><p className="small break">{origin}</p>
      {globalThis.chrome?.runtime?.id && <p className="small break">Origin CORS: chrome-extension://{chrome.runtime.id}</p>}
      <button disabled={busy || booting} onClick={() => run(() => api.health(), result => {
        setHealth(result.status === 'UP' ? 'Sẵn sàng' : result.status);
      })}>Kiểm tra kết nối</button>
    </details>
    {booting ? <p role="status">Đang khôi phục phiên…</p> : <>
      {session && <><div className="account row"><span className="small break">{session.user.email}</span><button disabled={busy} onClick={logout}>Đăng xuất</button></div>
        <button disabled={busy} onClick={() => { if (canLeave()) { dirty.current=false; setView('audio'); setEditing(false); } }}>Ghi âm / Live</button>
        <nav aria-label="Meeting"><button disabled={busy} aria-current={view === 'new' ? 'page' : undefined} onClick={() => {
          if (!canLeave()) return;
          setSelectionInitial(null); dirty.current = false; setEditing(false); setFormKey(key => key + 1); setError(null); setNotice(''); setView('new');
        }}>Nhập mới</button><button disabled={busy} aria-current={view === 'history' ? 'page' : undefined} onClick={() => showHistory()}>Lịch sử</button>
          {meeting && <button disabled={busy} aria-current={view === 'meeting' ? 'page' : undefined} onClick={() => { if (canLeave()) { dirty.current = false; setEditing(false); setView('meeting'); } }}>Meeting đang mở</button>}</nav></>}
      <ErrorNotice error={error} />
      {error?.status === 409 && meeting && <button disabled={busy} onClick={() => openMeeting(meeting.meetingId)}>Tải phiên bản mới nhất</button>}
      {notice && <p className="notice" role="status">{notice}</p>}
      {busy && <p className="small muted" role="status">Đang gửi request…</p>}
      {!session ? storage && <AuthForm busy={busy} onSubmit={login} /> : <>
        {(view === 'new' || editing) && <InputForm key={formKey} initial={editing ? editInitial : selectionInitial ?? undefined} version={editing ? meeting.inputVersion : undefined}
          busy={busy} onSave={save} onDirty={() => { dirty.current = true; }} onCancel={editing ? () => { if (canLeave()) { setEditing(false); dirty.current = false; } } : undefined} />}
        {view === 'audio' && <AudioPanel key={session.user.id} api={api} session={session} onDirty={value=>{reviewDirty.current=value;}} onOpenMeeting={openMeeting} />}
        {view === 'history' && <History history={history} busy={busy} onRefresh={() => showHistory()} onMore={() => showHistory(history.nextCursor)} onOpen={openMeeting} />}
        {view === 'meeting' && meeting && !editing && <>
          <AnalysisPanel key={`${meeting.meetingId}:${meeting.transcriptRevision}:${meeting.inputVersion}`} api={api} token={token} meeting={meeting} onActivity={setJobActive}
            onReviewDirty={value => { reviewDirty.current = value; }} disabled={busy} onReload={() => openMeeting(meeting.meetingId)} />
          <MeetingDetail meeting={meeting} source={source} busy={busy} inputLocked={jobActive} onEdit={edit} onReload={() => openMeeting(meeting.meetingId)}
            onDeleteTranscript={() => {
              if (!window.confirm('Xóa toàn bộ nội dung transcript và trích dẫn của meeting này? Task và liên kết card được giữ; không hoàn tác được.')) return;
              run(() => api.deleteTranscript(token, meeting.meetingId), () => { setNotice('Đã xóa nội dung transcript.'); openMeeting(meeting.meetingId); });
            }}
            onDeleteMeeting={() => {
              if (!window.confirm('Xóa meeting, transcript và task nội bộ? Card đã tạo trên Trello không bị xóa. Không hoàn tác được.')) return;
              run(() => api.deleteMeeting(token, meeting.meetingId), async () => {
                await storage.setActive(session.user.id, null); resetDisplay(); setNotice('Đã xóa meeting. Card trên Trello (nếu có) vẫn giữ nguyên.');
              });
            }}
            onMore={() => run(async () => {
          const page = assertRevision(meeting, await api.transcript(token, meeting.meetingId, source.nextCursor));
          return { ...page, segments: [...source.segments, ...page.segments] };
        }, setSource)} /></>}
      </>}
    </>}
    <footer>Meeting to Task · Bản 0.7</footer>
  </main>;
}
