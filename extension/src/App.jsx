import React, { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, createApi } from './api.mjs';
import { createStorage, SESSION_KEY } from './storage.mjs';
import { assertRevision, openSavedMeeting } from './meeting.mjs';
import { AuthForm, ErrorNotice, History, InputForm, MeetingDetail, MeetingRow, Sheet } from './components.jsx';
import AnalysisPanel from './AnalysisPanel.jsx';
import AudioPanel from './AudioPanel.jsx';
import { TrelloConnection } from './TrelloPanel.jsx';
import { isActiveJob } from './jobs.mjs';
import { SELECTION_KEY, belongsToSession } from './selection.mjs';
import Icon from './icons.jsx';

const origin = __API_ORIGIN__;
const storage = globalThis.chrome?.storage ? createStorage(chrome.storage, origin) : null;
const PREF_PROVIDER = 'aiMttPreferredProvider';
const STEPS = [['input', 'Nhập', 'input'], ['analyze', 'Phân tích', 'brain'], ['review', 'Duyệt', 'list'], ['cards', 'Tạo card', 'board']];

function Stepper({ page, done, onGo }) {
  const index = STEPS.findIndex(([key]) => key === page);
  return <ol className="stepper">{STEPS.map(([key, label], i) => {
    const state = i === index ? 'active' : done[key] || i < index ? 'done' : 'todo';
    return <li key={key} className={state}>
      <button type="button" disabled={key === 'input' || !onGo} onClick={() => onGo(key)}>
        {state === 'done' ? <Icon name="checkCircle" size={14} /> : <span className="step-dot" />}{label}</button>
    </li>;
  })}</ol>;
}

function BottomNav({ current, hasMeeting, onGo }) {
  return <nav className="bottom-nav" aria-label="Các bước">
    {STEPS.map(([key, label, icon]) => <button key={key} type="button" aria-current={current === key ? 'page' : undefined}
      disabled={key !== 'input' && !hasMeeting} onClick={() => onGo(key)}><Icon name={icon} size={20} /><span>{label}</span></button>)}
  </nav>;
}

export default function App() {
  const [booting, setBooting] = useState(true);
  const [session, setSession] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState('');
  const [health, setHealth] = useState('Chưa kiểm tra');
  const [view, setView] = useState('new');
  const [page, setPage] = useState('analyze');
  const [source_tab, setSourceTab] = useState('paste');
  const [meeting, setMeeting] = useState(null);
  const [source, setSource] = useState(null);
  const [history, setHistory] = useState(null);
  const [editing, setEditing] = useState(false);
  const [jobActive, setJobActive] = useState(false);
  const [formKey, setFormKey] = useState(0);
  const [selection, setSelection] = useState(null);
  const [selectionInitial, setSelectionInitial] = useState(null);
  const [autoStart, setAutoStart] = useState(false);
  const [done, setDone] = useState({});
  const [detailOpen, setDetailOpen] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [preferred, setPreferred] = useState('');
  const [settingsData, setSettingsData] = useState(null);
  const dirty = useRef(false);
  const reviewDirty = useRef(false);
  const epoch = useRef(0);
  const currentSession = useRef(null);
  const api = useRef(createApi(origin, { onUnauthorized: token => storage?.clearSession(token) })).current;

  function setAuth(value) { currentSession.current = value; setSession(value); }
  function resetDisplay() {
    setMeeting(null); setSource(null); setHistory(null); setEditing(false);
    setJobActive(false); setDetailOpen(false); setMenuOpen(false); setDone({});
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
    setDone({ analyze: data.meeting.analysisStatus === 'COMPLETED', review: Boolean(data.meeting.latestSyncJobId) });
    setPage(data.meeting.analysisStatus === 'COMPLETED' ? 'review' : 'analyze');
  }
  async function restore(value) {
    const generation = epoch.current;
    const id = await storage.active(value.user.id);
    if (id) {
      const data = await openSavedMeeting(api, storage, value, id);
      if (generation === epoch.current) applyMeeting(data);
    }
  }
  function checkHealth() {
    api.health().then(result => setHealth(result.status === 'UP' ? 'Sẵn sàng' : result.status), () => setHealth('Không kết nối được'));
  }
  useEffect(() => {
    if (!storage) {
      setError(new Error('Hãy Load unpacked thư mục extension/dist trong Chrome rồi mở Side Panel. Trang web thông thường không có Chrome storage.'));
      setBooting(false); return;
    }
    checkHealth();
    chrome.storage.local.get(PREF_PROVIDER).then(value => setPreferred(value[PREF_PROVIDER] ?? '')).catch(() => {});
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
    resetDisplay(); setSourceTab('paste');
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
    setDetailOpen(false); setMenuOpen(false);
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
  function goNew() {
    if (!canLeave()) return;
    setSelectionInitial(null); dirty.current = false; setEditing(false); setFormKey(key => key + 1); setError(null); setNotice(''); setView('new');
  }
  function goSettings() {
    if (view !== 'settings' && !canLeave()) return;
    dirty.current = false; setEditing(false); setView('settings'); setSettingsData(null);
    run(async () => {
      const [config, connections, policies] = await Promise.all([api.trelloConfig(token), api.trelloConnections(token), api.analysisPolicies(token)]);
      return { config, connection: connections[0] ?? null, policies: policies.filter(item => ['openai', 'gemini'].includes(item.providerId)) };
    }, setSettingsData);
  }
  function goStep(key) {
    if (key === 'input') { goNew(); return; }
    if (!meeting) return;
    if (view !== 'meeting') { if (!canLeave()) return; dirty.current = false; setView('meeting'); setEditing(false); }
    setPage(key);
  }
  function choosePreferred(value) {
    setPreferred(value);
    chrome.storage.local.set({ [PREF_PROVIDER]: value }).catch(setError);
  }
  // Recent meetings on the input screen.
  useEffect(() => {
    if (!session || view !== 'new') return;
    let live = true;
    api.history(session.accessToken, null).then(data => { if (live) setHistory(data); }, () => {});
    return () => { live = false; };
  }, [session, view]);

  function save(payload) {
    const updating = editing;
    run(() => updating ? api.replace(token, meeting.meetingId, payload.input)
      : payload.kind === 'file' ? api.upload(token, payload.file, payload.metadata) : api.create(token, payload.input), async saved => {
      // Adopt the committed meeting before querying sources. A source-read error must not cause a second create.
      setSelectionInitial(null); setMeeting(saved); setSource(null); setView('meeting'); setEditing(false); dirty.current = false;
      setJobActive(false); setPage('analyze'); setDone({}); setAutoStart(!updating);
      setNotice(updating ? 'Đã lưu nội dung mới. Bạn có thể phân tích lại.' : 'Đã lưu nội dung. AI sẽ bắt đầu phân tích nếu đã chọn dịch vụ mặc định.');
      await storage.setActive(session.user.id, saved.meetingId);
      const page = assertRevision(saved, await api.transcript(token, saved.meetingId));
      if (currentSession.current?.accessToken === token) setSource(page);
    });
  }
  function edit() {
    if (jobActive) { setError(new ApiError('Hủy hoặc chờ job kết thúc trước khi thay input.', { status: 409, code: 'RESOURCE_BUSY' })); return; }
    if (!canLeave()) return;
    setDetailOpen(false); setMenuOpen(false);
    setEditing(true); setFormKey(key => key + 1); dirty.current = false;
  }
  const onActivity = useCallback(value => setJobActive(value), []);
  // Editing intentionally starts with empty text: loaded segments are not the original complete upload.
  const editInitial = meeting ? { title: meeting.title ?? '', meetingDate: meeting.meetingDate ?? '', timezone: meeting.timezone ?? '', transcriptText: '' } : null;
  const tabs = <div className="segmented tabs" role="group" aria-label="Cách nhập">
    {[['paste', 'Dán', 'paste'], ['file', 'Tải file', 'upload'], ['audio', 'Ghi âm', 'mic']].map(([key, label, icon]) =>
      <button type="button" key={key} aria-pressed={source_tab === key} onClick={() => { setSourceTab(key); setError(null); }}><Icon name={icon} size={15} />{label}</button>)}
  </div>;
  const navCurrent = view === 'new' ? 'input' : view === 'meeting' && !editing ? page : null;
  const initial = (session?.user.email ?? '?')[0].toUpperCase();

  const meetingDetail = meeting && <MeetingDetail meeting={meeting} source={source} busy={busy} inputLocked={jobActive} onEdit={edit} onReload={() => openMeeting(meeting.meetingId)}
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
    }, setSource)} />;

  const meetingHeader = meeting && <div className="meeting-head">
    <div className="row-main"><h1>{meeting.title || 'Cuộc họp chưa đặt tên'}</h1>
      <p className="small muted"><Icon name="calendar" size={13} /> {meeting.meetingDate || 'Chưa có ngày họp'} · v{meeting.inputVersion}</p></div>
    <div className="menu-wrap">
      <button className="icon-button soft" aria-label="Thêm thao tác" aria-expanded={menuOpen} onClick={() => setMenuOpen(!menuOpen)}><Icon name="more" size={18} /></button>
      {menuOpen && <div className="menu" role="menu" onClick={() => setMenuOpen(false)}>
        <button role="menuitem" onClick={() => setDetailOpen(true)}><Icon name="file" size={15} />Xem nội dung gốc</button>
        <button role="menuitem" disabled={busy || jobActive} onClick={edit}><Icon name="edit" size={15} />Thay nội dung</button>
        <button role="menuitem" disabled={busy} onClick={() => openMeeting(meeting.meetingId)}><Icon name="refresh" size={15} />Tải lại</button>
        <button role="menuitem" className="danger-text" onClick={() => setDetailOpen(true)}><Icon name="trash" size={15} />Xoá dữ liệu…</button>
      </div>}
    </div>
  </div>;

  return <div className={`app${session ? ' with-nav' : ''}`}>
    <header className="top-bar">
      <div className="brand"><div className="brand-mark">M</div><span>Meeting to Task</span></div>
      {session && <div className="top-actions">
        <button className="icon-button" aria-label="Cài đặt" aria-current={view === 'settings' ? 'page' : undefined} onClick={goSettings}><Icon name="settings" size={19} /></button>
        <span className="avatar tone-0 lg" title={session.user.email}>{initial}</span>
      </div>}
    </header>
    {busy && <div className="busy-line" role="status"><span className="sr-only">Đang gửi request…</span></div>}
    <main>
      {selection && <div className="selection-banner">
        <Icon name="sparkle" size={16} />
        {selection.error ? <span className="small">{selection.error}</span> : <span className="small grow">Đã lấy {selection.text.length.toLocaleString('vi-VN')} ký tự từ trang bạn chọn</span>}
        {!selection.error && <button className="chip suggest" disabled={busy || booting || !session} onClick={importSelection}>Dùng</button>}
        <button className="icon-button" aria-label="Bỏ đoạn chọn" onClick={dismissSelection}><Icon name="close" size={14} /></button>
      </div>}
      {view === 'meeting' && meeting && !editing && <Stepper page={page} done={{ input: true, ...done }} onGo={goStep} />}
      {view === 'new' && session && <Stepper page="input" done={{}} />}
      <ErrorNotice error={error} />
      {error?.status === 409 && meeting && <button disabled={busy} onClick={() => openMeeting(meeting.meetingId)}>Tải phiên bản mới nhất</button>}
      {notice && <p className="notice toast" role="status">{notice}</p>}

      {booting ? <div className="skeleton-list"><div /><div /><div /></div> : !session ? storage && <AuthForm busy={busy} onSubmit={login} health={
        <p className="server-status"><span className={`status-dot ${health === 'Sẵn sàng' ? 'ok ping' : health === 'Chưa kiểm tra' ? '' : 'bad'}`} />Máy chủ: {health === 'Sẵn sàng' ? 'đã kết nối' : health.toLowerCase()}</p>} /> : <>
        {view === 'new' && <div className="stack">
          <div className="page-head"><h1>Cuộc họp mới</h1><span className="pill ai">Trợ lý AI</span></div>
          {source_tab === 'audio' ? <AudioPanel key={session.user.id} api={api} session={session} tabs={tabs} onDirty={value => { reviewDirty.current = value; }} onOpenMeeting={openMeeting} />
            : <InputForm key={formKey} mode={source_tab} tabs={tabs} initial={selectionInitial ?? undefined} busy={busy} onSave={save} onDirty={() => { dirty.current = true; }} />}
          {source_tab !== 'audio' && <section className="recent">
            <div className="section-label"><span><Icon name="history" size={15} /> Gần đây</span>
              <button className="link-button" onClick={() => showHistory()}>Xem tất cả<Icon name="chevronRight" size={14} /></button></div>
            {!history ? <div className="skeleton-list"><div /><div /></div> : history.items.length ? <div className="stack tight">
              {history.items.slice(0, 3).map(item => <MeetingRow key={item.meetingId} item={item} busy={busy} onOpen={openMeeting} />)}</div>
              : <p className="small muted">Chưa có meeting. Nhập transcript để bắt đầu.</p>}
          </section>}
        </div>}

        {view === 'history' && <History history={history} busy={busy} onBack={goNew} onRefresh={() => showHistory()} onMore={() => showHistory(history.nextCursor)} onOpen={openMeeting} />}

        {view === 'meeting' && meeting && editing && <div className="stack">
          <div className="page-head"><button className="icon-button" aria-label="Quay lại" onClick={() => { if (canLeave()) { setEditing(false); dirty.current = false; } }}><Icon name="back" size={18} /></button><h1>Thay nội dung</h1></div>
          <InputForm key={formKey} initial={editInitial} version={meeting.inputVersion} busy={busy} onSave={save} onDirty={() => { dirty.current = true; }}
            onCancel={() => { if (canLeave()) { setEditing(false); dirty.current = false; } }} />
        </div>}
        {view === 'meeting' && meeting && !editing && <>
          {meetingHeader}
          <AnalysisPanel key={`${meeting.meetingId}:${meeting.transcriptRevision}:${meeting.inputVersion}`} api={api} token={token} meeting={meeting} onActivity={onActivity}
            onReviewDirty={value => { reviewDirty.current = value; }} disabled={busy} onReload={() => openMeeting(meeting.meetingId)}
            page={page} preferredProvider={preferred} autoStart={autoStart} onAutoStartDone={() => setAutoStart(false)}
            onCompleted={() => { setDone(d => ({ ...d, analyze: true })); setPage('review'); }} onGoReview={() => setPage('review')}
            reviewProps={{ onSyncStarted: () => { setDone(d => ({ ...d, analyze: true, review: true })); setPage('cards'); }, onNewMeeting: goNew, onGoReview: () => setPage('review') }} />
          {detailOpen && <Sheet title="Nội dung gốc" onClose={() => setDetailOpen(false)}>{meetingDetail}</Sheet>}
        </>}

        {view === 'settings' && <div className="stack settings">
          <div className="page-head"><button className="icon-button" aria-label="Quay lại" onClick={() => meeting ? setView('meeting') : setView('new')}><Icon name="back" size={18} /></button><h1>Cài đặt</h1></div>
          <p className="group-label">TÀI KHOẢN</p>
          <section className="card list-card">
            <div className="setting-row"><span className="avatar tone-0 lg">{initial}</span><span className="row-main small break">{session.user.email}</span></div>
            <button className="setting-row danger-text" disabled={busy} onClick={logout}><Icon name="logout" size={16} /><span className="row-main">Đăng xuất</span></button>
          </section>
          <p className="group-label">TRELLO</p>
          <section className="card">
            {!settingsData ? <p className="small muted">Đang tải…</p> : <TrelloConnection api={api} token={token} config={settingsData.config} connection={settingsData.connection}
              disabled={busy} onChanged={goSettings} />}
            <p className="tiny muted">Board và List được chọn riêng cho từng cuộc họp ở bước Duyệt.</p>
          </section>
          <p className="group-label">AI MẶC ĐỊNH</p>
          <section className="card">
            <div className="segmented" role="group" aria-label="Dịch vụ phân tích mặc định">
              {(settingsData?.policies ?? [{ providerId: 'gemini', displayName: 'Gemini' }, { providerId: 'openai', displayName: 'OpenAI' }]).map(policy =>
                <button type="button" key={policy.providerId} aria-pressed={preferred === policy.providerId} onClick={() => choosePreferred(policy.providerId)}>
                  {policy.displayName ?? policy.providerId}{policy.providerReady === false ? ' (chưa kết nối)' : ''}</button>)}
            </div>
            <p className="tiny muted">Dùng khi bấm “Lưu & phân tích bằng AI”. Bạn vẫn đổi được dịch vụ ở bước Phân tích.</p>
          </section>
          <p className="group-label">DỮ LIỆU</p>
          <section className="card list-card">
            <button className="setting-row" onClick={() => showHistory()}><Icon name="history" size={16} /><span className="row-main">Lịch sử cuộc họp</span><Icon name="chevronRight" size={16} /></button>
          </section>
          <details className="card advanced">
            <summary>Nâng cao</summary>
            <div className="setting-row"><span className={`status-dot ${health === 'Sẵn sàng' ? 'ok' : health === 'Chưa kiểm tra' ? '' : 'bad'}`} /><span className="row-main small">Backend · {health}</span>
              <button className="link-button" onClick={checkHealth}>Kiểm tra kết nối</button></div>
            <p className="tiny muted break mono">{origin}</p>
            {globalThis.chrome?.runtime?.id && <p className="tiny muted break mono">Origin CORS: chrome-extension://{chrome.runtime.id}</p>}
          </details>
          <p className="center tiny muted">Meeting to Task · Bản 0.7</p>
        </div>}
      </>}
    </main>
    {session && !booting && <BottomNav current={navCurrent} hasMeeting={Boolean(meeting)} onGo={goStep} />}
  </div>;
}
