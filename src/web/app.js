'use strict';
const $=id=>document.getElementById(id);
let state={revision:0,services:[],policy:{targetHosts:[]}},editing=null,busy=false,ready=false;
const checks=new Map(),controllers=new Set();
const root=new URL('./',document.baseURI);
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
function entry(service){return new URL('s/'+encodeURIComponent(service.id)+'/',root).href;}
function render(){
  const container=$('services');container.replaceChildren();$('count').textContent=state.services.length+' / 50';
  $('loading').hidden=true;
  if(!state.services.length){container.append(element('p',{className:'empty',text:'还没有服务。点击“添加服务”，填写现有服务的本机端口即可。'}));return;}
  for(const service of state.services){
    const heading=element('div',{className:'card-top'},[element('h3',{text:service.name}),element('span',{className:'badge'+(service.enabled?'':' off'),text:service.enabled?'已启用':'已停用'})]);
    const target=`http://${service.host.includes(':')?'['+service.host+']':service.host}:${service.port}${service.basePath}`;
    const actions=element('div',{className:'actions'});
    if(service.enabled)actions.append(element('a',{className:'open',href:entry(service),target:'_blank',rel:'noopener noreferrer',text:'打开服务'}));
    const check=element('button',{type:'button',text:'检查连接',onClick:()=>run(async()=>{const result=await api('services/'+service.id+'/check','POST',{});checks.set(service.id,result);render();})});
    actions.append(check,element('button',{type:'button',text:'编辑',onClick:()=>edit(service)}),element('button',{type:'button',text:service.enabled?'停用':'启用',onClick:()=>run(async()=>{
      if(service.enabled&&!confirm('停用只阻止新的访问，不停止后端服务；已建立连接可能继续。确定停用？'))return;
      await save(state.services.map(item=>item.id===service.id?{...item,enabled:!item.enabled}:item));message('配置已保存。');
    })}),element('button',{type:'button',className:'danger',text:'删除',onClick:()=>run(async()=>{
      if(!confirm('仅删除 AIO 转发配置，不会删除文件或停止后端服务。确定删除？'))return;
      await save(state.services.filter(item=>item.id!==service.id));if(editing===service.id)cancel();message('服务配置已删除。');
    })}));
    const card=element('article',{className:'card'},[heading,...(service.note?[element('p',{className:'service-note',text:service.note})]:[]),element('p',{text:'目标：'+target}),element('p',{className:'address',text:'入口：'+entry(service)}),actions]);
    const result=checks.get(service.id);if(result)card.append(element('p',{className:'result','data-ok':String(result.reachable),role:'status',text:result.message}));container.append(card);
  }updateBusy();
}
async function load(){
  const next=await api('services');if(!Array.isArray(next.services)||!Number.isSafeInteger(next.revision))throw new Error('配置响应无效');
  const selectedHost=$('host').value;
  state=next;ready=true;checks.clear();$('host').replaceChildren(...state.policy.targetHosts.map(host=>element('option',{value:host,text:host})));
  if(state.policy.targetHosts.includes(selectedHost))$('host').value=selectedHost;render();
}
async function save(services){const next=await api('services','PUT',{revision:state.revision,services});state={...state,...next};checks.clear();render();}
function edit(service){
  editing=service?.id??null;$('editor-title').textContent=service?'编辑服务':'添加服务';
  $('name').value=service?.name??'';$('id').value=service?.id??'';$('id').disabled=!!service;
  $('host').value=service?.host??state.policy.targetHosts[0];$('port').value=service?.port??8080;$('base-path').value=service?.basePath??'/';$('upstream-authority').value=service?.upstreamAuthority??'';$('enabled').checked=service?.enabled??true;
  $('note').value=service?.note??'';
  $('editor').hidden=false;$('editor').scrollIntoView({block:'start'});$('name').focus();
}
function cancel(){editing=null;$('editor').hidden=true;$('id').disabled=false;$('service-form').reset();}
$('service-form').addEventListener('submit',event=>{event.preventDefault();run(async()=>{
  const service={id:$('id').value.trim(),name:$('name').value.trim(),host:$('host').value,port:Number($('port').value),basePath:$('base-path').value.trim(),upstreamAuthority:$('upstream-authority').value.trim(),enabled:$('enabled').checked,note:$('note').value};
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
window.addEventListener('pagehide',()=>{for(const controller of controllers)controller.abort();});
run(load);
