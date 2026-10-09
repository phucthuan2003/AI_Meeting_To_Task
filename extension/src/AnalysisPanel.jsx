import React, { useEffect, useRef, useState } from 'react';
import { ErrorNotice } from './components.jsx';
import { assertJobSnapshot, isActiveJob, pollJob } from './jobs.mjs';
import ReviewPanel from './ReviewPanel.jsx';

const statusLabels = {
  QUEUED: 'Đang chờ worker', PROCESSING: 'Đang xử lý', CANCEL_REQUESTED: 'Đang yêu cầu hủy',
  CANCELLED: 'Đã hủy', FAILED: 'Chưa hoàn tất', PARTIAL_FAILED: 'Một phần chưa hoàn tất', COMPLETED: 'Đã hoàn tất',
};
const stageLabels = { WAITING: 'Chờ xử lý', PREPARING_INPUT: 'Chuẩn bị nguồn và ngân sách input', READY_FOR_PROVIDER: 'Đã chuẩn bị nguồn', CALLING_LLM: 'Đang gọi AI và kiểm tra kết quả', CANCELLING: 'Chờ worker xác nhận hủy' };
const providerNames = { openai: 'OpenAI', gemini: 'Gemini', unconfigured: 'Job chuẩn bị trước đây' };

export function ProviderChoice({ policies, value, onChange, disabled }) {
  return <label>Chọn AI
    <select value={value} onChange={event => onChange(event.target.value)} disabled={disabled}>
      <option value="" disabled>Chọn OpenAI hoặc Gemini</option>
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
    <p className="small">AI của job: {providerNames[job.providerId] ?? job.providerId}</p>
    <p className="small">Nguồn đã chuẩn bị: {job.preparedSegments} / {segmentCount} segments</p>
    {job.totalChunks !== null && <p className="small">Phần phân tích hoàn tất: {job.completedChunks} / {job.totalChunks}</p>}
    {job.error && <div className="notice error"><strong>{job.error.message}</strong><div className="small">{job.error.code}</div></div>}
    <p className="small muted break">Job ID: {job.jobId}<br />Input version: {job.inputVersion} · Lần worker nhận: {job.attemptCount}</p>
  </div>;
}

export default function AnalysisPanel({ api, token, meeting, onActivity, onReviewDirty, disabled, onReload }) {
  const [policies, setPolicies] = useState([]);
  const [selectedProvider, setSelectedProvider] = useState('');
  const [job, setJob] = useState(null);
  const [error, setError] = useState(null);
  const [working, setWorking] = useState(false);
  const [loading, setLoading] = useState(true);
  const [pollNote, setPollNote] = useState('');
  const poller = useRef(null);
  const pendingStart = useRef(null);
  const mounted = useRef(false);
  const generation = useRef(0);

  function accept(result, restoreProvider = false) {
    assertJobSnapshot(result, meeting);
    if (restoreProvider || isActiveJob(result)) setSelectedProvider(['openai', 'gemini'].includes(result.providerId) ? result.providerId : '');
    setJob(result); setError(null); setPollNote(''); onActivity(isActiveJob(result));
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
    pendingStart.current = null;
    setLoading(true); setWorking(false); setJob(null); setPolicies([]); setSelectedProvider(''); setError(null); setPollNote('');
    onActivity(Boolean(meeting.currentAnalysisJobId) || isActiveJob(meeting.analysisStatus));
    const policyLoad = api.analysisPolicies(token).then(result => {
      if (mounted.current && current === generation.current) {
        const options = result.filter(item => ['openai', 'gemini'].includes(item.providerId));
        setPolicies(options);
        if (!options.length) throw new Error('Backend chưa hỗ trợ chọn OpenAI/Gemini. Khởi động lại backend mới rồi tải lại meeting.');
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

  async function action(request) {
    if (working || disabled || loading) return;
    const current = generation.current;
    setWorking(true); setError(null);
    onActivity(true);
    try {
      const result = await request();
      if (!mounted.current || current !== generation.current) return;
      accept(result, true); follow(result.jobId);
    } catch (failure) {
      if (mounted.current && current === generation.current) {
        setError(failure);
        // A lost response may hide an accepted job. Reload metadata before allowing input edits.
        onActivity(isActiveJob(job) || !failure.status || failure.status >= 500 || failure.code === 'RESOURCE_BUSY');
      }
    } finally { if (mounted.current && current === generation.current) setWorking(false); }
  }
  const policy = policies.find(item => item.providerId === selectedProvider);
  const active = isActiveJob(job) || (!job && isActiveJob(meeting.analysisStatus));
  const locked = disabled || working || loading;
  return <><section className="card">
    <p className="eyebrow">PHÂN TÍCH</p><h3>Tiến trình xử lý cuộc họp</h3>
    {loading && <p className="small muted">Đang khôi phục trạng thái từ backend…</p>}
    <ProviderChoice policies={policies} value={selectedProvider} disabled={locked || active || Boolean(pendingStart.current)} onChange={setSelectedProvider} />
    {!loading && !policy && policies.length > 0 && <p className="small muted">Chọn AI trước khi tạo job. Lựa chọn được lưu theo từng job.</p>}
    {policy && <p className={policy.providerReady ? 'small muted' : 'notice'}>{policy.description}
      {!policy.providerReady && ' Job chuẩn bị chưa tạo công việc; cấu hình key ở backend rồi khởi động lại.'}
    </p>}
    <ErrorNotice error={error} />
    {pollNote && <p className="small muted">{pollNote}</p>}
    {pendingStart.current && !working && <p className="small muted">Chưa xác định yêu cầu tạo job đã được lưu hay chưa. Bấm nút tạo/phân tích để gửi lại cùng lựa chọn, hoặc tải lại meeting để kiểm tra.</p>}
    <AnalysisJobStatus job={job} segmentCount={meeting.segmentCount} />
    <div className="actions">
      <button className="primary" disabled={locked || active || !policy} onClick={() => {
        // Re-analysis publishes a new candidate set; AI drafts of the old set leave the current review (manual tasks stay).
        if (!pendingStart.current && job?.status === 'COMPLETED' && !window.confirm('Phân tích lại sẽ tạo danh sách đề xuất AI mới. Chỉnh sửa trên các đề xuất AI hiện tại sẽ không còn hiển thị; task thủ công được giữ. Tiếp tục?')) return;
        pendingStart.current ??= { key: crypto.randomUUID(), providerId: policy.providerId, processingPolicyId: policy.processingPolicyId };
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
      }}>{policy?.providerReady ? (job?.status === 'COMPLETED' ? 'Phân tích lại' : 'Phân tích') : 'Tạo job chuẩn bị'}</button>
      {job && <button disabled={locked} onClick={() => follow(job.jobId)}>Làm mới trạng thái</button>}
      {active && job && <button disabled={locked || job.status === 'CANCEL_REQUESTED'} onClick={() => action(() => api.cancelJob(token, job.jobId))}>Hủy job</button>}
      {job?.error?.retryable && !active && <button disabled={locked} onClick={() => action(() => api.retryJob(token, job.jobId))}>Thử lại job</button>}
      {!job && error && <button disabled={locked} onClick={onReload}>Tải lại meeting</button>}
    </div>
    <p className="small muted">Job lưu ở backend và tiếp tục khi panel đóng. Input bị khóa sửa trong lúc job đang chờ, xử lý hoặc hủy.</p>
    {active && <p className="small muted">Hủy là best effort. Khi có provider thật, request đã gửi có thể vẫn được tính phí.</p>}
  </section>{!loading && <ReviewPanel api={api} token={token} meeting={meeting} job={job} onDirty={onReviewDirty} disabled={disabled} />}</>;
}
