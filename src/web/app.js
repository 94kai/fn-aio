'use strict';
const $=id=>document.getElementById(id);
let state={revision:0,services:[],policy:{targetHosts:[]}},editing=null,busy=false,ready=false;
const checks=new Map(),controllers=new Set();
const root=new URL('./',document.baseURI);
// The FPK builder replaces this value using the validated deployment policy.
const packagedDefaultOrigin=null;
function element(tag,props={},children=[]){const node=document.createElement(tag);for(const [key,value]of Object.entries(props)){if(key==='className')node.className=value;else if(key==='text')node.textContent=value;else if(key==='onClick')node.addEventListener('click',value);else node.setAttribute(key,value);}for(const child of children)node.append(child);return node;}
function message(text,error=false){$('message').textContent=text;$('message').dataset.error=String(error);$('message').hidden=!text;$('message').setAttribute('role',error?'alert':'status');}
function updateBusy(){document.querySelectorAll('button').forEach(button=>button.disabled=busy);$('fields').disabled=busy;$('import').disabled=busy||!ready;$('export').disabled=busy||!ready;$('add').disabled=busy||!ready;}
async function run(action){if(busy)return;busy=true;updateBusy();try{await action();}catch(error){message(error.name==='AbortError'?'请求超时或已取消，请重试。':error.message||'操作失败，请重试。',true);}finally{busy=false;updateBusy();}}
async function api(path,method='GET',body){
  const controller=new AbortController();controllers.add(controller);const timer=setTimeout(()=>controller.abort(),10000);
  try{
    const response=await fetch(new URL('api/'+path,root),{method,credentials:'same-origin',cache:'no-store',redirect:'error',signal:controller.signal,headers:{'X-Aio-Request':'1',...(body===undefined?{}:{'Content-Type':'application/json'})},body:body===undefined?undefined:JSON.stringify(body)});
    const value=await response.json().catch(()=>({error:'无法解析服务器响应，请确认飞牛登录状态。'}));
    if(!response.ok)throw new Error(value.error||'操作失败');return value;
  }finally{clearTimeout(timer);controllers.delete(controller);}
}
function entry(service){
  for(const value of [state.policy?.defaultOrigin,packagedDefaultOrigin]){
    try{
      const origin=new URL(value);
      if(!['http:','https:'].includes(origin.protocol)||origin.origin!==value)continue;
      return new URL('/app/aio/s/'+encodeURIComponent(service.id)+'/',origin).href;
    }catch{/* Try the packaged policy if an older API omits the default origin. */}
  }
  return null;
}
function render(){
  const container=$('services');container.replaceChildren();$('count').textContent=state.services.length+' / 50';
  $('loading').hidden=true;
  if(!state.services.length){container.append(element('p',{className:'empty',text:'还没有服务。点击“添加服务”，填写现有服务的本机端口即可。'}));return;}
  for(const service of state.services){
    const heading=element('div',{className:'card-top'},[element('h3',{text:service.name}),element('span',{className:'badge'+(service.enabled?'':' off'),text:service.enabled?'已启用':'已停用'})]);
    const target=`http://${service.host.includes(':')?'['+service.host+']':service.host}:${service.port}${service.basePath}`;
    const access=element('div',{className:'card-access'});
    const privateUrl=entry(service);
    if(service.enabled&&privateUrl)access.append(element('a',{className:'open',href:privateUrl,target:'_blank',rel:'noopener noreferrer',text:'飞牛私有入口'}));
    else access.append(element('span',{className:'access-disabled',text:service.enabled?'默认飞牛入口不可用':'服务已停用，启用后可访问'}));
    const actions=element('div',{className:'card-actions','aria-label':service.name+'的管理操作'});
    const check=element('button',{type:'button',text:'检查连接',onClick:()=>run(async()=>{const result=await api('services/'+service.id+'/check','POST',{});checks.set(service.id,result);render();})});
    actions.append(check,element('button',{type:'button',text:'编辑',onClick:()=>edit(service)}),element('button',{type:'button',text:service.enabled?'停用':'启用',onClick:()=>run(async()=>{
      if(service.enabled&&!confirm('停用只阻止新的访问，不停止后端服务；已建立连接可能继续。确定停用？'))return;
      await save(state.services.map(item=>item.id===service.id?{...item,enabled:!item.enabled}:item));message('配置已保存。');
    })}),element('button',{type:'button',className:'danger',text:'删除',onClick:()=>run(async()=>{
      if(!confirm('仅删除 AIO 转发配置，不会删除文件或停止后端服务。确定删除？'))return;
      await save(state.services.filter(item=>item.id!==service.id));if(editing===service.id)cancel();message('服务配置已删除。');
    })}));
    const addresses=element('dl',{className:'entry-addresses'},[
      element('div',{},[element('dt',{text:'飞牛私有入口'}),element('dd',{className:'address',text:privateUrl??'接口未返回有效的默认飞牛地址，请重启 AIO 后刷新。'})]),
    ]);
    const details=element('details',{className:'entry-details'},[
      element('summary',{text:'入口地址与说明'}),addresses,
      element('p',{text:'私有入口固定使用管理员配置的默认飞牛地址，需要在该入口登录飞牛；不随当前页面域名或 IP 改变。'}),
    ]);
    const card=element('article',{className:'card'},[heading,element('p',{className:'service-target',text:'本机目标 · '+target}),...(service.note?[element('p',{className:'service-note',text:service.note})]:[])]);
    if(service.publicAccess){
      if(state.policy.publicOrigin&&service.enabled){
        const url=state.policy.publicOrigin+'/'+encodeURIComponent(service.id)+'/';
        addresses.append(element('div',{},[element('dt',{text:'外部入口 · 业务自身登录'}),element('dd',{className:'address',text:url})]));
        access.append(element('a',{className:'open external',href:url,target:'_blank',rel:'noopener noreferrer',text:'外部入口 ↗'}));
      }else card.append(element('p',{className:'result',text:service.enabled?'外部访问已授权，但管理员尚未配置业务入口策略。':'外部访问已授权，服务停用期间不可访问。'}));
    }
    card.append(access,actions,details);
    const result=checks.get(service.id);if(result)card.append(element('p',{className:'result','data-ok':String(result.reachable),role:'status',text:result.message}));container.append(card);
  }updateBusy();
}
async function load(){
  const next=await api('services');if(!Array.isArray(next.services)||!Number.isSafeInteger(next.revision))throw new Error('配置响应无效');
  const selectedHost=$('host').value;
  state=next;ready=true;checks.clear();$('host').replaceChildren(...state.policy.targetHosts.map(host=>element('option',{value:host,text:host})));
  if(state.policy.targetHosts.includes(selectedHost))$('host').value=selectedHost;render();await loadTokens();
}
async function save(services){
  const opening=services.some(item=>item.publicAccess&&item.enabled&&!state.services.some(previous=>previous.id===item.id&&previous.publicAccess&&previous.enabled));
  if(opening&&!confirm('允许外部访问后，这些服务不再受飞牛登录保护。请确认业务自身有鉴权，并信任其同域网页。确定开放？'))throw new Error('已取消外部开放，配置未保存。');
  const next=await api('services','PUT',{revision:state.revision,services});state={...state,...next};checks.clear();render();
}
function edit(service){
  editing=service?.id??null;$('editor-title').textContent=service?'编辑服务':'添加服务';
  $('name').value=service?.name??'';$('id').value=service?.id??'';$('id').disabled=!!service;
  $('host').value=service?.host??state.policy.targetHosts[0];$('port').value=service?.port??8080;$('base-path').value=service?.basePath??'/';$('upstream-authority').value=service?.upstreamAuthority??'';$('enabled').checked=service?.enabled??true;
  $('note').value=service?.note??'';$('public-access').checked=service?.publicAccess??false;
  $('public-hint').textContent=state.policy.publicOrigin?'外部入口：'+state.policy.publicOrigin+'/服务标识/。请确认业务自身有登录鉴权，不受飞牛登录保护。':'管理员尚未配置业务入口策略；授权后也不会立即对外开放。';
  $('editor').hidden=false;$('editor').scrollIntoView({block:'start'});$('name').focus();
}
function cancel(){editing=null;$('editor').hidden=true;$('id').disabled=false;$('service-form').reset();}
$('service-form').addEventListener('submit',event=>{event.preventDefault();run(async()=>{
  const service={id:$('id').value.trim(),name:$('name').value.trim(),host:$('host').value,port:Number($('port').value),basePath:$('base-path').value.trim(),upstreamAuthority:$('upstream-authority').value.trim(),enabled:$('enabled').checked,publicAccess:$('public-access').checked,note:$('note').value};
  if(!editing&&state.services.some(item=>item.id===service.id))throw new Error('服务标识已存在，请换一个标识。');
  if(editing&&!state.services.some(item=>item.id===editing))throw new Error('该服务已被删除，请取消编辑后重新添加。');
  await save(editing?state.services.map(item=>item.id===editing?service:item):[...state.services,service]);cancel();message('服务已保存。可先检查连接，再打开入口。');
});});
$('reload').addEventListener('click',()=>run(async()=>{await load();message('已重新读取配置；未保存的表单内容仍保留。');}));
$('add').addEventListener('click',()=>edit(null));$('cancel').addEventListener('click',cancel);
$('export').addEventListener('click',()=>{
  const data=JSON.stringify({schemaVersion:1,revision:state.revision,services:state.services},null,2);
  const url=URL.createObjectURL(new Blob([data],{type:'application/json'})),link=element('a',{href:url,download:'aio-services.json'});link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);message('已导出服务配置；文件包含你的内部地址，请妥善保存。');
});
$('import').addEventListener('click',()=>$('import-file').click());
$('import-file').addEventListener('change',()=>run(async()=>{
  const file=$('import-file').files[0];$('import-file').value='';if(!file)return;
  if(file.size>128*1024)throw new Error('配置文件不能超过 128 KiB。');
  let value;try{value=JSON.parse(await file.text());}catch{throw new Error('配置文件不是有效 JSON。');}
  if(value.schemaVersion!==1||!Array.isArray(value.services))throw new Error('不支持此配置文件格式。');
  if(!confirm('导入会替换全部 AIO 服务配置，不会修改后端服务。确定继续？'))return;
  await save(value.services);cancel();message('服务配置已导入。');
}));
let tokenTimer;
function clearToken(){clearTimeout(tokenTimer);$('token-value').value='';}
function closeToken(){clearToken();$('token-dialog').close();}
function tokenTime(value){return value?'日志时间：'+value+'（日志原文）':'尚无可用记录';}
async function copyServiceToken(source,time){
  clearToken();
  const value=await api('tokens/'+source.id,'POST',{});
  time.textContent=tokenTime(value.timestamp);
  if(typeof value.token!=='string'||!/^[-_a-zA-Z0-9]{16,512}$/.test(value.token))throw new Error('token 响应无效');
  try{
    await navigator.clipboard.writeText(value.token);
    message(source.name+' token 已复制；请勿分享给他人。');
  }catch{
    $('token-dialog-title').textContent=source.name+' · '+value.timestamp;
    $('token-value').value=value.token;$('token-dialog').showModal();$('token-value').focus();$('token-value').select();
    tokenTimer=setTimeout(closeToken,60000);
    message('浏览器未允许自动复制，请在弹窗中继续复制。');
  }
}
async function loadTokens(){
  try{
    const result=await api('tokens');
    if(!Array.isArray(result.sources))throw new Error('token 状态响应无效');
    $('token-section').hidden=!result.sources.length;
    $('token-sources').replaceChildren(...result.sources.map(source=>{
      const time=element('p',{className:'token-time',text:tokenTime(source.timestamp)});
      return element('article',{className:'card'},[
        element('div',{className:'card-top'},[element('h3',{text:source.name}),element('span',{className:'badge',text:source.rescue?'救援模式':'常规模式'})]),time,
        element('p',{text:source.available?'点击时重新读取最新 token，不展示明文。':source.message||'尚无可用记录'}),
        element('button',{type:'button',className:'primary',text:'复制最新 token',onClick:()=>run(()=>copyServiceToken(source,time))}),
      ]);
    }));
  }catch{
    $('token-section').hidden=false;
    $('token-sources').replaceChildren(element('p',{className:'result',text:'无法读取 token 日志状态，请确认 AIO 已升级并配置日志来源，然后重试。'}));
  }
}
$('token-reload').addEventListener('click',()=>run(loadTokens));
$('token-close').addEventListener('click',closeToken);
$('token-dialog').addEventListener('close',clearToken);
$('token-copy').addEventListener('click',async()=>{
  const input=$('token-value');if(!input.value)return;
  try{input.focus();input.select();if(!document.execCommand('copy'))await navigator.clipboard.writeText(input.value);closeToken();message('token 已复制；请勿分享给他人。');}
  catch{message('复制被浏览器阻止，请选中文本后手动复制。',true);}
});
window.addEventListener('pagehide',()=>{clearToken();for(const controller of controllers)controller.abort();});
run(load);
