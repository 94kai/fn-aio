import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {mkdtemp,rm,readFile,writeFile,stat} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {startApp} from '../src/bridge.mjs';
import {Store,validatePolicy,validateServices} from '../src/store.mjs';

const policy={ownerUid:'1000',allowedOrigins:['https://aio.example','http://127.0.0.1:5666'],defaultOrigin:'https://aio.example',targetHosts:['127.0.0.1']};
const identity={host:'aio.example','x-trim-userid':'1000','x-trim-isadmin':'true'};
async function fixture(t){
  const seen=[],remoteSockets=new Set();let streamClosed;
  const streamDone=new Promise(resolve=>streamClosed=resolve);
  const upstream=http.createServer(async(req,res)=>{
    const body=[];for await(const chunk of req)body.push(chunk);
    seen.push({url:req.url,headers:req.headers,body:Buffer.concat(body).toString()});
    if(req.url==='/base/cookie'){res.writeHead(200,{'set-cookie':['sid=abc; Path=/base/; Domain=127.0.0.1; HttpOnly','csrf=xyz; Path=/']});return res.end('cookie');}
    if(req.url==='/base/redirect'){res.writeHead(302,{location:'/base/login?next=home'});return res.end();}
    if(req.url==='/base/data'){res.writeHead(206,{'content-range':'bytes 2-4/10','content-type':'application/octet-stream','accept-ranges':'bytes'});return res.end('234');}
    if(req.url==='/base/stream'){res.writeHead(200);res.write('first');res.once('close',()=>streamClosed());return;}
    if(req.url==='/base/protected/'){res.writeHead(401);return res.end();}
    res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify(seen.at(-1)));
  });
  upstream.on('upgrade',(req,socket,head)=>{
    seen.push({url:req.url,headers:req.headers});remoteSockets.add(socket);socket.on('error',()=>{});socket.once('close',()=>remoteSockets.delete(socket));
    socket.write('HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n');if(head.length)socket.write(head);socket.on('data',data=>socket.write(data));
  });
  await new Promise(resolve=>upstream.listen(0,'127.0.0.1',resolve));
  const directory=await mkdtemp(join(tmpdir(),'aio-test-')),socketPath=join(directory,'aio.sock'),dataFile=join(directory,'services.json');
  const app=await startApp({policy,dataFile},socketPath);
  const service={id:'notes',name:'笔记服务',host:'127.0.0.1',port:upstream.address().port,basePath:'/base',upstreamAuthority:'',enabled:true};
  const request=(method,path,data,headers={})=>new Promise((resolve,reject)=>{
    const body=data===undefined?undefined:typeof data==='string'?data:JSON.stringify(data);
    const req=http.request({socketPath,path,method,agent:false,headers:{...identity,...(body===undefined?{}:{'content-type':'application/json','content-length':Buffer.byteLength(body)}),...headers}},async res=>{
      const chunks=[];for await(const chunk of res)chunks.push(chunk);const text=Buffer.concat(chunks).toString();let value;try{value=JSON.parse(text);}catch{}
      resolve({status:res.statusCode,headers:res.headers,text,value});
    });req.on('error',reject);req.end(body);
  });
  const api=(method,path,data,headers={})=>request(method,'/app/aio/api/'+path,data,{'x-aio-request':'1',origin:'https://aio.example',...headers});
  const save=(services=[service],revision=0)=>api('PUT','services',{revision,services});
  t.after(async()=>{await app.closeApp();for(const socket of remoteSockets)socket.destroy();await new Promise(resolve=>upstream.close(resolve));assert.ok(directory.startsWith(join(tmpdir(),'aio-test-')));await rm(directory,{recursive:true,force:true});});
  return {app,service,save,request,api,seen,socketPath,dataFile,directory,streamDone};
}

test('所有管理、静态和服务请求都先验证飞牛所有者管理员身份',async t=>{
  const f=await fixture(t);
  for(const path of ['/app/aio/','/app/aio/assets/app.js','/app/aio/api/health','/app/aio/s/notes/']){
    for(const headers of [{'x-trim-userid':''},{'x-trim-userid':'1001'},{'x-trim-isadmin':'false'}])assert.equal((await f.request('GET',path,undefined,headers)).status,403);
  }
  assert.equal(f.seen.length,0);
});

test('正确处理 FN localhost 内部改写，拒绝未知 Host、恶意 Origin 和跨站操作',async t=>{
  const f=await fixture(t);
  assert.equal((await f.request('GET','/app/aio/',undefined,{host:'localhost'})).status,200);
  for(const headers of [{host:'evil.example'},{origin:'https://evil.example'},{origin:'null'},{'sec-fetch-site':'cross-site'}])assert.equal((await f.request('GET','/app/aio/api/health',undefined,headers)).status,403);
  assert.equal((await f.request('GET','/app/aio/',undefined,{'sec-fetch-site':'cross-site','sec-fetch-mode':'navigate','sec-fetch-dest':'document'})).status,200);
  assert.equal((await f.request('GET','/app/aio/?x=1',undefined,{'sec-fetch-site':'cross-site','sec-fetch-mode':'navigate','sec-fetch-dest':'document'})).status,403);
});

test('独立管理页面中文、CSP、自托管资源及 HEAD 无正文',async t=>{
  const f=await fixture(t),index=await f.request('GET','/app/aio/');
  assert.equal(index.status,200);assert.match(index.text,/服务入口/);assert.match(index.headers['content-security-policy'],/script-src 'self'/);
  assert.doesNotMatch(index.text,/https?:\/\/[^< ]+\.js/);
  assert.equal((await f.request('HEAD','/app/aio/')).text,'');
  assert.equal((await f.request('GET','/app/aio')).headers.location,'/app/aio/');
  assert.equal((await f.request('GET','/app/aio/assets/app.js')).status,200);
  assert.equal((await f.request('GET','/app/aio/api/health')).value.privateOnly,true);
  assert.equal((await stat(f.socketPath)).mode&0o777,0o600);
});

test('管理 API 需要请求标记、写入 Origin 和 JSON，配置超限拒绝',async t=>{
  const f=await fixture(t);
  assert.equal((await f.request('GET','/app/aio/api/services')).status,403);
  assert.equal((await f.api('PUT','services',{revision:0,services:[]},{origin:''})).status,403);
  assert.equal((await f.api('PUT','services',{revision:0,services:[]},{'content-type':'text/plain'})).status,415);
  assert.equal((await f.api('PUT','services','{')).status,400);
  assert.equal((await f.api('PUT','services',' '.repeat(128*1024+1))).status,413);
  assert.equal((await f.api('GET','services')).value.revision,0);
});

test('配置增删改持久化、私有权限和前一版本备份',async t=>{
  const f=await fixture(t);assert.equal((await f.save()).status,200);
  assert.equal((await f.api('GET','services')).value.services[0].id,'notes');
  assert.equal((await stat(f.dataFile)).mode&0o777,0o600);
  assert.equal((await f.save([{...f.service,name:'新名称'}],1)).status,200);
  assert.equal(JSON.parse(await readFile(f.dataFile+'.previous','utf8')).services[0].name,'笔记服务');
  const store=await new Store(f.dataFile,validatePolicy(policy)).init();assert.equal(store.get().services[0].name,'新名称');
  assert.equal((await f.save([],2)).value.services.length,0);
});

test('备注兼容旧配置，支持多行持久化、导入和清空且不参与转发',async t=>{
  const f=await fixture(t);
  assert.equal((await f.save()).value.services[0].note,'');
  const note='<script>alert(1)</script>\n用途说明\t注意事项';
  assert.equal((await f.save([{...f.service,note}],1)).value.services[0].note,note);
  const store=await new Store(f.dataFile,validatePolicy(policy)).init();
  assert.equal(store.get().services[0].note,note);
  const exported=(await f.api('GET','services')).value.services;
  assert.equal((await f.save(exported,2)).value.services[0].note,note);
  assert.equal((await f.request('GET','/app/aio/s/notes/')).status,200);
  assert.equal(f.seen[0].url,'/base/');assert.doesNotMatch(JSON.stringify(f.seen[0]),/用途说明|alert\(1\)/);
  assert.equal((await f.save([{...f.service,note:''}],3)).value.services[0].note,'');
});

test('备注长度、类型及控制字符校验，拒绝时不修改已有配置',async t=>{
  const f=await fixture(t);
  assert.equal((await f.save([{...f.service,note:'备'.repeat(500)}])).status,200);
  for(const note of ['备'.repeat(501),null,42,{},[],true,'bad\u0000text','bad\u007ftext']){
    assert.equal((await f.save([{...f.service,note}],1)).status,400);
  }
  assert.equal((await f.api('GET','services')).value.revision,1);
  assert.equal((await f.save([{...f.service,note:'第一行\r\n第二行'}],1)).value.services[0].note,'第一行\n第二行');
});

test('并发写入按 revision 拒绝旧配置，不丢失已保存修改',async t=>{
  const f=await fixture(t);await f.save();
  const results=await Promise.all([f.save([{...f.service,name:'甲'}],1),f.save([{...f.service,name:'乙'}],1)]);
  assert.deepEqual(results.map(value=>value.status).sort(),[200,409]);
  assert.equal((await f.api('GET','services')).value.revision,2);
});

test('目标、端口、重复标识、目录穿越及注入型 Host 均拒绝',async t=>{
  const f=await fixture(t);
  for(const change of [{host:'evil.example'},{host:'8.8.8.8'},{host:'0.0.0.0'},{port:5666},{port:5667},{port:22},{port:0},{port:65536},{id:'../evil'},{basePath:'/a/../b'},{basePath:'//evil'},{basePath:'/a%2fb'},{upstreamAuthority:'user:password@host'},{upstreamAuthority:'host\r\nx: y'},{password:'secret'}])assert.equal((await f.save([{...f.service,...change}])).status,400,JSON.stringify(change));
  assert.equal((await f.save([f.service,f.service])).status,400);
  assert.equal((await f.api('GET','services')).value.services.length,0);assert.equal(f.seen.length,0);
});

test('服务转发剥离挂载前缀并保留基础路径、查询和请求正文',async t=>{
  const f=await fixture(t);await f.save();
  const result=await f.request('POST','/app/aio/s/notes/echo?q=abc','hello',{origin:'https://aio.example','content-type':'text/plain'});
  assert.equal(result.status,200);assert.equal(result.value.url,'/base/echo?q=abc');assert.equal(result.value.body,'hello');
  assert.equal(result.value.headers['x-forwarded-prefix'],'/app/aio/s/notes');
  assert.equal(result.value.headers['x-forwarded-host'],'aio.example');
  assert.equal(result.value.headers['x-trim-userid'],undefined);
  assert.equal((await f.request('GET','/app/aio/s/notes?x=1')).headers.location,'/app/aio/s/notes/?x=1');
});

test('Cookie 名称及路径隔离，不把飞牛或其他服务凭据交给目标',async t=>{
  const f=await fixture(t);await f.save();
  const cookie=await f.request('GET','/app/aio/s/notes/cookie');
  assert.match(cookie.headers['set-cookie'][0],/^aio_notes__sid=abc;/);
  assert.match(cookie.headers['set-cookie'][0],/Path=\/app\/aio\/s\/notes\//);
  assert.match(cookie.headers['set-cookie'][0],/HttpOnly/);assert.match(cookie.headers['set-cookie'][0],/Secure/);assert.doesNotMatch(cookie.headers['set-cookie'][0],/Domain=/);
  const result=await f.request('GET','/app/aio/s/notes/echo',undefined,{cookie:'nas-session=private; aio_notes__sid=abc; aio_other__sid=other'});
  assert.equal(result.value.headers.cookie,'sid=abc');assert.equal(result.value.headers['x-trim-isadmin'],undefined);
});

test('重定向和 Range 文件下载兼容，不整体缓冲业务正文',async t=>{
  const f=await fixture(t);await f.save();
  assert.equal((await f.request('GET','/app/aio/s/notes/redirect')).headers.location,'/app/aio/s/notes/login?next=home');
  const result=await f.request('GET','/app/aio/s/notes/data',undefined,{range:'bytes=2-4'});
  assert.equal(result.status,206);assert.equal(result.text,'234');assert.equal(result.headers['content-range'],'bytes 2-4/10');assert.equal(f.seen.at(-1).headers.range,'bytes=2-4');
});

test('路径歧义、未配置和停用服务拒绝，不触发真实转发',async t=>{
  const f=await fixture(t);await f.save([{...f.service,enabled:false}]);
  assert.equal((await f.request('GET','/app/aio/s/notes/')).status,503);
  assert.equal((await f.request('GET','/app/aio/s/other/')).status,404);
  for(const path of ['/app/aio/s/notes/../secret','/app/aio/s/notes/%2e%2e/secret','/app/aio/s/notes/%2fsecret','/app/aio/s/notes/%5csecret'])assert.equal((await f.request('GET',path)).status,400);
  assert.equal(f.seen.length,0);
});

test('管理员显式检查连接：仅 HEAD，不跟随跳转、不发送业务 Cookie',async t=>{
  const f=await fixture(t);await f.save();const result=await f.api('POST','services/notes/check',{});
  assert.equal(result.status,200);assert.equal(result.value.reachable,true);assert.equal(f.seen[0].url,'/base/');assert.equal(f.seen[0].headers.cookie,undefined);
});

test('WebSocket 双向透传，仍受身份和停用状态限制',{timeout:3000},async t=>{
  const f=await fixture(t);await f.save();
  await new Promise((resolve,reject)=>{
    const req=http.request({socketPath:f.socketPath,path:'/app/aio/s/notes/ws',headers:{...identity,origin:'https://aio.example',connection:'Upgrade',upgrade:'websocket'},agent:false});
    req.on('error',reject);req.on('response',res=>{res.resume();reject(new Error('unexpected '+res.statusCode));});
    req.on('upgrade',(res,socket)=>{socket.on('error',reject);assert.equal(res.statusCode,101);socket.once('data',data=>{try{assert.equal(data.toString(),'ping');socket.destroy();resolve();}catch(error){reject(error);}});socket.write('ping');});req.end();
  });
  assert.equal(f.seen.at(-1).url,'/base/ws');
  assert.equal((await f.request('GET','/app/aio/s/notes/ws',undefined,{connection:'Upgrade',upgrade:'websocket','x-trim-userid':'1001'})).status,403);
});

test('下载中断会取消回源，不留下持续传输',{timeout:3000},async t=>{
  const f=await fixture(t);await f.save();
  await new Promise((resolve,reject)=>{
    const req=http.request({socketPath:f.socketPath,path:'/app/aio/s/notes/stream',headers:identity,agent:false},res=>res.once('data',()=>{res.destroy();resolve();}));req.on('error',reject);req.end();
  });await f.streamDone;
});

test('损坏配置拒绝初始化，绝不覆盖原文件',async t=>{
  const f=await fixture(t);await writeFile(f.dataFile,'{broken');
  await assert.rejects(new Store(f.dataFile,validatePolicy(policy)).init(),/损坏/);assert.equal(await readFile(f.dataFile,'utf8'),'{broken');
});

test('策略和配置白名单不接受不明确的入口或任意目标',()=>{
  assert.throws(()=>validatePolicy({...policy,targetHosts:['example.com']}));
  assert.throws(()=>validatePolicy({...policy,defaultOrigin:'https://evil.example'}));
  assert.throws(()=>validateServices(Array.from({length:51},()=>({})),validatePolicy(policy)),/50/);
});
