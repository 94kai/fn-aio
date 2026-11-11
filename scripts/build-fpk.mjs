import {mkdtemp,mkdir,copyFile,writeFile,readFile} from 'node:fs/promises';
import {join,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import {deflateSync} from 'node:zlib';
import {validatePolicy} from '../src/store.mjs';
import {configureWebScript} from './web-config.mjs';
const root=fileURLToPath(new URL('../',import.meta.url));
const [configFile]=process.argv.slice(2);
if(!configFile||process.argv.length!==3)throw new Error('用法：npm run build -- /绝对路径/本机网关策略.json');
const policy=JSON.parse(await readFile(resolve(configFile),'utf8'));validatePolicy(policy);
const output=join(root,'.local');await mkdir(output,{recursive:true,mode:0o700});
const stage=await mkdtemp(join(output,'build-'));
for(const dir of ['app/web','app/ui/images','config','cmd'])await mkdir(join(stage,dir),{recursive:true});
const put=(name,content,mode=0o644)=>writeFile(join(stage,name),content,{mode,flag:'wx'});
await put('manifest',`appname = aio\nversion = 0.2.8\ndisplay_name = AIO 服务入口\ndesc = 独立的飞牛私有服务网关：统一管理 NAS 自建 HTTP 服务，不依赖 DSH\nplatform = x86\nsource = thirdparty\nmaintainer = FN AIO\ndistributor = FN AIO\ndesktop_uidir = ui\ndesktop_applaunchname = aio.main\ncheckport = false\n`);
await put('config/privilege',JSON.stringify({defaults:{'run-as':'package'}},null,2));
await put('config/resource','{}\n');
await put('app/ui/config',JSON.stringify({'.url':{'aio.main':{title:'AIO',icon:'images/icon_{0}.png',type:'url',protocol:'',gatewayPrefix:'/app/aio',gatewaySocket:'app.sock',url:'/app/aio/',allUsers:false}}},null,2));
await put('app/gateway.json',JSON.stringify(policy,null,2));
for(const file of ['bridge.mjs','store.mjs','proxy.mjs','token-logs.mjs'])await copyFile(join(root,'src',file),join(stage,'app',file));
for(const file of ['index.html','style.css'])await copyFile(join(root,'src/web',file),join(stage,'app/web',file));
await put('app/web/app.js',configureWebScript(await readFile(join(root,'src/web/app.js'),'utf8'),policy.defaultOrigin));
await copyFile(join(root,'scripts/control.py'),join(stage,'app/control.py'));
await put('cmd/main','#!/bin/sh\nexec /usr/bin/python3 "${TRIM_APPDEST:?}/control.py" "$1"\n',0o755);
for(const file of ['install_init','install_callback','uninstall_init','uninstall_callback','upgrade_init','upgrade_callback','config_init','config_callback'])await put('cmd/'+file,'#!/bin/sh\nexit 0\n',0o755);
// Original AIO bitmap icon, generated with built-in zlib; no external asset/runtime.
function crc32(buffer){let crc=0xffffffff;for(const byte of buffer){crc^=byte;for(let bit=0;bit<8;bit++)crc=(crc>>>1)^((crc&1)?0xedb88320:0);}return (crc^0xffffffff)>>>0;}
function chunk(type,data){const header=Buffer.from(type),length=Buffer.alloc(4),crc=Buffer.alloc(4);length.writeUInt32BE(data.length);crc.writeUInt32BE(crc32(Buffer.concat([header,data])));return Buffer.concat([length,header,data,crc]);}
function icon(size){
  const glyphs=['01110100011000111111100011000110001','11111001000010000100001000010011111','01110100011000110001100011000101110'];
  const scale=Math.max(1,Math.floor(size/24)),width=19*scale,left=Math.floor((size-width)/2),top=Math.floor((size-7*scale)/2),rows=[];
  for(let y=0;y<size;y++){const row=Buffer.alloc(1+size*4);for(let x=0;x<size;x++){let white=false;const gx=Math.floor((x-left)/scale),gy=Math.floor((y-top)/scale);if(gx>=0&&gx<19&&gy>=0&&gy<7){const letter=Math.floor(gx/7),column=gx%7;if(letter<3&&column<5)white=glyphs[letter][gy*5+column]==='1';}const offset=1+x*4;row[offset]=white?255:55;row[offset+1]=white?255:93;row[offset+2]=white?255:201;row[offset+3]=255;}rows.push(row);}
  const ihdr=Buffer.alloc(13);ihdr.writeUInt32BE(size,0);ihdr.writeUInt32BE(size,4);ihdr[8]=8;ihdr[9]=6;
  return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',ihdr),chunk('IDAT',deflateSync(Buffer.concat(rows))),chunk('IEND',Buffer.alloc(0))]);
}
for(const [file,size] of [['ICON.PNG',64],['ICON_256.PNG',256],['app/ui/images/icon_64.png',64],['app/ui/images/icon_256.png',256]])await put(file,icon(size));
const result=spawnSync('fnpack',['build','--directory',stage],{cwd:stage,stdio:'inherit'});
if(result.status!==0)throw new Error('FPK 构建失败：'+result.status);
console.log('AIO 安装包：'+join(stage,'aio.fpk'));
