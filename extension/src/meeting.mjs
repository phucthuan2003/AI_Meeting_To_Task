import { ApiError } from './api.mjs';

export function assertRevision(meeting, page) {
  if (page.transcriptRevision !== meeting.transcriptRevision) {
    throw new ApiError('Input đã thay đổi ở cửa sổ khác. Tải lại meeting để xem nguồn đúng phiên bản.', { status: 409, code: 'STALE_VIEW' });
  }
  return page;
}

export async function openSavedMeeting(api, storage, session, id) {
  try {
    const meeting = await api.meeting(session.accessToken, id);
    const source = assertRevision(meeting, await api.transcript(session.accessToken, id));
    return { meeting, source };
  } catch (error) {
    if (error.status === 404) await storage.setActive(session.user.id, null);
    throw error;
  }
}
