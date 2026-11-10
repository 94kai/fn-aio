import http from 'node:http';
import {fail} from './store.mjs';

export const ROOT='/app/aio';
const HOP=['connection','keep-alive','proxy-authenticate','proxy-authorization','te','trailer','transfer-encoding','upgrade'];
export function cleanHeaders(source){
  const headers={...source};for(const token of String(headers.connection??'').split(','))delete headers[token.trim().toLowerCase()];
  for(const key of HOP)delete headers[key];return headers;
}
export function parseTarget(raw){
  if(typeof raw!=='string'||!raw.startsWith('/')||raw.startsWith('//')||/[\\\x00-\x20\x7f]/.test(raw))throw fail('请求路径无效');
  const index=raw.indexOf('?'),pathname=index<0?raw:raw.slice(0,index),search=index<0?'':raw.slice(index);
  if(/%(?:2f|5c|00)/i.test(pathname))throw fail('不支持编码分隔符');
  let decoded;try{decoded=decodeURIComponent(pathname);}catch{throw fail('路径编码无效');}
  if(decoded.split('/').some(part=>part==='.'||part==='..')||/[\x00-\x1f\x7f]/.test(decoded))throw fail('请求路径无效');
  return {pathname,search};
}
export function mount(service,context){return context?.publicAccess?`/${service.id}`:`${ROOT}/s/${service.id}`;}
function cookiePrefix(service,context){return `aio_${context?.publicAccess?'public_':''}${service.id}__`;}
export function destination(service,suffix,search=''){
  return (service.basePath==='/'?'':service.basePath)+(suffix||'/')+search;
}
function targetAuthority(service){return service.upstreamAuthority||`${service.host.includes(':')?'['+service.host+']':service.host}:${service.port}`;}
export function outboundHeaders(req,service,context,upgrade=false){
  const headers=cleanHeaders(req.headers);
  for(const key of Object.keys(headers))if(key.startsWith('x-trim-')||key.startsWith('x-forwarded-')||key.startsWith('x-aio-')||['forwarded','referer'].includes(key))delete headers[key];
  headers.host=targetAuthority(service);
  if(headers.origin!==undefined)headers.origin=`http://${headers.host}`;
  const prefix=cookiePrefix(service,context);
  if(headers.cookie){
    const cookies=String(headers.cookie).split(';').map(value=>value.trim()).filter(value=>value.startsWith(prefix)&&value.indexOf('=')>prefix.length).map(value=>value.slice(prefix.length));
    if(cookies.length)headers.cookie=cookies.join('; ');else delete headers.cookie;
  }
  headers['x-forwarded-host']=new URL(context.origin).host;
  headers['x-forwarded-proto']=new URL(context.origin).protocol.slice(0,-1);
  headers['x-forwarded-prefix']=mount(service,context);
  if(upgrade){headers.connection='Upgrade';headers.upgrade='websocket';}
  return headers;
}
function mountedPath(service,path,context){
  const base=service.basePath==='/'?'':service.basePath,prefix=mount(service,context);
  const hasBase=base&&(path===base||path.startsWith(base+'/'));
  if(!hasBase&&(path===prefix||path.startsWith(prefix+'/')))return path;
  const suffix=hasBase?path.slice(base.length):path;
  return prefix+(suffix.startsWith('/')?suffix:'/'+suffix);
}
export function rewriteCookie(cookie,service,context){
  const parts=String(cookie).split(';').map(value=>value.trim()),first=parts.shift(),index=first.indexOf('=');
  if(index<1||! /^[!#$%&'*+.^_`|~0-9a-z-]+$/i.test(first.slice(0,index)))return null;
  const out=[`${cookiePrefix(service,context)}${first}`];let hasPath=false,hasSecure=false;
  for(const attr of parts){
    const key=attr.split('=')[0].toLowerCase();
    if(key==='domain')continue;
    if(key==='path'){out.push('Path='+mountedPath(service,attr.slice(attr.indexOf('=')+1)||'/',context));hasPath=true;}
    else {out.push(attr);if(key==='secure')hasSecure=true;}
  }
  if(!hasPath)out.push('Path='+mount(service,context)+'/');
  if(context.origin.startsWith('https:')&&!hasSecure)out.push('Secure');
  return out.join('; ');
}
export function inboundHeaders(headers,service,context,upstreamPath){
  const out=cleanHeaders(headers);
  if(out['set-cookie'])out['set-cookie']=out['set-cookie'].map(value=>rewriteCookie(value,service,context)).filter(Boolean);
  if(typeof out.location==='string'){
    try{
      const actual=`http://${service.host.includes(':')?'['+service.host+']':service.host}:${service.port}`;
      const current=new URL(upstreamPath,actual),location=new URL(out.location,current);
      const accepted=new Set([new URL(actual).host,new URL(`http://${targetAuthority(service)}`).host]);
      if(['http:','https:'].includes(location.protocol)&&accepted.has(location.host))out.location=mountedPath(service,location.pathname,context)+location.search+location.hash;
    }catch{delete out.location;}
  }
  return out;
}
export function createProxy(){
  const requests=new Set(),tunnels=new Set();
  function options(req,service,context,path,upgrade=false){return {hostname:service.host,port:service.port,path,method:req.method,headers:outboundHeaders(req,service,context,upgrade),agent:false,maxHeaderSize:32768};}
  function track(request){requests.add(request);request.once('close',()=>requests.delete(request));return request;}
  function forward(req,res,service,context,path){
    const upstream=track(http.request(options(req,service,context,path)));
    upstream.setTimeout(300000,()=>upstream.destroy());
    upstream.on('error',()=>{if(!res.headersSent){res.writeHead(502,{'content-type':'text/plain; charset=utf-8','cache-control':'no-store'});res.end('目标服务暂时不可用');}else res.destroy();});
    req.on('aborted',()=>upstream.destroy());res.on('close',()=>upstream.destroy());
    upstream.on('response',reply=>{res.writeHead(reply.statusCode,inboundHeaders(reply.headers,service,context,path));reply.on('error',()=>res.destroy());reply.pipe(res);});
    req.pipe(upstream);
  }
  function upgrade(req,socket,head,service,context,path){
    const upstream=track(http.request(options(req,service,context,path,true)));
    const timer=setTimeout(()=>upstream.destroy(),15000);
    upstream.on('error',()=>socket.destroy());socket.once('close',()=>{clearTimeout(timer);upstream.destroy();});
    upstream.on('response',reply=>{clearTimeout(timer);reply.resume();socket.end(`HTTP/1.1 ${reply.statusCode||502} Rejected\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);});
    upstream.on('upgrade',(reply,remote,remoteHead)=>{
      clearTimeout(timer);tunnels.add(remote);remote.once('close',()=>tunnels.delete(remote));
      remote.on('error',()=>socket.destroy());remote.once('close',()=>socket.destroy());socket.once('close',()=>remote.destroy());
      const headers=inboundHeaders(reply.headers,service,context,path);headers.connection='Upgrade';headers.upgrade='websocket';
      socket.write(`HTTP/1.1 101 Switching Protocols\r\n${Object.entries(headers).flatMap(([key,value])=>(Array.isArray(value)?value:[value]).map(item=>`${key}: ${item}`)).join('\r\n')}\r\n\r\n`);
      if(remoteHead.length)socket.write(remoteHead);if(head.length)remote.write(head);
      remote.pipe(socket);socket.pipe(remote);
    });upstream.end();
  }
  async function check(service){
    return new Promise(resolve=>{
      let settled=false;const finish=value=>{if(!settled){settled=true;clearTimeout(timer);resolve(value);}};
      const request=track(http.request({hostname:service.host,port:service.port,path:service.basePath==='/'?'/':service.basePath+'/',method:'HEAD',headers:{host:targetAuthority(service)},agent:false,maxHeaderSize:32768}));
      const timer=setTimeout(()=>{finish({reachable:false,message:'连接超时'});request.destroy();},5000);
      request.on('error',()=>finish({reachable:false,message:'无法连接目标服务'}));
      request.on('response',reply=>{const status=reply.statusCode;reply.resume();finish({reachable:true,status,message:status===401||status===403?'服务可达，需要业务登录':`服务可达，HTTP ${status}`});request.destroy();});request.end();
    });
  }
  return {forward,upgrade,check,close(){for(const request of requests)request.destroy();for(const socket of tunnels)socket.destroy();}};
}
