import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {mkdtemp,rm,stat} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {startApp,publicContextFor} from '../src/bridge.mjs';
import {inboundHeaders,outboundHeaders} from '../src/proxy.mjs';

const policy={ownerUid:'1000',allowedOrigins:['https://nas.example:1024'],defaultOrigin:'https://nas.example:1024',targetHosts:['127.0.0.1'],publicProxy:{listenHost:'127.0.0.1',port:19090,origin:'https://nas.example:1025'}};
const service={id:'vaultwarden',host:'127.0.0.1',port:18080,basePath:'/',upstreamAuthority:''};
const context={origin:policy.publicProxy.origin,publicAccess:true};

test('仅信任本机连接，忽略伪造 Forwarded 和飞牛身份',()=>{
  const req={headers:{host:'nas.example:1025','x-forwarded-for':'127.0.0.1','x-trim-userid':'1000'},socket:{remoteAddress:'192.0.2.1'},method:'GET'};
  assert.throws(()=>publicContextFor(req,policy,{pathname:'/vaultwarden/'}),/本机/);
  req.socket.remoteAddress='127.0.0.1';assert.deepEqual(publicContextFor(req,policy,{pathname:'/vaultwarden/'}),context);
  const headers=outboundHeaders({...req,headers:{...req.headers,forwarded:'for=evil','x-forwarded-proto':'http','x-forwarded-prefix':'/evil',authorization:'Bearer business'}},service,context);
  assert.equal(headers.forwarded,undefined);assert.equal(headers['x-trim-userid'],undefined);
  assert.equal(headers['x-forwarded-prefix'],'/vaultwarden');assert.equal(headers['x-forwarded-proto'],'https');assert.equal(headers.authorization,'Bearer business');
});

test('后端已包含业务前缀时不重复追加，兼容根相对、绝对及相对跳转',()=>{
  for(const [location,expected] of [
    ['/login?q=1','/vaultwarden/login?q=1'],
    ['/vaultwarden/login','/vaultwarden/login'],
    ['http://127.0.0.1:18080/login','/vaultwarden/login'],
    ['https://nas.example:1025/vaultwarden/login','https://nas.example:1025/vaultwarden/login'],
    ['login','/vaultwarden/login'],
    ['https://other.example/login','https://other.example/login']
  ])assert.equal(inboundHeaders({location},service,context,'/').location,expected);
  const headers=inboundHeaders({'set-cookie':['sid=x; Path=/vaultwarden/; HttpOnly','csrf=y; Path=/; SameSite=Lax']},service,context,'/');
  assert.match(headers['set-cookie'][0],/Path=\/vaultwarden\/;/);
  assert.doesNotMatch(headers['set-cookie'][0],/vaultwarden\/vaultwarden/);
  assert.match(headers['set-cookie'][1],/Path=\/vaultwarden\/; SameSite=Lax; Secure/);
});

test('内部端口被占用时拒绝启动，不留下可误判就绪的管理 Socket',async t=>{
  const busy=http.createServer();await new Promise(resolve=>busy.listen(0,'127.0.0.1',resolve));
  const directory=await mkdtemp(join(tmpdir(),'aio-bind-test-'));
  t.after(async()=>{await new Promise(resolve=>busy.close(resolve));assert.ok(directory.startsWith(join(tmpdir(),'aio-bind-test-')));await rm(directory,{recursive:true,force:true});});
  const socketPath=join(directory,'app.sock');
  await assert.rejects(startApp({policy:{...policy,publicProxy:{...policy.publicProxy,port:busy.address().port}},dataFile:join(directory,'services.json')},socketPath),{code:'EADDRINUSE'});
  await assert.rejects(stat(socketPath),{code:'ENOENT'});
});
