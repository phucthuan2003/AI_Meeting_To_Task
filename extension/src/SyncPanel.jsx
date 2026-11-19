import React, { useEffect, useRef, useState } from 'react';
import { ErrorNotice } from './components.jsx';
import { formatDue, safeTrelloUrl } from './review.mjs';
import Icon from './icons.jsx';

const JOB_LABELS = { QUEUED: 'Đang chờ tạo card', RUNNING: 'Đang tạo card', COMPLETED: 'Đã tạo xong', PARTIAL_FAILED: 'Một phần không thành công',
  FAILED: 'Không tạo được card', NEEDS_ACTION: 'Cần bạn xử lý' };
const ITEM_LABELS = { QUEUED: 'Đang chờ', SYNCING: 'Đang tạo', SYNCED: 'Đã tạo', FAILED: 'Lỗi', UNKNOWN: 'Chưa rõ kết quả' };
export const isRunningSync = job => job && ['QUEUED', 'RUNNING'].includes(job.status);

function LinkCard({ onLink, disabled }) {
  const [card, setCard] = useState('');
  const [ack, setAck] = useState(false);
  return <form className="link-card" onSubmit={event => { event.preventDefault(); onLink(card.trim(), ack); }}>
    <label>Link card trên Trello<input value={card} onChange={e => setCard(e.target.value)} placeholder="https://trello.com/c/…" /></label>
    <label className="check"><input type="checkbox" checked={ack} onChange={e => setAck(e.target.checked)} />Card này không có mã AI_MTT_REF nhưng tôi chắc đúng là card của task</label>
    <button disabled={disabled || card.length < 6} type="submit">Liên kết card</button>
  </form>;
}

export function SyncItem({ item, onAction, disabled }) {
  const [linking, setLinking] = useState(false);
  const url = safeTrelloUrl(item.cardUrl);
  const board = safeTrelloUrl(item.boardUrl);
  const tone = item.status.toLowerCase();
  return <article className={`sync-item ${tone}`}>
    <div className="sync-head">
      <span className={`status-dot ${item.status === 'SYNCED' ? 'ok' : item.status === 'FAILED' ? 'bad' : 'warn'}${['QUEUED', 'SYNCING'].includes(item.status) ? ' ping' : ''}`} />
      <strong className="break">{item.taskName}</strong>
      {url ? <a className="icon-link" href={url} target="_blank" rel="noopener noreferrer" title="Mở card"><Icon name="external" size={14} />Mở card</a>
        : <span className={`badge sync ${tone}`}>{ITEM_LABELS[item.status] ?? item.status}</span>}
    </div>
    {item.message && <p className="small">{item.message}</p>}
    <p className="tiny muted">{item.boardName} › {item.listName}{item.memberName ? ` · ${item.memberName}` : ''}{item.dueLocal ? ` · ${formatDue(item.dueLocal, item.timezone)}` : ''}</p>
    {item.errorCode && item.status !== 'SYNCED' && <p className="tiny muted mono">Mã: {item.errorCode} · lần gửi {item.attemptCount}{item.reconcileCount ? ` · đối soát ${item.reconcileCount}` : ''}</p>}
    <div className="actions">
      {item.actions.includes('RETRY') && <button disabled={disabled} onClick={() => onAction('retry', item)}>Thử lại</button>}
      {item.actions.includes('RECONCILE') && <button className="primary" disabled={disabled} onClick={() => onAction('reconcile', item)}>Đối soát ngay</button>}
      {item.actions.includes('LINK_CARD') && <button disabled={disabled} onClick={() => setLinking(!linking)}>{linking ? 'Đóng' : 'Đã có card'}</button>}
      {item.actions.includes('OPEN_BOARD') && board && <a className="button-link" href={board} target="_blank" rel="noopener noreferrer">Mở Board</a>}
      {item.actions.includes('RECREATE') && <button className="danger-button" disabled={disabled} onClick={() => {
        if (window.confirm('Đối soát chưa thấy card, nhưng card vẫn có thể đã được tạo. Tạo lại có thể sinh card trùng. Bạn đã kiểm tra trên Trello và muốn tạo lại?')) onAction('recreate', item);
      }}>Tạo lại (có nguy cơ trùng)</button>}
      {item.actions.includes('EDIT') && <span className="tiny muted">Sửa task trong danh sách review rồi tạo lại.</span>}
    </div>
    {linking && <LinkCard disabled={disabled} onLink={(card, ack) => onAction('link', item, { card, ack })} />}
  </article>;
}

/** Result of one "Tạo N card" (SDS §5.17): per-task outcome; UNKNOWN shown separately because it needs reconciliation. */
export default function SyncPanel({ api, token, jobId, taskVersions, onChanged, disabled, onNewMeeting }) {
  const [job, setJob] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const timer = useRef(null);
  const live = useRef(true);
  const lastStatus = useRef(null);
  async function load(delay = 2000) {
    clearTimeout(timer.current);
    try {
      const value = await api.syncJob(token, jobId);
      if (!live.current) return;
      const previous = lastStatus.current;
      lastStatus.current = value.results.map(r => r.status).join(',') + value.status;
      setJob(value);
      if (previous && previous !== lastStatus.current) onChanged?.();
      setError(null);
      if (isRunningSync(value) || value.results.some(r => ['QUEUED', 'SYNCING'].includes(r.status))) timer.current = setTimeout(() => load(Math.min(delay * 1.5, 15000)), delay);
    } catch (failure) { if (live.current) setError(failure); }
  }
  useEffect(() => { live.current = true; lastStatus.current = null; setJob(null); load(); return () => { live.current = false; clearTimeout(timer.current); }; }, [jobId]);
  async function action(kind, item, extra) {
    setBusy(true); setError(null);
    try {
      let value;
      if (kind === 'retry') value = await api.retrySyncItem(token, item.syncItemId);
      if (kind === 'reconcile') value = await api.reconcileSyncItem(token, item.syncItemId);
      if (kind === 'link') value = await api.linkCard(token, item.syncItemId, extra.card, extra.ack);
      if (kind === 'recreate') value = await api.recreateSyncItem(token, item.syncItemId, taskVersions[item.taskId]);
      if (value) setJob(value);
      onChanged?.();
      load();
    } catch (failure) { setError(failure); } finally { setBusy(false); }
  }
  if (!job) return error ? <ErrorNotice error={error} /> : <div className="skeleton-list"><div /><div /></div>;
  const unknown = job.results.filter(r => r.status === 'UNKNOWN');
  const others = job.results.filter(r => r.status !== 'UNKNOWN');
  const running = isRunningSync(job) || job.results.some(r => ['QUEUED', 'SYNCING'].includes(r.status));
  const done = job.status === 'COMPLETED' && !unknown.length;
  const board = safeTrelloUrl(job.results.find(r => r.boardUrl)?.boardUrl);
  const destination = job.results[0] ? `${job.results[0].boardName} › ${job.results[0].listName}` : '';
  return <div className="stack sync-panel" aria-live="polite">
    <section className={`card ${done ? 'success-card' : 'hero-card'}`}>
      <div className={`hero-icon ${done ? 'ok' : running ? '' : 'warn'}`}><Icon name={done ? 'checkCircle' : running ? 'refresh' : 'warning'} size={26} className={running ? 'spin' : ''} /></div>
      <p className="eyebrow">KẾT QUẢ TẠO CARD</p>
      <h2>{done ? `Đã tạo ${job.summary.synced} card` : JOB_LABELS[job.status] ?? job.status}</h2>
      {destination && <p className="small muted">trong {destination}</p>}
      <p className="tiny muted">Đã tạo {job.summary.synced} · lỗi {job.summary.failed} · chưa rõ {job.summary.unknown} · đang xử lý {job.summary.queued + job.summary.syncing}</p>
      <button className="quiet" disabled={busy} onClick={() => load()}><Icon name="refresh" size={14} />Làm mới</button>
    </section>
    {unknown.length > 0 && <div className="unknown-group">
      <p className="notice warn">Có {unknown.length} task chưa xác định được card đã tạo hay chưa. Hệ thống không tự tạo lại để tránh trùng; hãy đối soát hoặc liên kết card.</p>
      {unknown.map(item => <SyncItem key={item.syncItemId} item={item} onAction={action} disabled={disabled || busy} />)}
    </div>}
    <div className="card list-card">{others.map(item => <SyncItem key={item.syncItemId} item={item} onAction={action} disabled={disabled || busy} />)}</div>
    <ErrorNotice error={error} />
    <div className="action-bar">
      {board && <a className="button-link primary full big" href={board} target="_blank" rel="noopener noreferrer"><Icon name="external" size={16} />Mở Board trên Trello</a>}
      {onNewMeeting && <button className="quiet full" onClick={onNewMeeting}>Cuộc họp mới</button>}
    </div>
  </div>;
}
