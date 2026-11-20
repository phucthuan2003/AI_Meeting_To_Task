export const CAPTURE_STATE = 'aiMttCaptureState';
/** Live chunk length. Whisper pads every input to a 30 s window, so ~28 s chunks halve STT work vs 15 s (WAV limit is 30 s). */
export const CHUNK_SECONDS = 28;
// Conservative silence gate, not a speech classifier. Inspect short frames so a
// quiet word in a long silent chunk is not lost in the chunk's average volume.
export function hasAudioSignal(samples, sampleRate) {
  return audioSignalWindow(samples, sampleRate) !== null;
}

export function audioSignalWindow(samples, sampleRate) {
  const frameSize = Math.max(1, Math.round(sampleRate * 0.02));
  let first = -1, last = -1;
  for (let start = 0; start < samples.length; start += frameSize) {
    const end = Math.min(samples.length, start + frameSize), count = end - start;
    let sum = 0, squares = 0;
    for (let i = start; i < end; i++) {
      sum += samples[i]; squares += samples[i] * samples[i];
    }
    // Remove DC offset; -60 dBFS RMS only excludes near-silence.
    if (squares / count - (sum / count) ** 2 > 0.001 ** 2) {
      if (first < 0) first = start;
      last = end;
    }
  }
  if (first < 0) return null;
  // Keep context around quiet consonants at either edge; preserve the samples.
  const padding = Math.round(sampleRate * 0.2);
  return { start: Math.max(0, first - padding), end: Math.min(samples.length, last + padding) };
}

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
