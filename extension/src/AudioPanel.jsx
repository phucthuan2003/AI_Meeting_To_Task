import React, { useEffect, useRef, useState } from 'react';
import { ErrorNotice } from './components.jsx';
import { validateAudio, CAPTURE_STATE, liveDraftPatch } from './audio.mjs';
import { watchSnapshots } from './streams.mjs';
import { createStorage } from './storage.mjs';

function LiveDraft({ proposal, override, version, onSave, onDirty }) {
  const [draft,setDraft]=useState(null),[error,setError]=useState(null),[busy,setBusy]=useState(false);
  useEffect(()=>{ onDirty(Boolean(draft)); return ()=>onDirty(false); },[Boolean(draft)]);
  const displayed={...proposal,...override};
  return <div className="segment">
    <strong>{displayed.taskName}</strong><p>{displayed.assigneeRaw || 'Chưa rõ người'} · {displayed.deadlineRaw || 'Chưa rõ hạn'}</p>
    {override && <p className="small notice">Đã có bản sửa của bạn. AI tiếp tục cập nhật đề xuất gốc; phần sửa này được giữ khi chốt. Nếu AI hủy việc, cần kiểm tra lại ở bước chốt.</p>}
    <details><summary>Bằng chứng</summary>{proposal.evidence.map((e,i)=><p key={i}>{e.quote}</p>)}</details>
    {!draft ? <button onClick={()=>setDraft({ expectedVersion:version, base:{taskName:displayed.taskName,assigneeRaw:displayed.assigneeRaw??'',deadlineRaw:displayed.deadlineRaw??''}, taskName:displayed.taskName,assigneeRaw:displayed.assigneeRaw??'',deadlineRaw:displayed.deadlineRaw??'' })}>Sửa bản nháp live</button> : <form onSubmit={async e=>{
      e.preventDefault();setBusy(true);setError(null);
      try {const patch=liveDraftPatch(draft.base,draft);
        if(Object.keys(patch).length)await onSave({expectedVersion:draft.expectedVersion,taskRef:proposal.taskRef,patch});setDraft(null);}
      catch(err){setError(err);}finally{setBusy(false);}
    }}>
      <label>Tên việc<input required maxLength={255} value={draft.taskName} onChange={e=>setDraft({...draft,taskName:e.target.value})}/></label>
      <label>Người phụ trách<input maxLength={255} value={draft.assigneeRaw} onChange={e=>setDraft({...draft,assigneeRaw:e.target.value})}/></label>
      <label>Hạn nguyên văn<input maxLength={255} value={draft.deadlineRaw} onChange={e=>setDraft({...draft,deadlineRaw:e.target.value})}/></label>
      <ErrorNotice error={error}/>{error?.status===409&&<button type="button" onClick={()=>{setDraft({...draft,expectedVersion:version});setError(null);}}>Giữ bản sửa và dùng phiên bản mới nhất</button>}<button disabled={busy}>Lưu bản sửa</button><button type="button" onClick={()=>setDraft(null)}>Bỏ sửa</button>
    </form>}
  </div>;
}
export default function AudioPanel({ api, session, onOpenMeeting, onDirty = () => {} }) {
  const token=session.accessToken, origin=session.origin;
  const pointer=`capture:${origin}:${session.user.id}`;
  const [sessions,setSessions]=useState([]);
  const [config,setConfig]=useState(null),[capture,setCapture]=useState(null),[local,setLocal]=useState(null);
  const [mode,setMode]=useState('FILE'),[provider,setProvider]=useState(''),[policies,setPolicies]=useState([]);
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
  return <section className="card">
    <h2>File ghi âm / Họp trực tiếp</h2>
    <p className="small muted">Âm thanh sẽ được gửi tới Groq để chuyển thành văn bản. Live còn gửi transcript tới AI đã chọn. Chỉ bắt đầu khi bạn được phép ghi và đã thông báo cho người tham gia.</p>
    {sessions.length>0&&<label>Khôi phục phiên đã có<select value={capture?.id??''} onChange={e=>{const id=e.target.value;if(id)action(async()=>remember(await api.capture(token,id)));}}>
      <option value="">Chọn phiên</option>{sessions.map(item=><option key={item.id} value={item.id}>{item.title||item.mode} · {item.status} · {item.id.slice(0,8)}</option>)}
    </select></label>}
    {config&&!config.sttReady&&<p className="notice">Chưa cấu hình dịch vụ nhận diện giọng nói ở backend.</p>}
    {!capture || capture.status==='CANCELLED' ? <fieldset disabled={busy||!config?.sttReady}>
      <label>Nguồn<select value={mode} onChange={e=>setMode(e.target.value)}><option value="FILE">File ghi âm</option><option value="LIVE">Tab cuộc họp trong Chrome</option></select></label>
      <label>Tiêu đề<input maxLength={255} value={title} onChange={e=>setTitle(e.target.value)}/></label>
      <label>Ngày họp<input type="date" value={date} onChange={e=>setDate(e.target.value)}/></label>
      <label>Múi giờ<input value={zone} onChange={e=>setZone(e.target.value)}/></label>
      {mode==='FILE'?<><input type="file" accept=".wav,.mp3,.m4a,.webm" onChange={e=>setFile(e.target.files?.[0]??null)}/><p className="small muted">Tối đa 10 MiB và một giờ; giới hạn thời lượng được kiểm tra từ kết quả STT.</p></>:<>
        <label>AI<select value={provider} onChange={e=>setProvider(e.target.value)}><option value="">Chọn AI</option>{policies.map(p=><option key={p.providerId} value={p.providerId}>{p.displayName}</option>)}</select></label>
        <button type="button" onClick={()=>chrome.tabs.create({url:chrome.runtime.getURL('microphone.html')})}>Cấp quyền microphone</button>
        <label><input type="checkbox" checked={microphone} onChange={e=>setMicrophone(e.target.checked)}/> Kèm microphone</label>
        <p className="small muted">Mở tab họp và bấm biểu tượng extension tại tab đó trước khi bắt đầu. Thu âm tab đang mở; chưa hỗ trợ thu toàn bộ âm thanh ứng dụng desktop. Tối đa hai giờ, 480 đoạn. Đề xuất là tạm thời, không tự tạo card.</p>
      </>}
      <button disabled={busy||(mode==='LIVE'&&!provider)} onClick={()=>action(create)}>{mode==='FILE'?'Gửi file để chuyển lời nói':'Chuẩn bị phiên live'}</button>
    </fieldset>:<>
      <p><strong>{capture.status}</strong> · Đã xử lý {capture.processedSequence+1}/{capture.nextSequence} đoạn</p>
      <p className="small break">Session: {capture.id}</p>
      {capture.warnings?.map((warning,i)=><p className="notice" key={i}>{warning}</p>)}
      {capture.errorCode&&<p className="notice">{capture.errorCode} · Không tự gửi lại request lỗi. Kiểm tra cấu hình/quota; retry có thể phát sinh phí.</p>}
      {local?.id===capture.id&&<p className="small">Thu: {local.state} · đã thu {local.capturedSeconds}s · còn {local.queued} đoạn chờ {local.error}</p>}
      {capture.status==='OPEN'&&capture.mode==='FILE'&&<><input type="file" accept=".wav,.mp3,.m4a,.webm" onChange={e=>setFile(e.target.files?.[0]??null)}/><button disabled={busy||!file} onClick={()=>action(async()=>{validateAudio(file);await api.uploadAudio(token,capture.id,0,0,file);setCapture(await api.stopCapture(token,capture.id,1));})}>Gửi lại cùng file / chốt upload</button></>}
      {capture.status==='OPEN'&&capture.mode==='LIVE'&&<>
        <button disabled={busy||local?.state==='RECORDING'||capture.nextSequence>0} onClick={()=>action(begin)}>Bắt đầu thu tab đang mở</button>
        <button disabled={busy} onClick={()=>action(()=>control('stop'))}>Dừng thu & gửi đoạn cuối</button>
        <button disabled={busy} onClick={()=>action(async()=>{if(!window.confirm('Chỉ chốt các đoạn backend đã nhận? Phần audio chưa gửi có thể không được đưa vào.'))return;await control('cancel').catch(()=>{});setCapture(await api.stopCapture(token,capture.id,capture.nextSequence));})}>Chốt các đoạn đã nhận</button>
      </>}
      {local?.id===capture.id&&local?.state==='ERROR'&&<button disabled={busy} onClick={()=>action(()=>control('retry'))}>Gửi lại đoạn đang chờ</button>}
      {capture.status==='FAILED'&&<button disabled={busy} onClick={()=>action(async()=>{await control('stop').catch(()=>{});setCapture(await api.retryCapture(token,capture.id));})}>Thử lại xử lý backend</button>}
      {capture.status==='READY'&&<button disabled={busy||dirtyCount>0} onClick={()=>action(async()=>{const value=await api.publishCapture(token,capture.id);setCapture(await api.capture(token,capture.id));onOpenMeeting(value.meetingId);})}>Chốt & lưu thành meeting để review</button>}
      {dirtyCount>0&&<p className="notice">Lưu hoặc bỏ phần sửa live đang mở trước khi chốt phiên.</p>}
      {capture.meetingId&&<button onClick={()=>onOpenMeeting(capture.meetingId)}>Mở meeting đã lưu</button>}
      <button disabled={busy} onClick={()=>action(async()=>setCapture(await api.capture(token,capture.id)))}>Làm mới</button>
      {!['PUBLISHED','CANCELLED'].includes(capture.status)&&<button disabled={busy} onClick={()=>action(async()=>{if(!window.confirm('Hủy phiên và xóa audio/transcript tạm chưa lưu?'))return;await control('cancel').catch(()=>{});setCapture(await api.cancelCapture(token,capture.id));})}>Hủy phiên</button>}
      {capture.status==='PUBLISHED'&&<button onClick={()=>{setCapture(null);chrome.storage.local.remove(pointer);}}>Phiên mới</button>}
      {capture.mode==='LIVE' && Object.keys(capture.overrides).some(ref=>!capture.proposals.some(p=>p.taskRef===ref)) &&
        <p className="notice">Có bản sửa thuộc việc AI đã hủy/không còn trong danh sách. Các việc đó không tự được khôi phục khi chốt; kiểm tra và thêm thủ công nếu cần.</p>}
      <p className="small muted">Đoạn đã upload và checkpoint được lưu ở backend. Audio chưa upload chỉ ở bộ nhớ phiên thu; đóng Chrome có thể mất đoạn này. Phiên tạm hết hạn sau 24 giờ.</p>
      <details><summary>Transcript STT ({capture.sources.length} đoạn)</summary>{capture.sources.map(s=><p key={s.segmentId}>{(s.startMs/1000).toFixed(2)}s — {s.text}</p>)}</details>
      {capture.mode==='LIVE'&&<><h3>Đề xuất tạm — cần review sau khi chốt</h3>
        {capture.proposals.map(p=><LiveDraft key={p.taskRef} proposal={p} override={capture.overrides[p.taskRef]} version={capture.version}
          onDirty={dirty=>{if(dirty)draftRefs.current.add(p.taskRef);else draftRefs.current.delete(p.taskRef);setDirtyCount(draftRefs.current.size);onDirty(draftRefs.current.size>0);}}
          onSave={async input=>setCapture(await api.editLiveDraft(token,capture.id,input))}/>)}
      </>}
    </>}
    <ErrorNotice error={error}/>{note&&<p className="notice">{note}</p>}
  </section>;
}
