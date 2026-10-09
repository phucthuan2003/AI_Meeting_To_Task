import React from 'react';

const warningLabels = {
  AI_REVIEW_REQUIRED: 'Cần kiểm tra đề xuất của AI', ASSIGNEE_MISSING: 'Chưa rõ người phụ trách',
  ASSIGNEE_NOT_RESOLVED_TO_MEMBER: 'Tên người chưa được đối chiếu với thành viên', DEADLINE_MISSING: 'Chưa có hạn',
  DEADLINE_NEEDS_CONFIRMATION: 'Cần xác nhận hạn', DEADLINE_AMBIGUOUS: 'Chưa diễn giải được hạn hoặc múi giờ',
};
export default function CandidateList({ view }) {
  if (!view) return null;
  return <section className="card">
    <p className="eyebrow">ĐỀ XUẤT AI</p><h3>Công việc trích xuất được</h3>
    <p className="notice">Kết quả cần bạn kiểm tra. Chưa có thao tác duyệt/sửa hoặc tạo card Trello.</p>
    <p className="small muted">{view.providerId === 'openai' ? 'OpenAI' : 'Gemini'} · {view.model} · Version {view.inputVersion}</p>
    {view.result.warnings?.map((warning, index) => <p className="notice" key={index}>{warning}</p>)}
    {view.result.candidates.length === 0 && <p>Không tìm thấy công việc cần thực hiện trong kết quả phân tích này.</p>}
    {view.result.candidates.map(task => <article className="candidate" key={task.taskId}>
      <h3>{task.taskName}</h3>
      <p className="small">Người phụ trách: <strong>{task.assigneeRaw ?? 'Chưa rõ'}</strong></p>
      <p className="small">Hạn trong cuộc họp: <strong>{task.deadlineRaw ?? 'Chưa có'}</strong></p>
      {task.dueLocal && <p className="small muted">Đề xuất thời gian: {task.dueLocal} · {task.timezone} (cần xác nhận)</p>}
      {task.priority && <p className="small">Ưu tiên được đề xuất: {task.priority}</p>}
      <ul className="small muted">{task.warnings.map(warning => <li key={warning}>{warningLabels[warning] ?? warning}</li>)}</ul>
      <details><summary>Bằng chứng từ transcript</summary>
        {task.evidence.map((item, index) => <div className="segment" key={index}>
          <p className="small muted">Segment #{item.sequence + 1} · {item.field}</p><p>{item.quote}</p>
        </div>)}
      </details>
    </article>)}
    <p className="small muted">Usage: input {view.result.inputTokens ?? 'không có số liệu'}, output {view.result.outputTokens ?? 'không có số liệu'} tokens · {view.result.latencyMs} ms</p>
  </section>;
}
