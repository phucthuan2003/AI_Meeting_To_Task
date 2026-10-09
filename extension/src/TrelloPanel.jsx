import React, { useEffect, useRef, useState } from 'react';
import { ErrorNotice } from './components.jsx';

const AUTH_LABELS = { OAUTH2: 'OAuth 2.0', TOKEN: 'API key + token' };

function openExternal(url) {
  // Opening a tab needs no "tabs" permission; fall back to window.open outside Chrome.
  if (globalThis.chrome?.tabs?.create) chrome.tabs.create({ url }); else window.open(url, '_blank', 'noopener');
}

/** Connection status and connect/disconnect (SDS §6.6). Tokens stay on the backend. */
export function TrelloConnection({ api, token, config, connection, onChanged, disabled }) {
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [pending, setPending] = useState(null);
  const [trelloToken, setTrelloToken] = useState('');
  const poll = useRef(null);
  useEffect(() => () => clearTimeout(poll.current), []);
  async function run(task) {
    setBusy(true); setError(null);
    try { await task(); } catch (failure) { setError(failure); } finally { setBusy(false); }
  }
  function watch(tx) {
    clearTimeout(poll.current);
    poll.current = setTimeout(async () => {
      try {
        const state = await api.trelloTransaction(token, tx.transactionId);
        if (state.status === 'PENDING') { watch(tx); return; }
        setPending(null);
        if (state.status === 'COMPLETED') onChanged();
        else setError(new Error(state.status === 'DENIED' ? 'Bạn đã từ chối cấp quyền Trello.' : 'Kết nối Trello chưa hoàn tất hoặc đã hết hạn. Thử lại.'));
      } catch (failure) { setPending(null); setError(failure); }
    }, 2000);
  }
  if (!config) return <p className="small muted">Đang đọc cấu hình Trello…</p>;
  const locked = disabled || busy;
  return <div className="trello-connection">
    {connection ? <>
      <p className="small">Đã kết nối: <strong>{connection.fullName || connection.username || connection.trelloMemberId}</strong>
        {connection.username && <span className="muted"> @{connection.username}</span>} · {AUTH_LABELS[connection.authType] ?? connection.authType}</p>
      {connection.status !== 'ACTIVE' && <p className="notice error">Kết nối Trello đã hết quyền hoặc bị thu hồi. Kết nối lại để tạo card; bản nháp vẫn được giữ.</p>}
    </> : <p className="small muted">Chưa kết nối Trello. Bạn vẫn review được; cần kết nối để tạo card.</p>}
    {(!connection || connection.status !== 'ACTIVE') && <>
      {!config.encryptionReady && <p className="notice">Backend chưa cấu hình TOKEN_ENCRYPTION_KEY nên chưa thể lưu kết nối Trello.</p>}
      {config.oauthAvailable && <button className="primary" disabled={locked || pending} onClick={() => run(async () => {
        const tx = await api.trelloAuthorize(token);
        openExternal(tx.authorizationUrl); setPending(tx); watch(tx);
      })}>{pending ? 'Đang chờ bạn cấp quyền trên Trello…' : 'Kết nối Trello (OAuth)'}</button>}
      {config.tokenModeAvailable && <form className="token-form" onSubmit={event => {
        event.preventDefault();
        const value = trelloToken.trim(); setTrelloToken('');
        run(async () => { await api.trelloConnectToken(token, value); onChanged(); });
      }}>
        <p className="small muted">Chế độ API key + token (dùng cho Board thử nghiệm): <a href={config.tokenAuthorizeUrl} target="_blank" rel="noopener noreferrer">mở trang cấp token Trello</a>, bấm Allow rồi dán token.</p>
        <label>Token Trello<input type="password" autoComplete="off" spellCheck={false} value={trelloToken} onChange={e => setTrelloToken(e.target.value)} placeholder="Dán token…" /></label>
        <button disabled={locked || trelloToken.trim().length < 20} type="submit">Kết nối bằng token</button>
      </form>}
      {!config.oauthAvailable && !config.tokenModeAvailable && config.encryptionReady && <p className="notice">Backend chưa cấu hình OAuth (TRELLO_CLIENT_ID/SECRET/CALLBACK_URL) hoặc TRELLO_API_KEY.</p>}
    </>}
    {connection && <button className="text" disabled={locked} onClick={() => {
      if (!window.confirm('Ngắt kết nối Trello? Token lưu ở backend sẽ bị xóa; card đã tạo trên Trello không bị ảnh hưởng.')) return;
      run(async () => { await api.trelloDisconnect(token, connection.connectionId); onChanged(); });
    }}>Ngắt kết nối</button>}
    <ErrorNotice error={error} />
  </div>;
}

/** Board/List selection with destinationVersion (SDS §5.11, §6.7). */
export function DestinationPicker({ api, token, meeting, connection, destination, onSaved, disabled, locked }) {
  const [boards, setBoards] = useState(null);
  const [lists, setLists] = useState([]);
  const [boardId, setBoardId] = useState(destination?.boardId ?? '');
  const [listId, setListId] = useState(destination?.listId ?? '');
  const [editing, setEditing] = useState(!destination);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => { setEditing(!destination); setBoardId(destination?.boardId ?? ''); setListId(destination?.listId ?? ''); }, [destination?.version, destination?.boardId]);
  useEffect(() => {
    if (!editing || !connection || connection.status !== 'ACTIVE') return;
    let live = true;
    api.trelloBoards(token, connection.connectionId).then(value => { if (live) setBoards(value); }, failure => { if (live) setError(failure); });
    return () => { live = false; };
  }, [editing, connection?.connectionId, connection?.status]);
  useEffect(() => {
    if (!editing || !boardId || !connection) { setLists([]); return; }
    let live = true;
    api.trelloLists(token, connection.connectionId, boardId).then(value => { if (live) { setLists(value); if (!value.some(l => l.id === listId)) setListId(''); } },
      failure => { if (live) setError(failure); });
    return () => { live = false; };
  }, [editing, boardId, connection?.connectionId]);
  if (!connection || connection.status !== 'ACTIVE') return destination ? <p className="small">Đích: <strong>{destination.boardName} › {destination.listName}</strong></p> : null;
  if (!editing && destination) return <div className="destination">
    <p className="small">Nơi tạo card: <strong>{destination.boardName} › {destination.listName}</strong> <span className="muted">· phiên bản đích {destination.version}</span></p>
    <button className="text" disabled={disabled || locked} onClick={() => setEditing(true)}>Đổi Board/List</button>
    {locked && <p className="small muted">Không đổi đích khi đang có card chờ tạo hoặc chờ đối soát.</p>}
  </div>;
  return <form className="destination" onSubmit={async event => {
    event.preventDefault(); setBusy(true); setError(null);
    try {
      const saved = await api.saveDestination(token, meeting.meetingId, { connectionId: connection.connectionId, boardId, listId, expectedVersion: destination?.version ?? 0 });
      setEditing(false); onSaved(saved);
    } catch (failure) { setError(failure); } finally { setBusy(false); }
  }}>
    <fieldset disabled={disabled || busy}>
      <label>Board<select value={boardId} onChange={e => setBoardId(e.target.value)} required>
        <option value="">{boards ? 'Chọn Board' : 'Đang tải Board…'}</option>
        {(boards ?? []).map(board => <option key={board.id} value={board.id}>{board.name}</option>)}
      </select></label>
      <label>List<select value={listId} onChange={e => setListId(e.target.value)} required disabled={!boardId}>
        <option value="">Chọn List</option>{lists.map(list => <option key={list.id} value={list.id}>{list.name}</option>)}
      </select></label>
      {destination && <p className="small muted">Đổi Board sẽ yêu cầu đối chiếu lại người phụ trách của các task chưa tạo card.</p>}
      <div className="actions"><button className="primary" type="submit" disabled={!boardId || !listId}>{busy ? 'Đang kiểm tra…' : 'Lưu nơi tạo card'}</button>
        {destination && <button type="button" onClick={() => setEditing(false)}>Hủy</button>}</div>
    </fieldset>
    <ErrorNotice error={error} />
  </form>;
}
