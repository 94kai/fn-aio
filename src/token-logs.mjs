import {open,constants} from 'node:fs/promises';
import {isAbsolute,normalize,basename} from 'node:path';

const SOURCES={dsh:{name:'DSH',rescue:false,file:'dsh-out.log'},rescue:{name:'DSH 救援模式',rescue:true,file:'dsh-rescue-out.log'}};
const MAX_BYTES=8*1024*1024;
const error=(message,status=400)=>Object.assign(new Error(message),{status});
export function validateTokenLogs(input){
  if(input===undefined)return {};
  if(!input||typeof input!=='object'||Array.isArray(input)||Object.keys(input).some(key=>!Object.hasOwn(SOURCES,key)))throw error('token 日志仅支持 dsh 和 rescue 两个来源');
  for(const [id,path] of Object.entries(input)){
    if(typeof path!=='string'||path.length>4096||/[\x00-\x1f\x7f]/.test(path)||!isAbsolute(path)||normalize(path)!==path||basename(path)!==SOURCES[id].file)throw error('token 日志须为对应日志文件的规范绝对路径');
  }
  return {...input};
}
export function tokenSources(logs){return Object.keys(logs).map(id=>({id,name:SOURCES[id].name,rescue:SOURCES[id].rescue}));}
export function parseTokenLine(line){
  if(!line.includes('dsh web: ')||!line.includes('?token='))return null;
  const match=line.match(/(?:^|\s)(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?(?:Z|[+-]\d{2}:\d{2})?):?\s+dsh web: (https?:\/\/\S+)\s*$/);
  if(!match||!Number.isFinite(Date.parse(match[1])))throw error('最近一条 token 日志格式无效，未使用更早的 token',422);
  let url;try{url=new URL(match[2]);}catch{throw error('最近一条 token 日志格式无效',422);}
  const token=url.searchParams.get('token');
  if(!['http:','https:'].includes(url.protocol)||!['127.0.0.1','localhost','[::1]'].includes(url.hostname)||url.username||url.password||url.searchParams.getAll('token').length!==1||!token||!/^[-_a-zA-Z0-9]{16,512}$/.test(token))throw error('最近一条 token 日志格式无效，未使用更早的 token',422);
  return {token,timestamp:match[1]};
}
export async function readLatestToken(path){
  let file;
  try{
    // Never follow a replacement symlink or block on a FIFO/device.
    file=await open(path,constants.O_RDONLY|constants.O_NOFOLLOW|constants.O_NONBLOCK);
    const stat=await file.stat();if(!stat.isFile())throw error('日志不是普通文件',422);
    const size=Math.min(stat.size,MAX_BYTES),buffer=Buffer.alloc(size);
    let read=0;while(read<size){const result=await file.read(buffer,read,size-read,stat.size-size+read);if(!result.bytesRead)break;read+=result.bytesRead;}
    const text=buffer.subarray(0,read).toString('utf8');
    // Ignore an unfinished final line to avoid copying a partially written token.
    let end=text.lastIndexOf('\n');
    while(end>0){
      const start=text.lastIndexOf('\n',end-1)+1;
      if(start===0&&stat.size>size)break; // First line of a bounded tail may be truncated.
      const value=parseTokenLine(text.slice(start,end).replace(/\r$/,''));if(value)return value;
      if(start===0)break;end=start-1;
    }
    throw error('最近 8 MiB 日志中未找到完整的 token 记录',404);
  }catch(cause){
    if(cause.status)throw cause;
    if(['EACCES','EPERM'].includes(cause.code))throw error('AIO 无权读取该日志，请管理员检查只读权限',403);
    if(cause.code==='ENOENT')throw error('日志文件不存在',404);
    throw error('日志暂时无法读取',503);
  }finally{await file?.close();}
}
