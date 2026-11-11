import http from 'node:http';
import {readFile,stat} from 'node:fs/promises';
const [socketPath='/var/apps/aio/target/app.sock',policyPath='/var/apps/aio/target/gateway.json']=process.argv.slice(2);
const policy=JSON.parse(await readFile(policyPath,'utf8'));
const identity={host:new URL(policy.defaultOrigin).host,'x-trim-userid':policy.ownerUid,'x-trim-isadmin':'true'};
function request(path,extra={}){
  return new Promise((resolve,reject)=>{
    const req=http.request({socketPath,path,headers:{...identity,...extra},agent:false},async res=>{
      let body='';for await(const chunk of res)body+=chunk;let value;try{value=JSON.parse(body);}catch{}
      resolve({status:res.statusCode,body,value});
    });req.setTimeout(5000,()=>req.destroy(new Error('诊断超时')));req.on('error',reject);req.end();
  });
}
for(const [name,path,headers,status,match]of [
  ['AIO 管理页面','/app/aio/',{},200,/服务入口/],
  ['FN localhost 入口兼容','/app/aio/',{host:'localhost'},200,/服务入口/],
  ['前端脚本可达','/app/aio/assets/app.js',{},200],
  ['独立健康状态','/app/aio/api/health',{},200],
  ['无 NAS 身份拒绝','/app/aio/',{'x-trim-userid':''},403],
  ['非所有者拒绝','/app/aio/',{'x-trim-userid':policy.ownerUid==='1001'?'1002':'1001'},403],
  ['非管理员拒绝','/app/aio/',{'x-trim-isadmin':'false'},403],
  ['新入口无需白名单但仍须已登录','/app/aio/',{host:'new.example'},200],
  ['新入口无 NAS 身份仍拒绝','/app/aio/',{host:'new.example','x-trim-userid':''},403],
  ['跨站 Origin 拒绝','/app/aio/api/health',{origin:'https://evil.example'},403],
  ['管理请求缺少标记拒绝','/app/aio/api/services',{},403]
]){
  const response=await request(path,headers);
  if(response.status!==status||(match&&!match.test(response.body)))throw new Error(`${name}：预期 ${status}，实际 ${response.status}`);
  console.log(`通过：${name}（${status}）`);
}
const config=await request('/app/aio/api/services',{'x-aio-request':'1',origin:policy.defaultOrigin});
if(config.status!==200||!Array.isArray(config.value?.services))throw new Error('服务配置读取失败');
console.log(`通过：配置只读检查，当前服务数 ${config.value.services.length}，修订号 ${config.value.revision}`);
const socket=await stat(socketPath);if((socket.mode&0o777)!==0o600||socket.uid===0)throw new Error('Socket 权限或运行所有者不符合隔离要求');
console.log('通过：Socket 权限 0600，归非 root 包用户所有');
console.log('边界：以上使用本机管理员合成网关身份，只验证私有应用；不代替真实 NAS 登录和浏览器交互验收。未改写服务配置。');
