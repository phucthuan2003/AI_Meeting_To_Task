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
export function validateFile(file) {
  if (!file) throw new Error('Chọn một file TXT hoặc DOCX.');
  if (!/\.(txt|docx)$/i.test(file.name)) throw new Error('Chỉ hỗ trợ TXT UTF-8 và DOCX.');
  if (file.size > MAX_FILE) throw new Error('File vượt 10 MiB.');
}
export function validateCredentials(email, password) {
  if (!email.trim()) throw new Error('Nhập email.');
  if (password.length < 10 || password.length > 72 || new TextEncoder().encode(password).length > 72) {
    throw new Error('Mật khẩu cần 10–72 ký tự và tối đa 72 byte UTF-8.');
  }
  return { email: email.trim(), password };
}
