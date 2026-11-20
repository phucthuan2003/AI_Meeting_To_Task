export const MAX_TEXT = 200000;
export const MAX_FILE = 10 * 1024 * 1024;
export function pastePayload(draft, expectedVersion) {
  if (!draft.transcriptText.trim()) throw new Error('Nhập transcript trước khi lưu.');
  if (draft.transcriptText.length > MAX_TEXT) throw new Error('Transcript vượt 200.000 ký tự UTF-16.');
  const input = {
    title: draft.title.trim() || null, meetingDate: draft.meetingDate || null,
    timezone: draft.timezone.trim() || null, transcriptText: draft.transcriptText,
    ...(expectedVersion === undefined ? {} : { expectedVersion }),
  };
  if (new TextEncoder().encode(JSON.stringify(input)).length > 1024 * 1024) throw new Error('JSON vượt 1 MiB. Hãy dùng file TXT.');
  return input;
}
/** Transcript files the backend can read (it checks the real content again). */
export const TRANSCRIPT_EXTENSIONS = ['txt', 'md', 'docx', 'doc', 'pdf', 'rtf', 'odt', 'html', 'htm', 'vtt', 'srt'];
export const TRANSCRIPT_ACCEPT = TRANSCRIPT_EXTENSIONS.map(ext => `.${ext}`).join(',');
export function validateFile(file) {
  if (!file) throw new Error('Chọn một file PDF, Word, TXT, RTF, ODT, HTML, VTT hoặc SRT.');
  const ext = file.name.includes('.') ? file.name.split('.').pop().toLowerCase() : '';
  if (!TRANSCRIPT_EXTENSIONS.includes(ext)) throw new Error('Chỉ hỗ trợ PDF, DOCX, DOC, RTF, ODT, TXT, MD, HTML, VTT và SRT.');
  if (file.size > MAX_FILE) throw new Error('File vượt 10 MiB.');
}
export function validateCredentials(email, password) {
  if (!email.trim()) throw new Error('Nhập email.');
  if (password.length < 10 || password.length > 72 || new TextEncoder().encode(password).length > 72) {
    throw new Error('Mật khẩu cần 10–72 ký tự và tối đa 72 byte UTF-8.');
  }
  return { email: email.trim(), password };
}
