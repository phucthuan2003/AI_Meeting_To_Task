import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdir, rm } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';

// Exercise React's actual rendering of untrusted API text. No Chrome/DB mock is bundled in dist.
const directory = new URL('../node_modules/.cache/ui-tests/', import.meta.url);
await mkdir(directory, { recursive: true });
const output = new URL('components.mjs', directory);
await build({ entryPoints: [fileURLToPath(new URL('./ui-entry.mjs', import.meta.url))], outfile: fileURLToPath(output),
  bundle: true, platform: 'node', format: 'esm', external: ['react'], logLevel: 'silent' });
const { MeetingDetail, History, ErrorNotice, InputForm, AnalysisJobStatus, ProviderChoice, CandidateList } = await import(output.href);
test.after(async () => rm(directory, { recursive: true, force: true }));
const injection = '<img src=x onerror="alert(1)"><script>steal()</script>';
const meeting = { meetingId: 'meeting-1', title: injection, inputVersion: 2, preview: injection,
  characterCount: 5000, segmentCount: 2, warnings: [injection], sourceExpiresAt: '2026-11-09T00:00:00Z' };
const source = { sourceAvailable: true, nextCursor: 0, segments: [{ segmentId: 's1', sequence: 0,
  text: injection, sourceText: injection, normalizedStart: 0, normalizedEnd: 10, sourceLocator: { lineIndex: 0 } }] };

test('transcript/title/warnings/error/history render as text, never executable HTML', () => {
  for (const component of [
    React.createElement(MeetingDetail, { meeting, source }),
    React.createElement(ErrorNotice, { error: { message: injection, details: [{ field: 'title', message: injection }] } }),
    React.createElement(History, { history: { nextCursor: null, items: [{ meetingId: '1', title: injection, inputVersion: 1, createdAt: '2026-10-09T00:00:00Z' }] } }),
  ]) {
    const html = renderToStaticMarkup(component);
    assert.ok(html.includes('&lt;img'));
    assert.ok(!html.includes('<img') && !html.includes('<script'));
  }
});

test('cursor zero offers next-page button; preview limit and source-unavailable are explicit', () => {
  const html = renderToStaticMarkup(React.createElement(MeetingDetail, { meeting, source }));
  assert.ok(html.includes('Xem thêm nội dung'));
  assert.ok(html.includes('4.000 ký tự'));
  const unavailable = renderToStaticMarkup(React.createElement(MeetingDetail, { meeting, source: { sourceAvailable: false } }));
  assert.ok(unavailable.includes('Nguồn hiện không còn khả dụng'));
  assert.ok(!unavailable.includes('Văn bản nguồn'));
});

test('replacement requires complete user input rather than silently writing the truncated preview', () => {
  const html = renderToStaticMarkup(React.createElement(InputForm, {
    initial: { title: 'Planning', meetingDate: '', timezone: '', transcriptText: '' }, version: 2,
  }));
  assert.ok(!html.includes('TXT / DOCX'));
  assert.match(html, /<textarea[^>]*required[^>]*><\/textarea>/);
  assert.ok(html.includes('Lưu revision mới'));
});

test('job progress reports actual counters without fabricated percentage; unavailable provider error is text', () => {
  const job = { jobId: 'job-a', inputVersion: 7, status: 'PROCESSING', stage: 'PREPARING_INPUT', preparedSegments: 2,
    completedChunks: 0, totalChunks: null, attemptCount: 1 };
  const html = renderToStaticMarkup(React.createElement(AnalysisJobStatus, { job, segmentCount: 20 }));
  assert.ok(html.includes('2 / 20 segments'));
  assert.ok(!html.includes('%') && !html.includes('Phần phân tích hoàn tất'));
  const failed = renderToStaticMarkup(React.createElement(AnalysisJobStatus, { job: { ...job, status: 'FAILED',
    error: { code: 'PROVIDER_NOT_CONFIGURED', message: injection } }, segmentCount: 20 }));
  assert.ok(failed.includes('PROVIDER_NOT_CONFIGURED') && failed.includes('&lt;img'));
  assert.ok(!failed.includes('<script'));
});

test('active job disables only input replacement while source reload remains available', () => {
  const html = renderToStaticMarkup(React.createElement(MeetingDetail, { meeting, source, inputLocked: true }));
  assert.match(html, /<button disabled="">Thay input<\/button>/);
  assert.match(html, /<button>Tải lại<\/button>/);
});

test('AI selector offers both choices, marks unconfigured providers and requires explicit selection', () => {
  const policies = [
    { providerId: 'openai', displayName: 'OpenAI', providerReady: false },
    { providerId: 'gemini', displayName: 'Gemini', providerReady: false },
  ];
  const html = renderToStaticMarkup(React.createElement(ProviderChoice, { policies, value: '', onChange() {} }));
  assert.match(html, /<option value="" disabled="" selected="">Chọn OpenAI hoặc Gemini/);
  assert.ok(html.includes('OpenAI — chưa kết nối') && html.includes('Gemini — chưa kết nối'));
  assert.equal((html.match(/<option /g) ?? []).length, 3);
  const locked = renderToStaticMarkup(React.createElement(ProviderChoice, { policies, value: 'gemini', disabled: true }));
  assert.match(locked, /<select disabled="">/);
  assert.match(locked, /<option value="gemini" selected="">/);
});

test('job provider remains visible independently of the next choice and terminal label is not repeated', () => {
  const html = renderToStaticMarkup(React.createElement(AnalysisJobStatus, { segmentCount: 2,
    job: { jobId: 'job-a', providerId: 'openai', status: 'FAILED', stage: 'FAILED', preparedSegments: 2,
      completedChunks: 0, totalChunks: 1, inputVersion: 7, attemptCount: 1 } }));
  assert.ok(html.includes('AI của job: OpenAI'));
  assert.equal((html.match(/Chưa hoàn tất/g) ?? []).length, 1);
});

test('candidates show escaped evidence, missing fields and required review without automatically creating cards', () => {
  const view={providerId:'gemini',model:'fixture-model',inputVersion:1,result:{inputTokens:10,outputTokens:20,latencyMs:100,warnings:[],candidates:[
    {taskId:'task-a',taskName:injection,assigneeRaw:null,deadlineRaw:null,priority:null,dueLocal:null,
      warnings:['ASSIGNEE_MISSING','DEADLINE_MISSING'],evidence:[{sequence:0,field:'TASK',quote:injection}]}]}};
  const html=renderToStaticMarkup(React.createElement(CandidateList,{view}));
  assert.ok(html.includes('&lt;img')&&!html.includes('<script')&&!html.includes('<img'));
  assert.ok(html.includes('Chưa rõ')&&html.includes('Chưa có')&&html.includes('Bằng chứng từ transcript'));
  assert.ok(!html.includes('<button'));assert.ok(html.includes('Chưa có thao tác duyệt/sửa'));
});

test('completed empty extraction is explicit and configured providers are not labelled as disconnected', () => {
  const html=renderToStaticMarkup(React.createElement(CandidateList,{view:{providerId:'openai',model:'fixture',inputVersion:1,result:{candidates:[],warnings:[],latencyMs:0}}}));
  assert.ok(html.includes('Không tìm thấy công việc'));
  const selector=renderToStaticMarkup(React.createElement(ProviderChoice,{value:'openai',onChange(){},policies:[{providerId:'openai',displayName:'OpenAI',providerReady:true}]}));
  assert.ok(!selector.includes('chưa kết nối'));
});
