import http from 'node:http';
import {networkInterfaces} from 'node:os';
import {isIP} from 'node:net';
import {readFile,lstat,chmod} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {Store,validatePolicy,authority,fail} from './store.mjs';
import {ROOT,parseTarget,mount,destination,createProxy} from './proxy.mjs';

const CSP="default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; form-action 'self'; base-uri 'self'; frame-ancestors 'self'";
function localIpOrigins(policy){
  const origins=new Set();
  if(!policy.localIpEntries?.length)return origins;
  // Refresh per request: FN ID may select an address added after AIO started.
  // Literal interface addresses only; never resolve a client-supplied hostname.
  for(const item of Object.values(networkInterfaces()).flat().filter(Boolean)){
    const family=isIP(item.address);
    if(!family||item.address.includes('%')||['0.0.0.0','::'].includes(item.address))continue;
    const host=family===6?`[${item.address}]`:item.address;
    for(const {protocol,port} of policy.localIpEntries){
      const origin=new URL(`${protocol}://${host}:${port}`).origin;
      if(origin!==policy.publicProxy?.origin)origins.add(origin);
    }
  }
  return origins;
}
export function contextFor(req,policy,target){
  if(req.headers['x-trim-userid']!==policy.ownerUid||req.headers['x-trim-isadmin']!=='true')throw fail('仅允许已登录飞牛的指定所有者管理员访问',403);
  let raw;try{raw=authority(req.headers.host);}catch{throw fail('入口 Host 无效',400);}
  const dynamicOrigins=localIpOrigins(policy),allowedOrigins=new Set([...policy.origins,...dynamicOrigins]);
  let candidate;
  if(req.headers.origin!==undefined){
    if(typeof req.headers.origin!=='string'||!allowedOrigins.has(req.headers.origin))throw fail('请求来源不在允许列表',403);
    candidate=req.headers.origin;
  }else if(typeof req.headers.referer==='string'){
    try{const value=new URL(req.headers.referer).origin;if(allowedOrigins.has(value))candidate=value;}catch{}
  }
  let origin;
  if(raw.host==='localhost')origin=candidate??policy.defaultOrigin;
  else{
    const matching=[...policy.origins].filter(value=>{
      const url=new URL(value);if(url.hostname!==raw.hostname)return false;
      return !raw.port||new URL(`${url.protocol}//${req.headers.host}`).host===url.host;
    });
    const staticCount=matching.length;
    // fnOS forwards $host without its port. Recover only from configured origins
    // for this literal interface IP; never infer ports from forwarded headers.
    matching.push(...[...dynamicOrigins].filter(value=>{
      const url=new URL(value);
      return url.hostname===raw.hostname&&(!raw.port||new URL(`${url.protocol}//${req.headers.host}`).host===url.host);
    }));
    if(!matching.length)throw fail('入口 Host 不在允许列表',403);
    if(candidate&&!matching.includes(candidate))throw fail('Origin 与入口不一致',403);
    if(!candidate&&!staticCount&&new Set(matching).size>1)throw fail('动态入口信息不完整，请使用明确的同源入口',403);
    origin=candidate??matching[0];
  }
  const landing=req.method==='GET'&&!req.headers.upgrade&&target.pathname===ROOT+'/'&&!target.search&&req.headers['sec-fetch-mode']==='navigate'&&req.headers['sec-fetch-dest']==='document';
  if(req.headers['sec-fetch-site']==='cross-site'&&!landing)throw fail('拒绝跨站访问',403);
  return {origin};
}
export function publicContextFor(req,policy,target){
  const config=policy.publicProxy;
  if(!config)throw fail('入口不存在',404);
  // Only loopback peers may reach this listener. Forwarded identity is never trusted.
  if(!['127.0.0.1','::1','::ffff:127.0.0.1'].includes(req.socket.remoteAddress))throw fail('业务入口仅允许本机代理访问',403);
  let host;try{host=authority(req.headers.host).host;}catch{throw fail('入口 Host 无效',400);}
  if(host!==new URL(config.origin).host)throw fail('业务入口 Host 不匹配',403);
  if(req.headers.origin!==undefined&&req.headers.origin!==config.origin)throw fail('拒绝跨源业务请求',403);
  const landing=req.method==='GET'&&!req.headers.upgrade&&/^\/[a-z][a-z0-9-]{0,31}\/?$/.test(target.pathname)&&!target.search&&req.headers['sec-fetch-mode']==='navigate'&&req.headers['sec-fetch-dest']==='document';
  if(req.headers['sec-fetch-site']==='cross-site'&&!landing)throw fail('拒绝跨站业务请求',403);
  return {origin:config.origin,publicAccess:true};
}
function apiAdmission(req,context){
  if(req.headers['x-aio-request']!=='1')throw fail('缺少同源管理请求标记',403);
  if(req.method!=='GET'&&req.method!=='HEAD'){
    if(req.headers.origin!==context.origin)throw fail('管理写入需要同源 Origin',403);
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
      json(res,{name:'AIO',version:'0.2.2',privateOnly:!policy.publicProxy});return;
    }
    if(target.pathname===ROOT+'/api/services'){
      apiAdmission(req,context);
      if(req.method==='GET'){json(res,{...store.get(),policy:{targetHosts:[...policy.targetHosts],publicOrigin:policy.publicProxy?.origin??null}});return;}
      if(req.method==='PUT'){json(res,await store.save(await jsonBody(req)));return;}
      throw fail('方法不支持',405);
    }
    const check=/^\/app\/aio\/api\/services\/([a-z][a-z0-9-]{0,31})\/check$/.exec(target.pathname);
    if(check){
      apiAdmission(req,context);if(req.method!=='POST')throw fail('方法不支持',405);
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
  // This server has no management route or asset map, even with forged NAS headers.
  let business;
  if(policy.publicProxy){
    function route(req){
      const target=parseTarget(req.url),context=publicContextFor(req,policy,target);
      const match=/^\/([a-z][a-z0-9-]{0,31})(\/.*)?$/.exec(target.pathname);
      const service=match&&store.get().services.find(item=>item.id===match[1]&&item.enabled&&item.publicAccess);
      if(!service)throw fail('入口不存在',404);
      return {target,context,service,suffix:match[2]};
    }
    business=http.createServer({maxHeaderSize:32768,headersTimeout:15000,requestTimeout:300000},(req,res)=>{
      try{
        const {target,context,service,suffix}=route(req);
        if(['CONNECT','TRACE'].includes(req.method))throw fail('方法不支持',405);
        if(!suffix){res.writeHead(308,{location:mount(service,context)+'/'+target.search,'cache-control':'no-store'});res.end();return;}
        proxy.forward(req,res,service,context,destination(service,suffix,target.search));
      }catch(error){reject(res,error);}
    });
    business.maxConnections=128;
    business.on('connection',socket=>{sockets.add(socket);socket.on('error',()=>{});socket.once('close',()=>sockets.delete(socket));});
    business.on('connect',(_req,socket)=>socket.end('HTTP/1.1 405 Rejected\r\nConnection: close\r\n\r\n'));
    business.on('upgrade',(req,socket,head)=>{
      try{
        const {target,context,service,suffix}=route(req);
        if(req.method!=='GET'||String(req.headers.upgrade).toLowerCase()!=='websocket'||!suffix)throw fail('升级入口无效',404);
        proxy.upgrade(req,socket,head,service,context,destination(service,suffix,target.search));
      }catch(error){socket.end(`HTTP/1.1 ${error.status||400} Rejected\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);}
    });
  }
  server.businessServer=business;
  server.startBusiness=async()=>{
    if(business)await new Promise((yes,no)=>{business.once('error',no);business.listen(policy.publicProxy.port,policy.publicProxy.listenHost,()=>{business.removeListener('error',no);yes();});});
  };
  server.closeApp=async()=>{
    proxy.close();for(const socket of sockets)socket.destroy();
    await Promise.all([server,business].filter(item=>item?.listening).map(item=>new Promise((yes,no)=>item.close(error=>error?no(error):yes()))));
  };
  return server;
}
export async function startApp(options,socketPath){
  const path=resolve(socketPath);try{await lstat(path);throw fail('Socket 已存在，请先检查旧进程',409);}catch(error){if(error.code!=='ENOENT')throw error;}
  const server=await createApp(options);
  try{
    // Bind business first: the lifecycle's Socket readiness means both entries are ready.
    await server.startBusiness();
    await new Promise((yes,no)=>{server.once('error',no);server.listen(path,yes);});await chmod(path,0o600);return server;
  }catch(error){await server.closeApp();throw error;}
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)){
  try{
    const policy=JSON.parse(await readFile(process.argv[2],'utf8'));
    if(!process.env.TRIM_PKGVAR)throw fail('必须由飞牛应用生命周期指定私有数据目录');
    const server=await startApp({policy,dataFile:join(resolve(process.env.TRIM_PKGVAR),'services.json')},process.argv[3]);
    console.log('AIO 已启动：管理入口为私有 Unix Socket，业务入口按本机策略启用');
    for(const name of ['SIGTERM','SIGINT'])process.once(name,()=>server.closeApp().then(()=>process.exit(0)));
  }catch(error){console.error(error.status?error.message:'AIO 启动失败，请检查网关策略、数据目录与 Socket 权限');process.exitCode=1;}
}
