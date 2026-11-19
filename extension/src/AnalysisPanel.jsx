import React, { useEffect, useRef, useState } from 'react';
import { ErrorNotice } from './components.jsx';
import { assertJobSnapshot, isActiveJob, pollJob } from './jobs.mjs';
import ReviewPanel from './ReviewPanel.jsx';
import { watchSnapshots } from './streams.mjs';
import { createStorage } from './storage.mjs';
import Icon from './icons.jsx';

const statusLabels = {
  QUEUED: 'Đang chờ worker', PROCESSING: 'Đang xử lý', CANCEL_REQUESTED: 'Đang yêu cầu hủy',
  CANCELLED: 'Đã hủy', FAILED: 'Chưa hoàn tất', PARTIAL_FAILED: 'Một phần chưa hoàn tất', COMPLETED: 'Đã hoàn tất',
};
const stageLabels = { WAITING: 'Chờ xử lý', PREPARING_INPUT: 'Chuẩn bị nguồn và ngân sách input', READY_FOR_PROVIDER: 'Đã chuẩn bị nguồn', CALLING_LLM: 'Đang phân tích và kiểm tra kết quả', CANCELLING: 'Chờ worker xác nhận hủy' };
const providerNames = { openai: 'OpenAI', gemini: 'Gemini', unconfigured: 'Job chuẩn bị trước đây' };

export function ProviderChoice({ policies, value, onChange, disabled }) {
  return <label>Dịch vụ phân tích
    <select value={value} onChange={event => onChange(event.target.value)} disabled={disabled}>
      <option value="" disabled>Chọn dịch vụ</option>
      {policies.map(policy => <option key={policy.providerId} value={policy.providerId}>
        {policy.displayName ?? providerNames[policy.providerId] ?? policy.providerId}{policy.providerReady ? '' : ' — chưa kết nối'}
      </option>)}
    </select>
  </label>;
}

export function AnalysisJobStatus({ job, segmentCount }) {
  if (!job) return null;
  const statusLabel = statusLabels[job.status] ?? job.status;
  const stageLabel = stageLabels[job.stage] ?? statusLabels[job.stage] ?? job.stage;
  return <div className="job-status" aria-live="polite">
    <p><strong>{statusLabel}</strong></p>
    {stageLabel !== statusLabel && <p className="small muted">{stageLabel}</p>}
    <p className="small">Dịch vụ của lượt phân tích: {providerNames[job.providerId] ?? job.providerId}</p>
    <p className="small">Nguồn đã chuẩn bị: {job.preparedSegments} / {segmentCount} segments</p>
    {job.totalChunks !== null && <p className="small">Phần phân tích hoàn tất: {job.completedChunks} / {job.totalChunks}</p>}
    {job.error && <div className="notice error"><strong>{job.error.message}</strong><div className="small mono">{job.error.code}</div></div>}
  </div>;
}

export default function AnalysisPanel({ api, token, meeting, onActivity, onReviewDirty, disabled, onReload, page = 'analyze', preferredProvider,
  autoStart, onAutoStartDone, onCompleted, onGoReview, header, reviewProps = {} }) {
  const [provisional, setProvisional] = useState([]);
  const [policies, setPolicies] = useState([]);
  const [selectedProvider, setSelectedProvider] = useState('');
  const [job, setJob] = useState(null);
  const [error, setError] = useState(null);
  const [working, setWorking] = useState(false);
  const [loading, setLoading] = useState(true);
  const [pollNote, setPollNote] = useState('');
  const [showDetail, setShowDetail] = useState(false);
  const poller = useRef(null);
  const pendingStart = useRef(null);
  const mounted = useRef(false);
  const generation = useRef(0);
  const wasActive = useRef(false);
  const autoStarted = useRef(false);

  function accept(result, restoreProvider = false) {
    assertJobSnapshot(result, meeting);
    if (restoreProvider || isActiveJob(result)) setSelectedProvider(['openai', 'gemini'].includes(result.providerId) ? result.providerId : '');
    const active = isActiveJob(result);
    if (wasActive.current && !active && result.status === 'COMPLETED') onCompleted?.();
    wasActive.current = active;
    setJob(result); setError(null); setPollNote(''); onActivity(active);
  }
  function follow(id) {
    poller.current?.stop();
    const current = generation.current;
    poller.current = pollJob({
      load: async () => assertJobSnapshot(await api.job(token, id), meeting),
      onJob: result => { if (mounted.current && current === generation.current) accept(result); },
      onError: (failure, { willRetry }) => {
        if (!mounted.current || current !== generation.current) return;
        setError(failure); setPollNote(willRetry ? 'Đang chờ để đọc lại trạng thái.' : 'Đã tạm dừng đọc trạng thái. Bấm Làm mới trạng thái để thử lại.');
      },
    });
  }
  useEffect(() => {
    mounted.current = true;
    generation.current++;
    const current = generation.current;
    pendingStart.current = null; wasActive.current = false;
    setLoading(true); setWorking(false); setJob(null); setPolicies([]); setSelectedProvider(''); setError(null); setPollNote('');
    onActivity(Boolean(meeting.currentAnalysisJobId) || isActiveJob(meeting.analysisStatus));
    const policyLoad = api.analysisPolicies(token).then(result => {
      if (mounted.current && current === generation.current) {
        const options = result.filter(item => ['openai', 'gemini'].includes(item.providerId));
        setPolicies(options);
        if (!meeting.currentAnalysisJobId && options.some(item => item.providerId === preferredProvider)) setSelectedProvider(preferredProvider);
        if (!options.length) throw new Error('Chưa có dịch vụ phân tích khả dụng. Kiểm tra cấu hình backend rồi tải lại cuộc họp.');
      }
    });
    const jobLoad = meeting.currentAnalysisJobId ? api.job(token, meeting.currentAnalysisJobId).then(result => {
      if (!mounted.current || current !== generation.current) return;
      accept(result, true); if (isActiveJob(result)) follow(result.jobId);
    }) : Promise.resolve();
    Promise.allSettled([policyLoad, jobLoad]).then(results => {
      if (!mounted.current || current !== generation.current) return;
      const failed = results.find(result => result.status === 'rejected');
      if (failed) setError(failed.reason);
      setLoading(false);
    });
    return () => {
      mounted.current = false; generation.current++; poller.current?.stop(); onActivity(false);
    };
  }, [api, token, meeting.meetingId, meeting.transcriptRevision, meeting.inputVersion, meeting.currentAnalysisJobId, onActivity]);

  useEffect(() => {
    if (!job?.jobId || !isActiveJob(job)) { setProvisional([]); return; }
    const stream = watchSnapshots(__API_ORIGIN__, `/api/v1/jobs/${job.jobId}/events`, token, {
      onUnauthorized: failed => createStorage(chrome.storage,__API_ORIGIN__).clearSession(failed),
      onSnapshot: snapshot => { if (snapshot.job.jobId === job.jobId) { accept(snapshot.job); setProvisional(snapshot.provisional ?? []); } },
      onError: () => {}, // bounded GET polling remains available as a fallback
    });
    return () => stream.stop();
  }, [job?.jobId, isActiveJob(job), token]);

  async function action(request) {
    if (working || disabled || loading) return;
    const current = generation.current;
    setWorking(true); setError(null);
    onActivity(true);
    try {
      const result = await request();
      if (!mounted.current || current !== generation.current) return;
      wasActive.current = true;
      accept(result, true); follow(result.jobId);
    } catch (failure) {
      if (mounted.current && current === generation.current) {
        setError(failure);
        // A lost response may hide an accepted job. Reload metadata before allowing input edits.
        onActivity(isActiveJob(job) || !failure.status || failure.status >= 500 || failure.code === 'RESOURCE_BUSY');
      }
    } finally { if (mounted.current && current === generation.current) setWorking(false); }
  }
  function start(chosen, confirmed = false) {
    if (!chosen) return;
    // Re-analysis publishes a new candidate set; AI drafts of the old set leave the current review (manual tasks stay).
    if (!confirmed && !pendingStart.current && job?.status === 'COMPLETED' && !window.confirm('Phân tích lại sẽ thay các đề xuất hiện tại và bỏ phần chỉnh sửa trên các đề xuất đó. Công việc thủ công được giữ. Tiếp tục?')) return;
    pendingStart.current ??= { key: crypto.randomUUID(), providerId: chosen.providerId, processingPolicyId: chosen.processingPolicyId };
    const pending = pendingStart.current;
    const current = generation.current;
    action(async () => {
      try {
        const result = await api.startAnalysis(token, meeting.meetingId, {
          expectedInputVersion: meeting.inputVersion, providerId: pending.providerId, processingPolicyId: pending.processingPolicyId,
        }, pending.key);
        if (current === generation.current && pendingStart.current === pending) pendingStart.current = null;
        return result;
      } catch (failure) {
        if (current === generation.current && pendingStart.current === pending && failure.status >= 400 && failure.status < 500 && failure.status !== 408) pendingStart.current = null;
        throw failure;
      }
    });
  }
  const policy = policies.find(item => item.providerId === selectedProvider);
  const active = isActiveJob(job) || (!job && isActiveJob(meeting.analysisStatus));
  const locked = disabled || working || loading;

  // "Lưu & phân tích": start once with the preferred service when it is ready and nothing has run yet.
  useEffect(() => {
    if (!autoStart || autoStarted.current || loading || job || active) return;
    autoStarted.current = true;
    const chosen = policies.find(item => item.providerId === selectedProvider && item.providerReady);
    onAutoStartDone?.();
    if (chosen) start(chosen, true);
  }, [autoStart, loading, job, active, policies, selectedProvider]);

  const fraction = job?.totalChunks ? Math.min(1, job.completedChunks / job.totalChunks) : null;
  const visible = page === 'analyze';
  const startLabel = policy?.providerReady ? (job?.status === 'COMPLETED' ? 'Phân tích lại' : 'Phân tích bằng AI') : 'Tạo job chuẩn bị';
  const failed = job && ['FAILED', 'PARTIAL_FAILED', 'CANCELLED'].includes(job.status);
  return <>{visible && <div className="stack">
    {header}
    {loading ? <div className="skeleton-list"><div /><div /></div> : active ? <section className="card hero-card">
      <div className="glow" />
      <div className="hero-icon"><Icon name="sparkle" size={24} className="pulse" /></div>
      <h2>AI đang đọc cuộc họp…</h2>
      <p className="small muted">Thường mất 10–30 giây · {statusLabels[job?.status] ?? 'Đang chờ worker'}</p>
      {fraction !== null && <div className="progress" role="progressbar" aria-valuemin={0} aria-valuemax={job.totalChunks} aria-valuenow={job.completedChunks}>
        <div style={{ width: `${Math.round(fraction * 100)}%` }} /></div>}
      {fraction !== null && <p className="tiny muted">Phần {job.completedChunks}/{job.totalChunks}</p>}
    </section> : job?.status === 'COMPLETED' ? <section className="card success-card">
      <div className="hero-icon ok"><Icon name="checkCircle" size={24} /></div>
      <h2>Đã phân tích xong</h2>
      <p className="small muted">Kiểm tra lại người làm và hạn trước khi tạo card.</p>
      <button className="primary full big" onClick={onGoReview}>Duyệt công việc<Icon name="arrow" size={16} /></button>
    </section> : failed ? <section className="card warn-card">
      <div className="notice-row"><span className="row-icon warn"><Icon name="warning" size={17} /></span>
        <div><h3>{job.status === 'CANCELLED' ? 'Đã hủy phân tích' : 'Chưa phân tích xong'}</h3>
          <p className="small muted">{job.error?.message ?? 'Bạn có thể thử lại hoặc chọn dịch vụ khác.'}</p></div></div>
      {job.error?.retryable && !active && <button className="primary full" disabled={locked} onClick={() => action(() => api.retryJob(token, job.jobId))}><Icon name="refresh" size={16} />Thử lại job</button>}
    </section> : <section className="card hero-card">
      <div className="hero-icon"><Icon name="sparkle" size={24} /></div>
      <h2>Sẵn sàng phân tích</h2>
      <p className="small muted">AI sẽ tách công việc, người làm và hạn từ nội dung đã lưu.</p>
    </section>}

    {provisional.length > 0 && <section className="stack">
      <div className="section-label"><span>TÁC VỤ PHÁT HIỆN</span><span className="pill busy"><span className="dot ping" />Đang tìm ({provisional.length})</span></div>
      <p className="tiny muted">Đề xuất tạm từ các phần đã xử lý — chưa duyệt/tạo card; câu sửa hoặc hủy ở phần sau có thể thay đổi kết quả.</p>
      {provisional.map(task => <div className="mini-task" key={task.taskRef}>
        <span className="avatar tone-0">{(task.assigneeRaw || '?')[0].toUpperCase()}</span>
        <span className="row-main"><strong>{task.taskName}</strong><span className="small muted">{task.assigneeRaw ? `Giao cho ${task.assigneeRaw}` : 'Chưa rõ người'}</span></span>
      </div>)}
      <div className="mini-task shimmer"><span className="avatar" /><span className="row-main"><span className="bar" /><span className="bar short" /></span></div>
    </section>}

    {!loading && !active && <section className="card">
      <ProviderChoice policies={policies} value={selectedProvider} disabled={locked || active || Boolean(pendingStart.current)} onChange={setSelectedProvider} />
      {policy && !policy.providerReady && <p className="notice">{policy.description} Job chuẩn bị chưa tạo công việc; cấu hình key ở backend rồi khởi động lại.</p>}
      {pendingStart.current && !working && <p className="small muted">Chưa xác định yêu cầu tạo job đã được lưu hay chưa. Bấm nút tạo/phân tích để gửi lại cùng lựa chọn, hoặc tải lại meeting để kiểm tra.</p>}
      <button className={job?.status === 'COMPLETED' ? 'full' : 'primary full big'} disabled={locked || active || !policy} onClick={() => start(policy)}>
        {job?.status !== 'COMPLETED' && <Icon name="sparkle" size={17} />}{startLabel}</button>
    </section>}

    <ErrorNotice error={error} />
    {pollNote && <p className="small muted">{pollNote}</p>}
    {job && <section className="card flat">
      <button className="disclosure" onClick={() => setShowDetail(!showDetail)} aria-expanded={showDetail}><span>Chi tiết tiến trình</span><Icon name="chevron" size={16} /></button>
      {showDetail && <AnalysisJobStatus job={job} segmentCount={meeting.segmentCount} />}
    </section>}
    <div className="actions center">
      {job && <button className="quiet" disabled={locked} onClick={() => follow(job.jobId)}><Icon name="refresh" size={15} />Làm mới trạng thái</button>}
      {active && job && <button className="quiet danger-text" disabled={locked || job.status === 'CANCEL_REQUESTED'} onClick={() => action(() => api.cancelJob(token, job.jobId))}>Hủy job</button>}
      {!job && error && <button className="quiet" disabled={locked} onClick={onReload}>Tải lại meeting</button>}
    </div>
  </div>}
  {!loading && <ReviewPanel api={api} token={token} meeting={meeting} job={job} onDirty={onReviewDirty} disabled={disabled} page={page} {...reviewProps} />}
  {loading && page !== 'analyze' && <div className="skeleton-list"><div /><div /><div /></div>}</>;
}
