import test from 'node:test';
import assert from 'node:assert/strict';
import {contextFor} from '../src/bridge.mjs';
import {validatePolicy} from '../src/store.mjs';
const policy=validatePolicy({ownerUid:'1000',defaultOrigin:'https://default.example',targetHosts:['127.0.0.1']});
const identity={'x-trim-userid':'1000','x-trim-isadmin':'true'};
const context=(headers={},extra={})=>contextFor({method:'POST',headers:{...identity,host:'new.example',...headers},...extra},policy,{pathname:'/app/aio/api/services',search:''});

test('私有入口不再依赖域名或本机 IP 白名单，恢复真实浏览器来源',()=>{
  for(const origin of ['http://new.example:1023','https://new.example:1024'])assert.equal(context({origin}).origin,origin);
  assert.equal(context({host:'[2001:db8::42]',origin:'https://[2001:db8::42]:1024'}).origin,'https://[2001:db8::42]:1024');
  assert.equal(context({referer:'https://new.example:8888/app/aio/'}).origin,'https://new.example:8888');
  assert.equal(context({host:'localhost',origin:'https://new.example:8888','sec-fetch-site':'same-origin'}).origin,'https://new.example:8888');
  assert.equal(context({host:'localhost',referer:'https://new.example:8888/app/aio/','sec-fetch-site':'same-origin'}).origin,'https://new.example:8888');
  assert.equal(context({host:'localhost'}).origin,policy.defaultOrigin);
  const portPolicy=validatePolicy({ownerUid:'1000',defaultOrigin:'https://default.example:1024',targetHosts:['127.0.0.1']});
  assert.equal(contextFor({method:'GET',headers:{...identity,host:'default.example'}},portPolicy,{pathname:'/app/aio/',search:''}).origin,'https://default.example:1024');
  assert.equal(context({'x-forwarded-host':'evil.example','x-forwarded-proto':'http'}).origin,'https://new.example');
});

test('移除白名单仍拒绝无效 Origin、Host 不匹配、跨站或缺失 localhost 同源证明',()=>{
  for(const headers of [
    {origin:'null'},{origin:'file://new.example'},{origin:'https://user:pass@new.example'},
    {origin:'https://new.example/path'},{origin:'https://other.example'},
    {host:'new.example:8888',origin:'https://new.example:9999'},
    {host:'localhost',origin:'https://new.example:8888'},
    {origin:'https://new.example','sec-fetch-site':'cross-site'},
    {origin:'https://new.example','sec-fetch-site':'same-site'},
    {'x-trim-userid':'1001'},{'x-trim-isadmin':'false'},
  ])assert.throws(()=>context(headers),{status:403});
});

test('WebSocket 使用同样入口与同源规则，不因取消白名单放开跨站升级',()=>{
  assert.equal(context({origin:'https://new.example:8888',upgrade:'websocket'},{method:'GET'}).origin,'https://new.example:8888');
  assert.throws(()=>context({origin:'https://other.example',upgrade:'websocket'},{method:'GET'}),{status:403});
  assert.throws(()=>context({origin:'https://new.example',upgrade:'websocket','sec-fetch-site':'cross-site'},{method:'GET'}),{status:403});
});
