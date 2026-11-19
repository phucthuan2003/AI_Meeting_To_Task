import React, { useEffect, useRef, useState } from 'react';
import { ErrorNotice } from './components.jsx';
import { validateAudio, CAPTURE_STATE, liveDraftPatch } from './audio.mjs';
import { watchSnapshots } from './streams.mjs';
import { createStorage } from './storage.mjs';
import Icon from './icons.jsx';

const CAPTURE_LABELS = { OPEN: 'Đang mở', DRAINING: 'Đang xử lý đoạn cuối', READY: 'Sẵn sàng chốt', FAILED: 'Xử lý lỗi', PUBLISHED: 'Đã lưu thành meeting', CANCELLED: 'Đã hủy' };
const clock = seconds => `${String(Math.floor((seconds ?? 0) / 60)).padStart(2, '0')}:${String(Math.floor((seconds ?? 0) % 60)).padStart(2, '0')}`;

function LiveDraft({ proposal, override, version, onSave, onDirty }) {
  const [draft,setDraft]=useState(null),[error,setError]=useState(null),[busy,setBusy]=useState(false);
  useEffect(()=>{ onDirty(Boolean(draft)); return ()=>onDirty(false); },[Boolean(draft)]);
  const displayed={...proposal,...override};
  return <div className="live-task">
    <div className="live-task-head"><span className="dot" /><strong>{displayed.taskName}</strong>
      <span className="tiny muted">{displayed.assigneeRaw || 'Chưa rõ người'} · {displayed.deadlineRaw || 'Chưa rõ hạn'}</span></div>
    {override && <p className="tiny notice">Đề xuất gốc có thể thay đổi trong lúc thu. Phần bạn sửa được giữ khi chốt nếu công việc vẫn còn trong danh sách.</p>}
    <details className="evidence"><summary><Icon name="quote" size={13} />Bằng chứng</summary>{proposal.evidence.map((e,i)=><blockquote className="quote" key={i}><p>{e.quote}</p></blockquote>)}</details>
    {!draft ? <button className="link-button" onClick={()=>setDraft({ expectedVersion:version, base:{taskName:displayed.taskName,assigneeRaw:displayed.assigneeRaw??'',deadlineRaw:displayed.deadlineRaw??''}, taskName:displayed.taskName,assigneeRaw:displayed.assigneeRaw??'',deadlineRaw:displayed.deadlineRaw??'' })}>Sửa bản nháp live</button> : <form className="live-edit" onSubmit={async e=>{
      e.preventDefault();setBusy(true);setError(null);
      try {const patch=liveDraftPatch(draft.base,draft);
        if(Object.keys(patch).length)await onSave({expectedVersion:draft.expectedVersion,taskRef:proposal.taskRef,patch});setDraft(null);}
      catch(err){setError(err);}finally{setBusy(false);}
    }}>
      <label>Tên việc<input required maxLength={255} value={draft.taskName} onChange={e=>setDraft({...draft,taskName:e.target.value})}/></label>
      <div className="columns">
        <label>Người phụ trách<input maxLength={255} value={draft.assigneeRaw} onChange={e=>setDraft({...draft,assigneeRaw:e.target.value})}/></label>
        <label>Hạn nguyên văn<input maxLength={255} value={draft.deadlineRaw} onChange={e=>setDraft({...draft,deadlineRaw:e.target.value})}/></label>
      </div>
      <ErrorNotice error={error}/>{error?.status===409&&<button type="button" onClick={()=>{setDraft({...draft,expectedVersion:version});setError(null);}}>Giữ bản sửa và dùng phiên bản mới nhất</button>}
      <div className="actions"><button className="primary" disabled={busy}>Lưu bản sửa</button><button type="button" onClick={()=>setDraft(null)}>Bỏ sửa</button></div>
    </form>}
  </div>;
}

export default function AudioPanel({ api, session, onOpenMeeting, onDirty = () => {}, tabs }) {
  const token=session.accessToken, origin=session.origin;
  const pointer=`capture:${origin}:${session.user.id}`;
  const [sessions,setSessions]=useState([]);
  const [config,setConfig]=useState(null),[capture,setCapture]=useState(null),[local,setLocal]=useState(null);
  const [mode,setMode]=useState('LIVE'),[provider,setProvider]=useState(''),[policies,setPolicies]=useState([]);
  const [title,setTitle]=useState(''),[date,setDate]=useState(''),[zone,setZone]=useState(Intl.DateTimeFormat().resolvedOptions().timeZone);
  const [microphone,setMicrophone]=useState(true),[file,setFile]=useState(null),[busy,setBusy]=useState(false),[error,setError]=useState(null),[note,setNote]=useState('');
  const [dirtyCount,setDirtyCount]=useState(0);
  const draftRefs=useRef(new Set());
  useEffect(()=>()=>onDirty(false),[]);
  const pending=useRef(null), mounted=useRef(true);
  async function action(fn) {
    setBusy(true);setError(null);
    try{await fn();}catch(e){if(mounted.current)setError(e);}finally{if(mounted.current)setBusy(false);}
  }
  useEffect(()=>{
    mounted.current=true;
    api.captureConfig(token).then(v=>mounted.current&&setConfig(v)).catch(setError);
    api.analysisPolicies(token).then(v=>mounted.current&&setPolicies(v.filter(p=>p.providerReady))).catch(setError);
    chrome.storage.local.get(pointer).then(async v=>{
      const all=await api.captures(token);if(!mounted.current)return;setSessions(all);
      const id=all.find(item=>item.id===v[pointer])?.id ?? all.find(item=>['OPEN','DRAINING','READY','FAILED'].includes(item.status))?.id;
      if(id){const saved=await api.capture(token,id);if(mounted.current)setCapture(saved);}
    }).catch(setError);
    const changed=(changes,area)=>{if(area==='session'&&changes[CAPTURE_STATE])readLocal();};
    const readLocal=()=>chrome.storage.session.get(CAPTURE_STATE).then(v=>{const value=v[CAPTURE_STATE];if(mounted.current)setLocal(value?.userId===session.user.id&&value.origin===origin?value:null);});
    readLocal();chrome.storage.onChanged.addListener(changed);
    return()=>{mounted.current=false;chrome.storage.onChanged.removeListener(changed);};
  },[token,pointer]);
  useEffect(()=>{
    if(!capture?.id)return;
    const stream=watchSnapshots(origin,`/api/v1/captures/${capture.id}/events`,token,{
      onUnauthorized:failed=>createStorage(chrome.storage,origin).clearSession(failed),
      onSnapshot:s=>{if(mounted.current)setCapture(s);},
      onError:(err,{willRetry})=>{if(!willRetry&&mounted.current){setError(err);setNote('Luồng đã dừng; bấm Làm mới. Phiên trên backend vẫn còn.');}},
    });
    return()=>stream.stop();
  },[capture?.id,token]);
  async function remember(value){setCapture(value);setSessions(old=>old.some(item=>item.id===value.id)?old:[{id:value.id,mode:value.mode,status:value.status},...old]);await chrome.storage.local.set({[pointer]:value.id});}
  async function create() {
    if(mode==='FILE')validateAudio(file);
    pending.current??={key:crypto.randomUUID(),body:{mode,title,meetingDate:date||null,timezone:zone,providerId:mode==='LIVE'?provider:undefined}};
    const saved=await api.createCapture(token,pending.current.body,pending.current.key);await remember(saved);pending.current=null;
    if(mode==='FILE'){
      await api.uploadAudio(token,saved.id,0,0,file);
      setCapture(await api.stopCapture(token,saved.id,1));
    }
  }
  async function control(command) {
    const result=await chrome.runtime.sendMessage({target:'capture-worker',command,id:capture.id});
    if(!result?.ok)throw new Error(result?.error||'Không điều khiển được phiên thu.');
  }
  async function begin() {
    if(microphone){const permission=await navigator.mediaDevices.getUserMedia({audio:true});permission.getTracks().forEach(t=>t.stop());}
    const [tab]=await chrome.tabs.query({active:true,currentWindow:true});
    const result=await chrome.runtime.sendMessage({target:'capture-worker',command:'start',id:capture.id,userId:session.user.id,tabId:tab.id,microphone});
    if(!result?.ok)throw new Error(result?.error||'Không thu được tab.');
  }

  const fresh = !capture || capture.status==='CANCELLED';
  const recording = local?.id===capture?.id && local?.state==='RECORDING';
  const actions = [];
  if (!fresh) {
    if(capture.status==='OPEN'&&capture.mode==='LIVE'){
      if (recording) actions.push(<button key="stop" className="primary full big" disabled={busy} onClick={()=>action(()=>control('stop'))}><Icon name="stop" size={16}/>Dừng thu & gửi đoạn cuối</button>);
      else actions.push(<button key="start" className="primary full big" disabled={busy||capture.nextSequence>0} onClick={()=>action(begin)}><Icon name="mic" size={16}/>Bắt đầu thu tab đang mở</button>);
      if (!recording && capture.nextSequence>0) actions.push(<button key="stop2" className="full" disabled={busy} onClick={()=>action(()=>control('stop'))}>Dừng thu & gửi đoạn cuối</button>);
      actions.push(<button key="seal" className="quiet full" disabled={busy} onClick={()=>action(async()=>{if(!window.confirm('Chỉ chốt các đoạn backend đã nhận? Phần audio chưa gửi có thể không được đưa vào.'))return;await control('cancel').catch(()=>{});setCapture(await api.stopCapture(token,capture.id,capture.nextSequence));})}>Chốt các đoạn đã nhận</button>);
    }
    if(local?.id===capture.id&&local?.state==='ERROR') actions.push(<button key="resend" className="full" disabled={busy} onClick={()=>action(()=>control('retry'))}>Gửi lại đoạn đang chờ</button>);
    if(capture.status==='FAILED') actions.push(<button key="retry" className="primary full big" disabled={busy} onClick={()=>action(async()=>{await control('stop').catch(()=>{});setCapture(await api.retryCapture(token,capture.id));})}>Thử lại xử lý backend</button>);
    if(capture.status==='READY') actions.push(<button key="publish" className="primary full big" disabled={busy||dirtyCount>0} onClick={()=>action(async()=>{const value=await api.publishCapture(token,capture.id);setCapture(await api.capture(token,capture.id));onOpenMeeting(value.meetingId);})}><Icon name="sparkle" size={16}/>Chốt & lưu thành meeting để review</button>);
    if(capture.meetingId) actions.push(<button key="open" className="primary full big" onClick={()=>onOpenMeeting(capture.meetingId)}>Mở meeting đã lưu<Icon name="arrow" size={16}/></button>);
    if(capture.status==='PUBLISHED') actions.push(<button key="new" className="full" onClick={()=>{setCapture(null);chrome.storage.local.remove(pointer);}}>Phiên mới</button>);
    actions.push(<div key="minor" className="actions center">
      <button className="quiet" disabled={busy} onClick={()=>action(async()=>setCapture(await api.capture(token,capture.id)))}><Icon name="refresh" size={14}/>Làm mới</button>
      {!['PUBLISHED','CANCELLED'].includes(capture.status)&&<button className="quiet danger-text" disabled={busy} onClick={()=>action(async()=>{if(!window.confirm('Hủy phiên và xóa audio/transcript tạm chưa lưu?'))return;await control('cancel').catch(()=>{});setCapture(await api.cancelCapture(token,capture.id));})}>Hủy phiên</button>}
    </div>);
  }

  return <div className="stack audio-panel">
    <section className="card">
      {tabs}
      {sessions.length>0&&<label className="compact">Khôi phục phiên đã có<select value={capture?.id??''} onChange={e=>{const id=e.target.value;if(id)action(async()=>remember(await api.capture(token,id)));}}>
        <option value="">Chọn phiên</option>{sessions.map(item=><option key={item.id} value={item.id}>{item.title||item.mode} · {CAPTURE_LABELS[item.status]??item.status} · {item.id.slice(0,8)}</option>)}
      </select></label>}
      {config&&!config.sttReady&&<p className="notice warn">Chưa cấu hình dịch vụ nhận diện giọng nói ở backend.</p>}
      {fresh ? <fieldset disabled={busy||!config?.sttReady}>
        <div className="option-grid" role="group" aria-label="Nguồn">
          <button type="button" className="option-card" aria-pressed={mode==='LIVE'} onClick={()=>setMode('LIVE')}><Icon name="tab" size={18}/><strong>Thu tab cuộc họp</strong><span className="tiny muted">Google Meet, Zoom web…</span></button>
          <button type="button" className="option-card" aria-pressed={mode==='FILE'} onClick={()=>setMode('FILE')}><Icon name="wave" size={18}/><strong>Tải file âm thanh</strong><span className="tiny muted">MP3, WAV, M4A, WebM</span></button>
        </div>
        {mode==='FILE'?<label className="dropzone"><Icon name="upload" size={24}/><strong>{file?file.name:'Chọn file ghi âm'}</strong><span className="tiny muted">Tối đa 10 MiB</span>
          <input type="file" accept=".wav,.mp3,.m4a,.webm" onChange={e=>setFile(e.target.files?.[0]??null)}/></label>:<>
          <label>Dịch vụ phân tích<select value={provider} onChange={e=>setProvider(e.target.value)}><option value="">Chọn dịch vụ</option>{policies.map(p=><option key={p.providerId} value={p.providerId}>{p.displayName}</option>)}</select></label>
          <div className="setting-row"><Icon name="mic" size={16}/><span className="row-main small">Kèm microphone</span>
            <label className="switch"><input type="checkbox" checked={microphone} onChange={e=>setMicrophone(e.target.checked)} aria-label="Kèm microphone"/><span /></label></div>
          <button type="button" className="link-button" onClick={()=>chrome.tabs.create({url:chrome.runtime.getURL('microphone.html')})}>Cấp quyền microphone</button>
        </>}
        <label className="pill-field"><Icon name="edit" size={15}/><input maxLength={255} aria-label="Tiêu đề" placeholder="Tên cuộc họp (tuỳ chọn)" value={title} onChange={e=>setTitle(e.target.value)}/></label>
        <div className="meta-row">
          <label className="pill-field"><Icon name="calendar" size={15}/><input type="date" aria-label="Ngày họp" value={date} onChange={e=>setDate(e.target.value)}/></label>
          <label className="pill-field"><Icon name="globe" size={15}/><input aria-label="Múi giờ" value={zone} onChange={e=>setZone(e.target.value)}/></label>
        </div>
      </fieldset> : <div className="rec">
        <div className={`rec-dot${recording?' on':''}`}><span /></div>
        <span className="rec-time">{clock(local?.id===capture.id?local.capturedSeconds:0)}</span>
        <span className="small muted">{recording ? (microphone ? 'Đang nghe tab và micro' : 'Đang nghe tab') : CAPTURE_LABELS[capture.status] ?? capture.status}</span>
        {recording && <div className="wave" aria-hidden="true">{Array.from({length:14},(_,i)=><span key={i} style={{animationDelay:`${(i*97)%500}ms`}}/>)}</div>}
        <p className="small"><strong>{capture.status}</strong> · Đã xử lý {capture.processedSequence+1}/{capture.nextSequence} đoạn</p>
        {local?.id===capture.id&&<p className="tiny muted">Thu: {local.state} · đã thu {local.capturedSeconds}s · còn {local.queued} đoạn chờ {local.error}</p>}
        <p className="tiny muted break mono">Session: {capture.id}</p>
      </div>}
    </section>

    {!fresh && <>
      {capture.warnings?.map((warning,i)=><p className="notice warn" key={i}>{warning}</p>)}
      {capture.errorCode&&<p className="notice error">{capture.errorCode} · Không tự gửi lại request lỗi. Kiểm tra cấu hình/quota; retry có thể phát sinh phí.</p>}
      {capture.status==='OPEN'&&capture.mode==='FILE'&&<section className="card"><label className="dropzone"><Icon name="upload" size={22}/><strong>{file?file.name:'Chọn lại file'}</strong>
        <input type="file" accept=".wav,.mp3,.m4a,.webm" onChange={e=>setFile(e.target.files?.[0]??null)}/></label>
        <button className="full" disabled={busy||!file} onClick={()=>action(async()=>{validateAudio(file);await api.uploadAudio(token,capture.id,0,0,file);setCapture(await api.stopCapture(token,capture.id,1));})}>Gửi lại cùng file / chốt upload</button></section>}
      {capture.sources.length>0&&<section className="card">
        <div className="section-label"><span><span className="dot"/> Lời thoại trực tiếp</span><span className="tiny muted">{capture.sources.length} đoạn</span></div>
        <div className="live-lines">{capture.sources.slice(-4).map((s,i,all)=><div key={s.segmentId} className={`live-line${i===all.length-1?' latest':''}`}>
          <span className="tiny muted">[{(s.startMs/1000).toFixed(2)}s]</span><p className="small">{s.text}</p></div>)}</div>
        <details className="evidence"><summary>Transcript STT ({capture.sources.length} đoạn)</summary>{capture.sources.map(s=><p className="small" key={s.segmentId}>{(s.startMs/1000).toFixed(2)}s — {s.text}</p>)}</details>
      </section>}
      {capture.mode==='LIVE'&&<section className="card">
        <div className="section-label"><span><Icon name="sparkle" size={14}/> Việc AI đang nghe thấy</span><span className="pill idle">{capture.proposals.length} tác vụ</span></div>
        {capture.proposals.map(p=><LiveDraft key={p.taskRef} proposal={p} override={capture.overrides[p.taskRef]} version={capture.version}
          onDirty={dirty=>{if(dirty)draftRefs.current.add(p.taskRef);else draftRefs.current.delete(p.taskRef);setDirtyCount(draftRefs.current.size);onDirty(draftRefs.current.size>0);}}
          onSave={async input=>setCapture(await api.editLiveDraft(token,capture.id,input))}/>)}
        {Object.keys(capture.overrides).some(ref=>!capture.proposals.some(p=>p.taskRef===ref))&&
          <p className="notice warn">Một số công việc bạn đã sửa không còn trong danh sách. Kiểm tra và thêm lại thủ công nếu cần.</p>}
        <p className="tiny muted center">Đề xuất tạm — bạn sẽ duyệt và sửa sau khi chốt.</p>
      </section>}
      {dirtyCount>0&&<p className="notice warn">Lưu hoặc bỏ phần sửa live đang mở trước khi chốt phiên.</p>}
    </>}
    <ErrorNotice error={error}/>{note&&<p className="notice">{note}</p>}
    <div className="action-bar">
      {fresh ? <button className="primary full big" disabled={busy||!config?.sttReady||(mode==='LIVE'&&!provider)} onClick={()=>action(create)}>
        {mode==='FILE'?<><Icon name="upload" size={16}/>Gửi file để chuyển lời nói</>:<><Icon name="mic" size={16}/>Chuẩn bị phiên live</>}</button> : actions}
    </div>
  </div>;
}
