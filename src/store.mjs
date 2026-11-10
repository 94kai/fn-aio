import {mkdir,readFile,writeFile,rename,unlink,copyFile} from 'node:fs/promises';
import {dirname} from 'node:path';
import {randomUUID} from 'node:crypto';
import {isIP} from 'node:net';
import {networkInterfaces} from 'node:os';

export function fail(message,status=400){return Object.assign(new Error(message),{status});}
export function authority(value){
  if(typeof value!=='string'||!value||/[\s/@?#\\]/.test(value))throw fail('Host 格式无效');
  let parsed;try{parsed=new URL(`http://${value}`);}catch{throw fail('Host 格式无效');}
  if(parsed.pathname!=='/'||parsed.username||parsed.password)throw fail('Host 格式无效');
  return parsed;
}
export function validatePolicy(input){
  if(!input||!/^\d{1,10}$/.test(input.ownerUid))throw fail('缺少所有者 UID');
  if(!Array.isArray(input.allowedOrigins)||!input.allowedOrigins.length)throw fail('缺少飞牛入口');
  const origins=new Set(input.allowedOrigins.map(value=>{
    const url=new URL(value);if(!['http:','https:'].includes(url.protocol)||url.origin!==value||url.username||url.password)throw fail('入口必须为规范 HTTP(S) Origin');return url.origin;
  }));
  if(!origins.has(input.defaultOrigin))throw fail('默认入口不在允许列表中');
  const localAddresses=new Set(['127.0.0.1','::1',...Object.values(networkInterfaces()).flat().filter(Boolean).map(item=>item.address)]);
  if(!Array.isArray(input.targetHosts)||!input.targetHosts.length||input.targetHosts.some(host=>typeof host!=='string'||!isIP(host)||!localAddresses.has(host)||['0.0.0.0','::'].includes(host)))throw fail('目标必须是当前 NAS 接口上的明确本机 IP 地址');
  const localIpEntries=input.localIpEntries===undefined?[]:input.localIpEntries;
  if(!Array.isArray(localIpEntries)||localIpEntries.length>32||localIpEntries.some(value=>!value||typeof value!=='object'||Array.isArray(value)||Object.keys(value).some(key=>!['protocol','port'].includes(key))||!['http','https'].includes(value.protocol)||!Number.isInteger(value.port)||value.port<1||value.port>65535))throw fail('动态本机入口须为明确的 HTTP(S) 协议与端口，最多 32 项');
  let publicProxy=null;
  if(input.publicProxy!==undefined){
    const value=input.publicProxy;
    if(!value||typeof value!=='object'||Array.isArray(value)||Object.keys(value).some(key=>!['listenHost','port','origin'].includes(key)))throw fail('业务入口策略字段无效');
    if(!['127.0.0.1','::1'].includes(value.listenHost))throw fail('业务入口只允许监听回环地址');
    if(!Number.isInteger(value.port)||value.port<1024||value.port>65535||[5666,5667].includes(value.port))throw fail('业务入口端口无效');
    let url;try{url=new URL(value.origin);}catch{throw fail('业务外部入口必须为规范 HTTPS Origin');}
    if(url.protocol!=='https:'||url.origin!==value.origin||url.username||url.password||/[\s*]/.test(value.origin)||origins.has(value.origin))throw fail('业务外部入口必须为独立的规范 HTTPS Origin');
    if(Number(url.port||443)===value.port)throw fail('业务内部端口不能与外部端口相同');
    publicProxy={listenHost:value.listenHost,port:value.port,origin:url.origin};
  }
  return {...input,localIpEntries:localIpEntries.map(({protocol,port})=>({protocol,port})),publicProxy,origins,targetHosts:new Set(input.targetHosts)};
}
export function normalizeBasePath(value){
  if(typeof value!=='string'||!value.startsWith('/')||value.startsWith('//')||/[?#\\\s]/.test(value)||/%(?:2f|5c|00)/i.test(value))throw fail('基础路径必须以 / 开头且不含查询或编码分隔符');
  let decoded;try{decoded=decodeURIComponent(value);}catch{throw fail('基础路径编码无效');}
  if(decoded.split('/').some(part=>part==='.'||part==='..')||/[\x00-\x1f\x7f]/.test(decoded))throw fail('基础路径不能包含目录穿越');
  return value==='/'?'/':value.replace(/\/+$/,'');
}
export function validateServices(items,policy){
  if(!Array.isArray(items)||items.length>50)throw fail('最多配置 50 个服务');
  const ids=new Set();
  return items.map(item=>{
    if(!item||typeof item!=='object'||Array.isArray(item)||Object.keys(item).some(key=>!['id','name','host','port','basePath','upstreamAuthority','enabled','note','publicAccess'].includes(key)))throw fail('服务字段无效');
    if(typeof item.id!=='string'||!/^[a-z][a-z0-9-]{0,31}$/.test(item.id)||ids.has(item.id))throw fail('服务标识须为小写字母开头的字母、数字或短横线，且不能重复');ids.add(item.id);
    if(typeof item.name!=='string'||!item.name.trim()||item.name.length>60||/[\x00-\x1f\x7f]/.test(item.name))throw fail('服务名称须为 1～60 字符');
    if(!policy.targetHosts.has(item.host))throw fail('目标地址不在本机允许列表中');
    if(!Number.isInteger(item.port)||item.port<1024||item.port>65535||[5666,5667].includes(item.port))throw fail('端口须为 1024～65535，且不能回指飞牛网关');
    if(policy.publicProxy&&[policy.publicProxy.port,Number(new URL(policy.publicProxy.origin).port||443)].includes(item.port))throw fail('目标端口不能回指 AIO 业务入口或外部代理入口');
    if(typeof item.enabled!=='boolean')throw fail('启用状态必须为布尔值');
    const publicAccess=item.publicAccess??false;
    if(item.publicAccess!==undefined&&typeof item.publicAccess!=='boolean')throw fail('外部访问状态必须为布尔值');
    const basePath=normalizeBasePath(item.basePath??'/');
    const upstreamAuthority=item.upstreamAuthority??'';
    if(upstreamAuthority!==''&&(typeof upstreamAuthority!=='string'||upstreamAuthority.length>253))throw fail('回源 Host 无效');
    if(upstreamAuthority)authority(upstreamAuthority);
    const note=item.note===undefined?'':item.note;
    if(typeof note!=='string'||note.length>500||/[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/.test(note))throw fail('备注须为最多 500 字符的文本');
    return {id:item.id,name:item.name.trim(),host:item.host,port:item.port,basePath,upstreamAuthority,enabled:item.enabled,publicAccess,note:note.replace(/\r\n?/g,'\n')};
  });
}

export class Store{
  constructor(file,policy){this.file=file;this.policy=policy;this.state=null;this.queue=Promise.resolve();}
  async init(){
    let value;
    try{value=JSON.parse(await readFile(this.file,'utf8'));}
    catch(error){if(error.code!=='ENOENT')throw fail('服务配置损坏或无法读取，已停止启动以避免覆盖',503);value={schemaVersion:1,revision:0,services:[]};}
    if(value.schemaVersion!==1||!Number.isSafeInteger(value.revision)||value.revision<0)throw fail('配置版本或修订号无效',503);
    this.state={schemaVersion:1,revision:value.revision,services:validateServices(value.services,this.policy)};
    return this;
  }
  get(){return structuredClone(this.state);}
  save(input){
    const run=async()=>{
      if(!input||!Number.isSafeInteger(input.revision)||input.revision!==this.state.revision)throw fail('配置已被其他页面修改，请重新加载后再保存',409);
      if(this.state.revision===Number.MAX_SAFE_INTEGER)throw fail('修订号已达上限',409);
      const next={schemaVersion:1,revision:this.state.revision+1,services:validateServices(input.services,this.policy)};
      await mkdir(dirname(this.file),{recursive:true,mode:0o700});
      const temp=`${this.file}.${randomUUID()}.tmp`;
      try{
        await writeFile(temp,JSON.stringify(next,null,2)+'\n',{flag:'wx',mode:0o600});
        try{await copyFile(this.file,`${this.file}.previous`);}catch(error){if(error.code!=='ENOENT')throw error;}
        await rename(temp,this.file);this.state=next;
      }finally{
        // Exact private temporary child of the fixed config filename, created above.
        await unlink(temp).catch(error=>{if(error.code!=='ENOENT')throw error;});
      }
      return this.get();
    };
    const result=this.queue.then(run);this.queue=result.catch(()=>{});return result;
  }
}
