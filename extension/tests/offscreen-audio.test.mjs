import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { runInNewContext } from 'node:vm';
import { build } from 'esbuild';

const source = await readFile(new URL('../src/offscreen.mjs', import.meta.url), 'utf8');
const bundle = await build({
  stdin: { contents: `${source}\nexport { start, stop };`, resolveDir: fileURLToPath(new URL('../src/', import.meta.url)) },
  bundle: true, write: false, format: 'iife', globalName: 'recorder',
  define: { __API_ORIGIN__: JSON.stringify('http://127.0.0.1:8080') }, logLevel: 'silent',
});

function harness() {
  const calls = [], statuses = [];
  let node;
  class AudioNode {
    port = {};
    constructor() { node = this; }
    connect() { return this; }
    disconnect() {}
  }
  class Context {
    sampleRate = 16000;
    audioWorklet = { addModule: async () => {} };
    createMediaStreamSource() { return { connect() {} }; }
    createGain() { return { gain: {}, connect() { return this; } }; }
    async resume() {}
    async close() {}
  }
  const track = { stop() {}, addEventListener() {} };
  const scope = {
    File, Float32Array, FormData, AbortController, setTimeout, clearTimeout,
    AudioContext: Context, AudioWorkletNode: AudioNode,
    navigator: { mediaDevices: { getUserMedia: async () => ({ getTracks: () => [track], getAudioTracks: () => [track] }) } },
    chrome: { runtime: {
      getURL: path => path, onMessage: { addListener() {} },
      sendMessage: async message => {
        if (message.command === 'status') statuses.push(message.status);
        return { ok: true, accessToken: 'test-token' };
      },
    } },
    fetch: async (url, options) => { calls.push({ url, options }); return { ok: true, status: 200, text: async () => '{}' }; },
  };
  runInNewContext(bundle.outputFiles[0].text, scope);
  return { recorder: scope.recorder, calls, statuses, feed: samples => node.port.onmessage({ data: samples.buffer }) };
}

async function settled() {
  // Let drain's auth, upload and status promises settle without a real clock delay.
  await new Promise(resolve => setImmediate(resolve));
}

test('silent capture uploads nothing and stops with zero chunks', async () => {
  const h = harness();
  await h.recorder.start({ id: 'capture', userId: 'user', streamId: 'tab' });
  h.feed(new Float32Array(16000 * 15));
  await settled();
  await h.recorder.stop();
  assert.equal(h.calls.length, 1);
  assert.match(h.calls[0].url, /\/stop$/);
  assert.deepEqual(JSON.parse(h.calls[0].options.body), { chunkCount: 0 });
  assert.equal(h.statuses.at(-1).capturedSeconds, 15);
  assert.equal(h.statuses.at(-1).state, 'STOPPED');
});

test('silence gaps preserve timestamps, contiguous upload sequence and a quiet final fragment', async () => {
  const h = harness(), rate = 16000;
  const sound = length => Float32Array.from({ length }, (_, i) => 0.004 * Math.sin(i * 2 * Math.PI * 220 / rate));
  await h.recorder.start({ id: 'capture', userId: 'user', streamId: 'tab' });
  h.feed(new Float32Array(rate * 28));
  h.feed(sound(rate * 28));
  await settled();
  h.feed(new Float32Array(rate * 28));
  h.feed(sound(rate / 100));
  await h.recorder.stop();
  const uploads = h.calls.filter(call => call.url.includes('/chunks/'));
  assert.equal(uploads.length, 2);
  assert.match(uploads[0].url, /\/chunks\/0\?startMs=28000$/);
  assert.match(uploads[1].url, /\/chunks\/1\?startMs=84000$/);
  assert.equal(uploads[1].options.body.get('file').size, 44 + rate / 100 * 2);
  assert.deepEqual(JSON.parse(h.calls.at(-1).options.body), { chunkCount: 2 });
  assert.equal(h.statuses.at(-1).state, 'STOPPED');
});

test('leading and trailing silence within a chunk is excluded before STT with the original time offset', async () => {
  const h = harness(), rate = 16000, samples = new Float32Array(rate * 15);
  for (let i = rate * 6; i < rate * 6.05; i++) samples[i] = 0.004 * Math.sin(i * 2 * Math.PI * 220 / rate);
  await h.recorder.start({ id: 'capture', userId: 'user', streamId: 'tab' });
  h.feed(samples);
  await settled();
  await h.recorder.stop();
  const upload = h.calls.find(call => call.url.includes('/chunks/'));
  assert.match(upload.url, /\/chunks\/0\?startMs=5800$/);
  const wav = new DataView(await upload.options.body.get('file').arrayBuffer());
  assert.ok(wav.getUint32(40, true) / 2 / rate < 0.5);
  // At 6 s in the original capture, the emitted audio still has the original sample.
  assert.equal(wav.getInt16(44 + (rate * 0.2 + 1) * 2, true), Math.round(samples[rate * 6 + 1] * 32767));
  assert.equal(h.statuses.at(-1).capturedSeconds, 15);
});
