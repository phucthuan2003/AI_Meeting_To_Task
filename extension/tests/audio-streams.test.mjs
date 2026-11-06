import test from 'node:test';
import assert from 'node:assert/strict';
import { wavBytes, validateAudio, liveDraftPatch } from '../src/audio.mjs';
import { createSseParser, watchSnapshots } from '../src/streams.mjs';
import { createApi } from '../src/api.mjs';

test('WAV chunks are independent RIFF/PCM files with correct sizes and bounded samples',()=>{
  for(let chunk=0;chunk<2;chunk++){
    const bytes=wavBytes(new Float32Array(48000).fill(2),48000),v=new DataView(bytes);
    assert.equal(new TextDecoder().decode(bytes.slice(0,4)),'RIFF');
    assert.equal(new TextDecoder().decode(bytes.slice(8,12)),'WAVE');
    assert.equal(v.getUint32(24,true),16000);assert.equal(v.getUint32(40,true),32000);
    assert.equal(v.getInt16(44,true),32767);assert.equal(bytes.byteLength,32044);
  }
  assert.throws(()=>wavBytes(new Float32Array(1),0));
  assert.throws(()=>validateAudio({name:'a.txt',size:4}));
  assert.throws(()=>validateAudio({name:'a.wav',size:11*1024*1024}));
});
test('SSE parser handles fragmented frames, CRLF, multiline data and heartbeat comments',()=>{
  const events=[],parse=createSseParser(e=>events.push(e));
  parse(': heartbeat\r\n\r\nevent: snap');parse('shot\r\nid: 7\r\ndata: {"text":');parse('"Việt Nam"}\r\n\r\n');
  assert.deepEqual(events,[{type:'snapshot',id:'7',data:'{"text":"Việt Nam"}'}]);
  parse('data: a\ndata: b\n\n');assert.equal(events[1].data,'a\nb');
  assert.throws(()=>parse('x'.repeat(2*1024*1024+1)),/giới hạn/);
});
test('SSE uses Bearer header, never URL token, and stops on expired auth',async()=>{
  let call,unauthorized=false,done;
  const finished=new Promise(resolve=>done=resolve);
  const watcher=watchSnapshots('https://api.test','/api/v1/captures/id/events','secret',{
    fetchImpl:async(url,options)=>{call={url,options};return new Response('event: auth-expired\ndata: {}\n\n',{headers:{'Content-Type':'text/event-stream'}});},
    onSnapshot:()=>assert.fail('no snapshot'),onUnauthorized:()=>unauthorized=true,onError:(error,{willRetry})=>{assert.equal(willRetry,false);done();},
  });
  await finished;watcher.stop();assert.equal(unauthorized,true);assert.equal(call.options.headers.Authorization,'Bearer secret');assert.equal(call.url.includes('secret'),false);
});
test('audio write calls retain explicit sequence/count/key and never retry a lost response',async()=>{
  let count=0,calls=[];
  const api=createApi('https://api.test',{fetchImpl:async(url,options)=>{calls.push({url,options});count++;if(count===1)throw new Error('lost');return new Response('{}');}});
  await assert.rejects(()=>api.uploadAudio('token','id',2,30000,new File(['x'],'a.wav')));
  assert.equal(count,1);assert.match(calls[0].url,/chunks\/2\?startMs=30000/);
  await api.stopCapture('token','id',3);assert.deepEqual(JSON.parse(calls[1].options.body),{chunkCount:3});
});

test('a title edit does not freeze an untouched assignee/deadline in live overrides',()=>{
  const base={taskName:'API',assigneeRaw:'Mai',deadlineRaw:'ngày mai'};
  assert.deepEqual(liveDraftPatch(base,{...base,taskName:'API đã sửa'}),{taskName:'API đã sửa'});
  assert.deepEqual(liveDraftPatch(base,{...base,assigneeRaw:''}),{assigneeRaw:null});
  assert.deepEqual(liveDraftPatch(base,base),{});
});
