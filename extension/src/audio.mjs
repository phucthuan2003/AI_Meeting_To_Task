export const CAPTURE_STATE = 'aiMttCaptureState';
export function validateAudio(file) {
  if (!file || !/\.(wav|mp3|m4a|webm)$/i.test(file.name) || file.size <= 0 || file.size > 10 * 1024 * 1024) {
    throw new Error('Chọn WAV, MP3, M4A hoặc WebM từ 1 byte tới 10 MiB; tối đa một giờ âm thanh.');
  }
}
/** Complete PCM WAV container for each chunk; avoids sending unplayable MediaRecorder fragments. */
export function wavBytes(samples, sampleRate) {
  const outputRate = 16000, size = Math.floor(samples.length * outputRate / sampleRate);
  if (!Number.isFinite(sampleRate) || sampleRate <= 0 || size > 16000 * 30) throw new Error('Đoạn audio vượt giới hạn.');
  const bytes = new ArrayBuffer(44 + size * 2), view = new DataView(bytes);
  const text = (at, value) => [...value].forEach((c, i) => view.setUint8(at + i, c.charCodeAt(0)));
  text(0,'RIFF'); view.setUint32(4,36+size*2,true); text(8,'WAVE'); text(12,'fmt '); view.setUint32(16,16,true);
  view.setUint16(20,1,true); view.setUint16(22,1,true); view.setUint32(24,outputRate,true); view.setUint32(28,outputRate*2,true);
  view.setUint16(32,2,true); view.setUint16(34,16,true); text(36,'data'); view.setUint32(40,size*2,true);
  for (let i=0;i<size;i++) {
    const at=i*sampleRate/outputRate, low=Math.floor(at), fraction=at-low;
    const sample=(samples[low]??0)*(1-fraction)+(samples[low+1]??samples[low]??0)*fraction;
    view.setInt16(44+i*2, Math.round(Math.max(-1,Math.min(1,sample))*32767),true);
  }
  return bytes;
}

// Keep only fields actually edited: changing a title must not freeze an AI-updated assignee/deadline.
export function liveDraftPatch(base, draft) {
  const patch = {};
  for (const field of ['taskName','assigneeRaw','deadlineRaw']) if (draft[field] !== base[field]) patch[field] = draft[field] || null;
  return patch;
}
