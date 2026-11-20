import React, { useEffect, useRef, useState } from 'react';
import { ErrorNotice } from './components.jsx';
import { validateAudio, CAPTURE_STATE } from './audio.mjs';
import { watchSnapshots } from './streams.mjs';
import { createStorage } from './storage.mjs';
import Icon from './icons.jsx';

const CAPTURE_LABELS = { OPEN: 'Đang mở', DRAINING: 'Đang nhận dạng đoạn cuối', READY: 'Sẵn sàng lưu', FAILED: 'Nhận dạng lỗi', PUBLISHED: 'Đã lưu thành meeting', CANCELLED: 'Đã hủy' };
const clock = seconds => `${String(Math.floor((seconds ?? 0) / 60)).padStart(2, '0')}:${String(Math.floor((seconds ?? 0) % 60)).padStart(2, '0')}`;

export default function AudioPanel({ api, session, onOpenMeeting, onDirty = () => {}, tabs }) {
  const token=session.accessToken, origin=session.origin;
  const pointer=`capture:${origin}:${session.user.id}`;
  const [sessions,setSessions]=useState([]);
  const [config,setConfig]=useState(null),[capture,setCapture]=useState(null),[local,setLocal]=useState(null);
  const [mode,setMode]=useState('LIVE'),[provider,setProvider]=useState(''),[policies,setPolicies]=useState([]);
  const [title,setTitle]=useState(''),[date,setDate]=useState(''),[zone,setZone]=useState(Intl.DateTimeFormat().resolvedOptions().timeZone);
  const [microphone,setMicrophone]=useState(true),[file,setFile]=useState(null),[busy,setBusy]=useState(false),[error,setError]=useState(null),[note,setNote]=useState('');
  useEffect(()=>()=>onDirty(false),[]);
  // Live: the backend saves the meeting and queues one analysis job when the last chunk is transcribed.
  // Open it once, only for a transition seen in this panel (restoring an old published session does not jump).
  const lastStatus=useRef(null);
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
  useEffect(()=>{
    const before=lastStatus.current; lastStatus.current=capture ? `${capture.id}:${capture.status}` : null;
    if(capture?.mode==='LIVE'&&capture.status==='PUBLISHED'&&capture.meetingId&&before&&before.startsWith(capture.id+':')&&before!==`${capture.id}:PUBLISHED`)
      onOpenMeeting(capture.meetingId);
  },[capture?.id,capture?.status,capture?.meetingId]);
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
      if (recording) actions.push(<button key="stop" className="primary full big" disabled={busy} onClick={()=>action(()=>control('stop'))}><Icon name="stop" size={16}/>Dừng thu & phân tích</button>);
      else actions.push(<button key="start" className="primary full big" disabled={busy||capture.nextSequence>0} onClick={()=>action(begin)}><Icon name="mic" size={16}/>Bắt đầu thu tab đang mở</button>);
      if (!recording && capture.nextSequence>0) actions.push(<button key="stop2" className="full" disabled={busy} onClick={()=>action(()=>control('stop'))}>Dừng thu & gửi đoạn cuối</button>);
      actions.push(<button key="seal" className="quiet full" disabled={busy} onClick={()=>action(async()=>{if(!window.confirm('Chỉ chốt các đoạn backend đã nhận? Phần audio chưa gửi có thể không được đưa vào.'))return;await control('cancel').catch(()=>{});setCapture(await api.stopCapture(token,capture.id,capture.nextSequence));})}>Chốt các đoạn đã nhận</button>);
    }
    if(local?.id===capture.id&&local?.state==='ERROR') actions.push(<button key="resend" className="full" disabled={busy} onClick={()=>action(()=>control('retry'))}>Gửi lại đoạn đang chờ</button>);
    if(capture.status==='FAILED'&&capture.sources.length>0) actions.push(<button key="save-partial" className="primary full big" disabled={busy} onClick={()=>action(async()=>{
      if(!window.confirm('Lưu phần transcript đã nhận dạng thành meeting? Các đoạn audio chưa nhận dạng sẽ bị bỏ.'))return;
      await control('cancel').catch(()=>{});const value=await api.publishCapture(token,capture.id);setCapture(await api.capture(token,capture.id));if(capture.mode!=='LIVE')onOpenMeeting(value.meetingId);})}>
      <Icon name="check" size={16}/>Lưu phần transcript đã có</button>);
    if(capture.status==='FAILED') actions.push(<button key="retry" className={capture.sources.length>0?'full':'primary full big'} disabled={busy} onClick={()=>action(async()=>{await control('stop').catch(()=>{});setCapture(await api.retryCapture(token,capture.id));})}>Thử lại nhận dạng</button>);
    if(capture.status==='READY') actions.push(<button key="publish" className="primary full big" disabled={busy} onClick={()=>action(async()=>{const value=await api.publishCapture(token,capture.id);setCapture(await api.capture(token,capture.id));if(capture.mode!=='LIVE')onOpenMeeting(value.meetingId);})}><Icon name="sparkle" size={16}/>{capture.mode==='LIVE'?'Lưu transcript & phân tích':'Lưu thành meeting để phân tích'}</button>);
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
          <label>Dịch vụ phân tích (chạy một lần sau khi dừng)<select value={provider} onChange={e=>setProvider(e.target.value)}><option value="">Chọn dịch vụ</option>{policies.map(p=><option key={p.providerId} value={p.providerId}>{p.displayName}</option>)}</select></label>
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
        <p className="small"><strong>{capture.status}</strong> · Đã nhận dạng {capture.processedSequence+1}/{capture.nextSequence} đoạn</p>
        {capture.pendingChunks>0&&<p className="tiny muted">Đang chờ nhận dạng {capture.pendingChunks} đoạn{capture.pendingChunks>2?' · máy chủ đang bận, transcript sẽ có sau ít phút':''}</p>}
        {local?.id===capture.id&&<p className="tiny muted">Thu: {local.state} · đã thu {local.capturedSeconds}s · còn {local.queued} đoạn chờ {local.error}</p>}
        <p className="tiny muted break mono">Session: {capture.id}</p>
      </div>}
    </section>

    {!fresh && <>
      {capture.errorCode&&<p className="notice error">{capture.errorCode} · Nhận dạng giọng nói bị lỗi sau nhiều lần thử. Transcript đã nhận dạng vẫn được giữ: thử lại{capture.sources.length>0?' hoặc lưu phần đã có':''}.</p>}
      {capture.status==='READY'&&capture.sources.length===0&&<p className="notice warn">Không nhận dạng được lời nói nào. Kiểm tra âm thanh tab/micro rồi tạo phiên mới.</p>}
      {capture.status==='OPEN'&&capture.mode==='FILE'&&<section className="card"><label className="dropzone"><Icon name="upload" size={22}/><strong>{file?file.name:'Chọn lại file'}</strong>
        <input type="file" accept=".wav,.mp3,.m4a,.webm" onChange={e=>setFile(e.target.files?.[0]??null)}/></label>
        <button className="full" disabled={busy||!file} onClick={()=>action(async()=>{validateAudio(file);await api.uploadAudio(token,capture.id,0,0,file);setCapture(await api.stopCapture(token,capture.id,1));})}>Gửi lại cùng file / chốt upload</button></section>}
      {capture.sources.length>0&&<section className="card">
        <div className="section-label"><span><span className="dot"/> Lời thoại trực tiếp</span><span className="tiny muted">{capture.sources.length} đoạn</span></div>
        <div className="live-lines">{capture.sources.slice(-6).map((s,i,all)=><div key={s.segmentId} className={`live-line${i===all.length-1?' latest':''}`}>
          <span className="tiny muted">[{(s.startMs/1000).toFixed(2)}s]</span><p className="small">{s.text}</p></div>)}</div>
        <details className="evidence"><summary>Transcript STT ({capture.sources.length} đoạn)</summary>{capture.sources.map(s=><p className="small" key={s.segmentId}>{(s.startMs/1000).toFixed(2)}s — {s.text}</p>)}</details>
      </section>}
      {capture.mode==='LIVE'&&!['PUBLISHED','CANCELLED'].includes(capture.status)&&<section className="card">
        <div className="section-label"><span><Icon name="sparkle" size={14}/> Phân tích công việc</span><span className="pill idle">Sau khi dừng</span></div>
        <p className="small muted">Trong lúc họp chỉ chuyển lời nói thành văn bản. Khi bạn dừng thu, transcript được lưu thành meeting và AI phân tích một lần để tạo danh sách việc.</p>
      </section>}
    </>}
    <ErrorNotice error={error}/>{note&&<p className="notice">{note}</p>}
    <div className="action-bar">
      {fresh ? <button className="primary full big" disabled={busy||!config?.sttReady||(mode==='LIVE'&&!provider)} onClick={()=>action(create)}>
        {mode==='FILE'?<><Icon name="upload" size={16}/>Gửi file để chuyển lời nói</>:<><Icon name="mic" size={16}/>Chuẩn bị phiên live</>}</button> : actions}
    </div>
  </div>;
}
