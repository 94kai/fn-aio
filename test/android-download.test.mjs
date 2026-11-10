import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

const root=new URL('../',import.meta.url);
const script=await readFile(new URL('android/app/src/main/assets/download-capture.js',root),'utf8');
function page(limit=256*1024*1024){
  let click;
  let next=0;
  const revoked=[];
  const created=new Map();
  const timers=[];
  const listeners=new Set();
  class ObjectURL extends URL {}
  ObjectURL.createObjectURL=blob=>{const url=`blob:https://nas.example/${++next}`;created.set(url,blob);return url;};
  ObjectURL.revokeObjectURL=url=>{revoked.push(url);created.delete(url);};
  class Reader {
    readAsDataURL(blob){blob.arrayBuffer().then(bytes=>{this.result=`data:;base64,${Buffer.from(bytes).toString('base64')}`;this.onload();});}
  }
  const context={URL:ObjectURL,Blob,FileReader:Reader,location:{origin:'https://nas.example'},setTimeout:(fn,ms)=>{timers.push({fn,ms});},document:{addEventListener:(type,fn)=>{if(type==='click'){click=fn;listeners.add(fn);}},removeEventListener:(type,fn)=>{if(type==='click')listeners.delete(fn);}},fetch:async url=>{
    if(url.startsWith('data:'))return fetch(url);
    const blob=created.get(url); if(!blob)throw new Error('revoked');
    return{ok:true,blob:async()=>blob};
  }};
  context.window=context;
  vm.createContext(context);vm.runInContext(`${script}('test_download',${limit},65536)`,context);
  return {context,api:context.test_download,revoked,timers,listeners,click:(href,download)=>{
    let prevented=false,stopped=false;
    click({target:{closest:()=>({href,download})},preventDefault(){prevented=true;},stopImmediatePropagation(){stopped=true;}});
    return {prevented,stopped};
  }};
}
async function settle(){for(let i=0;i<8;i++)await Promise.resolve();}
test('blob 导出在立即 revoke 后仍可读取，64 KiB 分块不丢字节',async()=>{
  const p=page();
  const bytes=Buffer.alloc(150000); for(let i=0;i<bytes.length;i++)bytes[i]=i%251;
  const url=p.context.URL.createObjectURL(new Blob([bytes],{type:'application/octet-stream'}));
  assert.deepEqual(p.click(url,'report.bin'),{prevented:true,stopped:true});
  p.context.URL.revokeObjectURL(url);
  await settle();
  const meta=p.api.info();assert.equal(meta.state,'ready');assert.equal(meta.size,bytes.length);assert.equal(meta.name,'report.bin');
  const parts=[];
  for(let offset=0;offset<bytes.length;offset+=65536){
    assert.equal(p.api.read(meta.id,offset),true);await settle();
    const chunk=p.api.take(meta.id);assert.equal(chunk.offset,offset);
    parts.push(Buffer.from(chunk.data,'base64'));assert.equal(p.api.take(meta.id),null);
  }
  assert.deepEqual(Buffer.concat(parts),bytes);
  assert.deepEqual(p.revoked,[url]);
  p.api.release(meta.id);assert.equal(p.api.info(),null);
});
test('data URL 导出保留中文内容，普通 HTTPS 导航不被 blob 钩子抢占',async()=>{
  const p=page();
  const url='data:text/plain;base64,'+Buffer.from('你好，下载！').toString('base64');
  assert.equal(p.api.prepare(url,'中文.txt'),true);await new Promise(resolve=>setImmediate(resolve));await settle();
  const meta=p.api.info();assert.equal(meta.state,'ready');
  assert.equal(p.api.read(meta.id,0),true);await settle();
  assert.equal(Buffer.from(p.api.take(meta.id).data,'base64').toString(),'你好，下载！');
  assert.deepEqual(p.click('https://nas.example/app/aio/s/dsh/file','file.pdf'),{prevented:false,stopped:false});
});
test('拒绝跨源 blob、任意 scheme、过大文件与并发请求，空文件可保存',async()=>{
  const p=page(4);
  for(const url of ['blob:https://evil.example/id','blob:null/id','javascript:alert(1)','file:///etc/passwd','https://nas.example/file'])assert.equal(p.api.prepare(url,'x'),false);
  assert.equal(p.api.prepare('data:,'+'a'.repeat(8*1024*1024),'x'),false);
  const big=p.context.URL.createObjectURL(new Blob(['12345']));
  assert.equal(p.api.prepare(big,'big'),true);await settle();assert.equal(p.api.info().state,'error');
  p.api.release(p.api.info().id);
  const empty=p.context.URL.createObjectURL(new Blob([]));
  assert.equal(p.api.prepare(empty,'empty'),true);assert.equal(p.api.prepare(empty,'duplicate'),false);
  await settle();assert.equal(p.api.info().size,0);assert.equal(p.api.info().state,'ready');
  assert.equal(p.api.read('wrong',0),false);assert.equal(p.api.read(p.api.info().id,0),false);
});
test('分块请求校验偏移与背压；超时后不会被迟到的 fetch 结果复活',async()=>{
  const p=page();
  const url=p.context.URL.createObjectURL(new Blob(['hello']));
  p.api.prepare(url,'hello.txt');await settle();const {id}=p.api.info();
  for(const offset of [-1,0.1,5,NaN])assert.equal(p.api.read(id,offset),false);
  assert.equal(p.api.read(id,0),true);assert.equal(p.api.read(id,0),false);await settle();
  assert.equal(p.api.read(id,0),false);assert.equal(Buffer.from(p.api.take(id).data,'base64').toString(),'hello');
  p.api.release(id);
  let resolve;
  p.context.fetch=()=>new Promise(done=>{resolve=done;});
  assert.equal(p.api.prepare('blob:https://nas.example/slow','slow'),true);
  for(const timer of p.timers.filter(timer=>timer.ms===15000))timer.fn();
  assert.equal(p.api.info().state,'error');
  resolve({ok:true,blob:async()=>new Blob(['late'])});await settle();assert.equal(p.api.info().state,'error');
});
test('下载导航留在旧文档时，重新安装钩子不重复劫持点击',async()=>{
  const p=page();
  assert.equal(p.listeners.size,1);
  vm.runInContext(`${script}('replacement',268435456,65536)`,p.context);
  assert.equal(p.listeners.size,1);
  assert.equal(p.context.test_download,undefined);
  const url=p.context.URL.createObjectURL(new Blob(['new']));
  p.click(url,'new.txt');await settle();
  assert.equal(p.context.replacement.info().name,'new.txt');
  assert.equal(p.api.info(),null);
});
test('下载使用用户保存器、同服务 Cookie 和受限重定向，无原生 JS 桥或广泛存储权限',async()=>{
  const native=await readFile(new URL('android/app/src/main/java/top/fnaio/direct/WebDownloads.java',root),'utf8');
  const manifest=await readFile(new URL('android/app/src/main/AndroidManifest.xml',root),'utf8');
  assert.match(native,/setDownloadListener/);
  assert.match(native,/Intent\.ACTION_CREATE_DOCUMENT/);
  assert.match(native,/CookieManager\.getInstance\(\)\.getCookie\(next\)/);
  assert.match(native,/setInstanceFollowRedirects\(false\)/);
  assert.match(native,/DownloadPolicy\.serviceUrl\(task\.route, next\)/);
  assert.match(native,/MAX_BYTES/);
  assert.match(native,/File\.createTempFile/);
  assert.match(native,/task\.document == document/);
  assert.doesNotMatch(native,/\.addJavascriptInterface\(|Authorization"|DocumentsContract\.deleteDocument/);
  assert.doesNotMatch(manifest,/WRITE_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE|READ_MEDIA_/);
});
