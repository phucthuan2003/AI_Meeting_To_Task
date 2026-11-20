import { createApi } from './api.mjs';
import { wavBytes, audioSignalWindow, CHUNK_SECONDS } from './audio.mjs';
const origin = __API_ORIGIN__, api = createApi(origin);
let current = null;
async function storage(command, fields) {
  const result = await chrome.runtime.sendMessage({ target: 'capture-storage', command, ...fields });
  if (!result?.ok) throw new Error(result?.error || 'Không đọc được trạng thái phiên thu.');
  return result;
}
async function status(extra = {}) {
  if (!current) return;
  Object.assign(current, extra);
  await storage('status', { status: {
    id: current.id, userId: current.userId, origin, state: current.state, error: current.error ?? '',
    queued: current.queue.length, sent: current.sent, capturedSeconds: Math.round(current.total / current.rate),
  } });
}
async function auth() {
  return (await storage('auth', { userId: current.userId, origin })).accessToken;
}
function closeTracks() {
  if (!current) return;
  current.node?.disconnect();
  current.streams.forEach(stream => stream.getTracks().forEach(track => track.stop()));
  current.context?.close().catch(() => {});
}
function flush() {
  if (!current.samples.length) return;
  const samples = new Float32Array(current.length); let at = 0;
  for (const part of current.samples) { samples.set(part, at); at += part.length; }
  // Keep total capture time even when silence is skipped. Only uploaded chunks
  // consume a sequence number, preserving retries, STOP counts and timestamps.
  const signal = audioSignalWindow(samples, current.rate);
  if (signal) {
    current.queue.push({ sequence: current.sequence++, startMs: Math.round((current.total-current.length+signal.start)*1000/current.rate),
      file: new File([wavBytes(samples.subarray(signal.start, signal.end),current.rate)], 'chunk.wav', { type: 'audio/wav' }) });
  }
  current.samples=[]; current.length=0;
}
async function drain() {
  if (!current || current.sending || current.state === 'ERROR') return;
  current.sending=true;
  try {
    while (current.queue.length) {
      const part=current.queue[0];
      await api.uploadAudio(await auth(),current.id,part.sequence,part.startMs,part.file);
      current.queue.shift(); current.sent++; await status();
    }
    if (current.state === 'STOPPING') {
      await api.stopCapture(await auth(),current.id,current.sequence); await status({ state:'STOPPED',error:'' });
    }
  } catch (error) {
    // Keep the head and its sequence: a retry is idempotent even when the response was lost.
    closeTracks(); flush(); await status({ state:'ERROR',error:error.message });
  } finally { if(current) current.sending=false; }
}
async function start(message) {
  if(current && !['STOPPED','CANCELLED'].includes(current.state)) throw new Error('Dừng hoặc hủy phiên đang thu trước.');
  current={ id:message.id,userId:message.userId,state:'STARTING',streams:[],queue:[],samples:[],length:0,total:0,sequence:0,sent:0,rate:48000 };
  try {
    const tab = await navigator.mediaDevices.getUserMedia({ audio:{ mandatory:{ chromeMediaSource:'tab',chromeMediaSourceId:message.streamId } }, video:false });
    current.streams.push(tab);
    const context=new AudioContext();current.context=context;current.rate=context.sampleRate;
    await context.audioWorklet.addModule(chrome.runtime.getURL('pcm-processor.js'));
    const node=new AudioWorkletNode(context,'aimtt-pcm');current.node=node;
    const source=context.createMediaStreamSource(tab);source.connect(node);source.connect(context.destination); // preserve playback of captured tab
    if(message.microphone) {
      const mic=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true},video:false});
      current.streams.push(mic);context.createMediaStreamSource(mic).connect(node);
    }
    const silent=context.createGain();silent.gain.value=0;node.connect(silent).connect(context.destination);
    tab.getAudioTracks()[0].addEventListener('ended',()=>stop().catch(()=>{}));
    node.port.onmessage=event=>{
      if(current?.state!=='RECORDING')return;
      const part=new Float32Array(event.data);current.samples.push(part);current.length+=part.length;current.total+=part.length;
      // ~28 s chunks: Whisper pads every input to a 30 s window, so 15 s chunks cost almost as much as 30 s ones.
      if(current.length>=current.rate*CHUNK_SECONDS) {flush();status().catch(()=>{});drain();}
      if(current.queue.length>=8||current.total>=current.rate*7200) stop().catch(()=>{});
    };
    await context.resume();await status({state:'RECORDING'});
  } catch(error) {closeTracks(); await status({state:'ERROR',error:'Không thu được tab/microphone. Kiểm tra quyền hoặc dùng file audio. '+error.name});throw error;}
}
async function stop() {
  if(!current || ['STOPPED','CANCELLED'].includes(current.state))return;
  current.state='STOPPING';closeTracks();flush();await status();await drain();
}
chrome.runtime.onMessage.addListener((message,sender,reply)=>{
  if(sender.id!==chrome.runtime.id || message.target!=='capture-offscreen')return;
  (async()=>{
    if (message.command === 'session-changed') {
      if (current?.state === 'RECORDING' && (message.userId !== current.userId
          || message.origin !== origin || !(Date.parse(message.expiresAt) > Date.now()))) await stop();
      return { ok: true };
    }
    if(message.command!=='start' && (!current || message.id!==current.id))throw new Error('Phiên thu cục bộ không khớp.');
    if(message.command==='start')await start(message);
    if(message.command==='stop')await stop();
    if(message.command==='retry'&&current){await status({state:'STOPPING',error:''});await drain();}
    if(message.command==='cancel'&&current){closeTracks();current.queue=[];current.samples=[];await status({state:'CANCELLED'});}
    return {ok:true};
  })().then(reply,error=>reply({ok:false,error:error.message}));
  return true;
});
