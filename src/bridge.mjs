import http from 'node:http';
import {readFile,lstat,chmod} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {Store,validatePolicy,authority,fail} from './store.mjs';
import {ROOT,parseTarget,mount,destination,createProxy} from './proxy.mjs';

const CSP="default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; form-action 'self'; base-uri 'self'; frame-ancestors 'self'";
export function contextFor(req,policy,target){
  if(req.headers['x-trim-userid']!==policy.ownerUid||req.headers['x-trim-isadmin']!=='true')throw fail('仅允许已登录飞牛的指定所有者管理员访问',403);
  let raw;try{raw=authority(req.headers.host);}catch{throw fail('入口 Host 无效',400);}
  let candidate;
  if(req.headers.origin!==undefined){
    if(typeof req.headers.origin!=='string'||!policy.origins.has(req.headers.origin))throw fail('请求来源不在允许列表',403);
    candidate=req.headers.origin;
  }else if(typeof req.headers.referer==='string'){
    try{const value=new URL(req.headers.referer).origin;if(policy.origins.has(value))candidate=value;}catch{}
  }
  let origin;
  if(raw.host==='localhost')origin=candidate??policy.defaultOrigin;
  else{
    const matching=[...policy.origins].filter(value=>{
      const url=new URL(value);if(url.hostname!==raw.hostname)return false;
      return !raw.port||new URL(`${url.protocol}//${req.headers.host}`).host===url.host;
    });
    if(!matching.length)throw fail('入口 Host 不在允许列表',403);
    if(candidate&&!matching.includes(candidate))throw fail('Origin 与入口不一致',403);
    origin=candidate??matching[0];
  }
  const landing=req.method==='GET'&&!req.headers.upgrade&&target.pathname===ROOT+'/'&&!target.search&&req.headers['sec-fetch-mode']==='navigate'&&req.headers['sec-fetch-dest']==='document';
  if(req.headers['sec-fetch-site']==='cross-site'&&!landing)throw fail('拒绝跨站访问',403);
  return {origin};
}
function apiAdmission(req,policy){
  if(req.headers['x-aio-request']!=='1')throw fail('缺少同源管理请求标记',403);
  if(req.method!=='GET'&&req.method!=='HEAD'){
    if(!policy.origins.has(req.headers.origin))throw fail('管理写入需要同源 Origin',403);
    if(!/^application\/json(?:;|$)/i.test(req.headers['content-type']??''))throw fail('管理写入需要 JSON',415);
  }
}
async function jsonBody(req){
  const limit=128*1024,length=req.headers['content-length'];
  if(length!==undefined&&(!/^\d+$/.test(length)||Number(length)>limit)){req.resume();throw fail('请求体过大或长度无效',413);}
  let size=0;const chunks=[];
  for await(const chunk of req){size+=chunk.length;if(size>limit)throw fail('请求体过大',413);chunks.push(chunk);}
  try{const value=JSON.parse(Buffer.concat(chunks).toString('utf8'));if(!value||typeof value!=='object'||Array.isArray(value))throw new Error();return value;}catch{throw fail('JSON 格式无效');}
}
function json(res,value,status=200){res.writeHead(status,{'content-type':'application/json; charset=utf-8','cache-control':'no-store','x-content-type-options':'nosniff'});res.end(JSON.stringify(value));}
function reject(res,error){
  if(res.headersSent){res.destroy();return;}
  const known=Number.isInteger(error.status)&&error.status>=400&&error.status<600;
  json(res,{error:known?error.message:'应用暂时不可用'},known?error.status:500);
}
export async function createApp({policy:input,dataFile,assetsRoot=new URL('./web/',import.meta.url)}={}){
  const policy=validatePolicy(input),store=await new Store(dataFile,policy).init(),proxy=createProxy(),sockets=new Set();
  const assets=new Map();
  for(const [path,file,type] of [[ROOT+'/','index.html','text/html; charset=utf-8'],[ROOT+'/assets/app.js','app.js','text/javascript; charset=utf-8'],[ROOT+'/assets/style.css','style.css','text/css; charset=utf-8']]){
    const source=assetsRoot instanceof URL?new URL(file,assetsRoot):join(assetsRoot,file);
    assets.set(path,{body:await readFile(source),type});
  }
  async function handle(req,res){
    const target=parseTarget(req.url),context=contextFor(req,policy,target);
    if(target.pathname===ROOT){res.writeHead(308,{location:ROOT+'/','cache-control':'no-store'});res.end();return;}
    if(target.pathname===ROOT+'/api/health'){
      if(!['GET','HEAD'].includes(req.method))throw fail('方法不支持',405);
      json(res,{name:'AIO',version:'0.1.1',privateOnly:true});return;
    }
    if(target.pathname===ROOT+'/api/services'){
      apiAdmission(req,policy);
      if(req.method==='GET'){json(res,{...store.get(),policy:{targetHosts:[...policy.targetHosts]}});return;}
      if(req.method==='PUT'){json(res,await store.save(await jsonBody(req)));return;}
      throw fail('方法不支持',405);
    }
    const check=/^\/app\/aio\/api\/services\/([a-z][a-z0-9-]{0,31})\/check$/.exec(target.pathname);
    if(check){
      apiAdmission(req,policy);if(req.method!=='POST')throw fail('方法不支持',405);
      await jsonBody(req);const service=store.get().services.find(item=>item.id===check[1]);if(!service)throw fail('服务不存在',404);
      json(res,await proxy.check(service));return;
    }
    const resource=assets.get(target.pathname);
    if(resource){
      if(!['GET','HEAD'].includes(req.method))throw fail('方法不支持',405);
      res.writeHead(200,{'content-type':resource.type,'content-length':resource.body.length,'cache-control':'no-store','content-security-policy':CSP,'referrer-policy':'no-referrer','x-content-type-options':'nosniff'});
      res.end(req.method==='HEAD'?undefined:resource.body);return;
    }
    const route=/^\/app\/aio\/s\/([a-z][a-z0-9-]{0,31})(\/.*)?$/.exec(target.pathname);
    if(route){
      if(['CONNECT','TRACE'].includes(req.method))throw fail('方法不支持',405);
      const service=store.get().services.find(item=>item.id===route[1]);
      if(!service)throw fail('服务不存在',404);if(!service.enabled)throw fail('此服务已停用',503);
      if(!route[2]){res.writeHead(308,{location:mount(service)+'/'+target.search,'cache-control':'no-store'});res.end();return;}
      proxy.forward(req,res,service,context,destination(service,route[2],target.search));return;
    }
    throw fail('入口不存在',404);
  }
  const server=http.createServer({maxHeaderSize:32768,headersTimeout:15000,requestTimeout:300000},(req,res)=>{handle(req,res).catch(error=>reject(res,error));});
  server.maxConnections=128;
  server.on('connection',socket=>{sockets.add(socket);socket.on('error',()=>{});socket.once('close',()=>sockets.delete(socket));});
  server.on('connect',(_req,socket)=>socket.end('HTTP/1.1 405 Rejected\r\nConnection: close\r\n\r\n'));
  server.on('upgrade',(req,socket,head)=>{
    try{
      const target=parseTarget(req.url),context=contextFor(req,policy,target),route=/^\/app\/aio\/s\/([a-z][a-z0-9-]{0,31})(\/.*)$/.exec(target.pathname);
      if(req.method!=='GET'||String(req.headers.upgrade).toLowerCase()!=='websocket'||!route)throw fail('升级入口无效',404);
      const service=store.get().services.find(item=>item.id===route[1]);if(!service)throw fail('服务不存在',404);if(!service.enabled)throw fail('此服务已停用',503);
      proxy.upgrade(req,socket,head,service,context,destination(service,route[2],target.search));
    }catch(error){socket.end(`HTTP/1.1 ${error.status||400} Rejected\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);}
  });
  server.closeApp=async()=>{proxy.close();for(const socket of sockets)socket.destroy();if(server.listening)await new Promise((yes,no)=>server.close(error=>error?no(error):yes()));};
  return server;
}
export async function startApp(options,socketPath){
  const path=resolve(socketPath);try{await lstat(path);throw fail('Socket 已存在，请先检查旧进程',409);}catch(error){if(error.code!=='ENOENT')throw error;}
  const server=await createApp(options);
  await new Promise((yes,no)=>{server.once('error',no);server.listen(path,yes);});await chmod(path,0o600);return server;
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  try{
    const policy=JSON.parse(await readFile(process.argv[2],'utf8'));
    if(!process.env.TRIM_PKGVAR)throw fail('必须由飞牛应用生命周期指定私有数据目录');
    const server=await startApp({policy,dataFile:join(resolve(process.env.TRIM_PKGVAR),'services.json')},process.argv[3]);
    console.log('AIO 私有网关已启动，仅监听 Unix Socket');
    for(const name of ['SIGTERM','SIGINT'])process.once(name,()=>server.closeApp().then(()=>process.exit(0)));
  }catch(error){console.error(error.status?error.message:'AIO 启动失败，请检查网关策略、数据目录与 Socket 权限');process.exitCode=1;}
}
