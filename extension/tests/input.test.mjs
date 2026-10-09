import test from 'node:test';
import assert from 'node:assert/strict';
import { pastePayload, validateFile, validateCredentials, MAX_TEXT, MAX_FILE } from '../src/input.mjs';
import { backendOrigin, extensionManifest } from '../src/config.mjs';
const draft = { title: '', meetingDate: '', timezone: '', transcriptText: 'Nam: Không tạo card trước khi duyệt.' };

test('PATCH includes complete nullable metadata and returned expectedVersion', () => {
  assert.deepEqual(pastePayload(draft, 7), { title: null, meetingDate: null, timezone: null, transcriptText: draft.transcriptText, expectedVersion: 7 });
  assert.equal(Object.hasOwn(pastePayload(draft), 'expectedVersion'), false);
});

test('UTF-16 text and UTF-8 JSON limits mirror backend boundaries', () => {
  assert.equal(pastePayload({ ...draft, transcriptText: '😀'.repeat(MAX_TEXT / 2) }).transcriptText.length, MAX_TEXT);
  assert.throws(() => pastePayload({ ...draft, transcriptText: '😀'.repeat(MAX_TEXT / 2 + 1) }), /200.000/);
  assert.throws(() => pastePayload({ ...draft, transcriptText: '\u0001'.repeat(MAX_TEXT) }), /1 MiB/);
  assert.throws(() => pastePayload({ ...draft, transcriptText: '  \n ' }), /Nhập transcript/);
});

test('file precheck rejects unsupported suffix/oversize; backend still validates actual content', () => {
  validateFile({ name: 'Meeting.DOCX', size: MAX_FILE });
  assert.throws(() => validateFile({ name: 'meeting.docm', size: 5 }), /Chỉ hỗ trợ/);
  assert.throws(() => validateFile({ name: 'meeting.txt', size: MAX_FILE + 1 }), /10 MiB/);
});

test('password enforces UTF-8 byte limit, not just character count', () => {
  validateCredentials('a@example.com', 'a'.repeat(72));
  assert.throws(() => validateCredentials('a@example.com', '😀'.repeat(19)), /72 byte/);
});

test('manifest only grants backend host, storage and sidePanel; production forbids plain HTTP', () => {
  const manifest = extensionManifest('https://api.example.com');
  assert.deepEqual(manifest.permissions, ['storage', 'sidePanel']);
  assert.deepEqual(manifest.host_permissions, ['https://api.example.com/*']);
  assert.equal(manifest.content_scripts, undefined);
  assert.match(manifest.content_security_policy.extension_pages, /connect-src https:\/\/api.example.com/);
  assert.equal(backendOrigin('http://localhost:18080'), 'http://localhost:18080');
  for (const invalid of ['http://api.example.com', 'https://name:password@api.example.com', 'https://api.example.com/v1', 'https://api.example.com?query=1']) {
    assert.throws(() => backendOrigin(invalid));
  }
});
