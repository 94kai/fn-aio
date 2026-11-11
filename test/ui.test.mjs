import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
import {configureWebScript} from '../scripts/web-config.mjs';
const html=await readFile(new URL('../src/web/index.html',import.meta.url),'utf8');
const js=await readFile(new URL('../src/web/app.js',import.meta.url),'utf8');
const css=await readFile(new URL('../src/web/style.css',import.meta.url),'utf8');

test('管理脚本引用的表单和操作元素均存在，所有资源自托管',()=>{
  const ids=new Set([...html.matchAll(/\bid="([^"]+)"/g)].map(item=>item[1]));
  for(const [,id]of js.matchAll(/\$\('([^']+)'\)/g))assert.ok(ids.has(id),'缺少界面元素：'+id);
  assert.match(html,/lang="zh-CN"/);assert.match(html,/服务入口/);
  assert.doesNotMatch(html,/(?:src|href)="https?:/);
  assert.match(html,/assets\/app\.js/);assert.match(html,/assets\/style\.css/);
});

test('服务文本不作为 HTML 注入，配置请求保护和破坏性操作确认保留',()=>{
  assert.doesNotMatch(js,/innerHTML|eval\(|document\.cookie|localStorage|sessionStorage/);
  assert.match(js,/textContent=value/);assert.match(js,/credentials:'same-origin'/);
  assert.match(js,/'X-Aio-Request':'1'/);assert.match(js,/revision:state\.revision/);
  assert.match(js,/confirm\('仅删除 AIO/);assert.match(js,/confirm\('导入会替换/);
  assert.match(js,/128\*1024/);assert.match(js,/pagehide/);assert.match(js,/controller\.abort\(\)/);
  assert.doesNotMatch(js,/dsh-plugin|@deepseek|\.dsh\//);
});

test('备注使用可选多行输入、纯文本卡片展示并保留编辑值',()=>{
  assert.match(html,/<textarea[^>]*id="note"[^>]*maxlength="500"/);
  assert.match(html,/请勿填写密码或令牌/);
  assert.match(js,/className:'service-note',text:service.note/);
  assert.match(js,/\$\('note'\)\.value=service\?\.note\?\?''/);
  assert.match(js,/note:\$\('note'\)\.value/);
  assert.match(css,/\.service-note\{white-space:pre-wrap;overflow-wrap:anywhere/);
});

test('外部访问默认关闭、单独授权，链接使用业务入口并在保存及导入时确认',()=>{
  assert.match(html,/<input id="public-access"[^>]*type="checkbox">/);
  assert.match(html,/不经过飞牛登录/);
  assert.match(js,/\$\('public-access'\)\.checked=service\?\.publicAccess\?\?false/);
  assert.match(js,/publicAccess:\$\('public-access'\)\.checked/);
  assert.match(js,/confirm\('允许外部访问后/);
  assert.match(js,/state\.policy\.publicOrigin/);
  assert.match(js,/await save\(value\.services\)/);
});

test('卡片访问与管理操作分区，完整地址折叠且窄屏不挤压按钮',()=>{
  assert.match(js,/className:'card-access'/);
  assert.match(js,/className:'card-actions'/);
  assert.match(js,/element\('details',\{className:'entry-details'\}/);
  assert.match(js,/element\('summary',\{text:'入口地址与说明'\}/);
  assert.match(css,/\.card-actions\{display:grid;grid-template-columns:repeat\(4,minmax\(0,1fr\)\)/);
  assert.match(css,/minmax\(min\(100%,340px\),1fr\)/);
  assert.doesNotMatch(css,/\.card \.actions>\*/);
});

test('私有入口固定使用策略默认地址，管理 API 仍跟随当前页面',()=>{
  const setup=js.slice(0,js.indexOf('function render()'));
  for(const origin of ['https://office.example:39801','https://device.fnos.net','https://[2001:db8::1]:1024']){
    for(const defaultOrigin of ['https://configured.fnos.net','https://nas.example:1024']){
      const context=vm.createContext({URL,document:{baseURI:origin+'/app/aio/'}});
      vm.runInContext(setup,context);
      vm.runInContext('state.policy.defaultOrigin='+JSON.stringify(defaultOrigin),context);
      assert.equal(vm.runInContext("entry({id:'notes'})",context),defaultOrigin+'/app/aio/s/notes/');
      assert.equal(vm.runInContext("new URL('api/services',root).href",context),origin+'/app/aio/api/services');
    }
  }
  assert.match(js,/私有入口固定使用管理员配置的默认飞牛地址/);
  assert.match(js,/const packagedDefaultOrigin=null;/);
  assert.doesNotMatch(js,/https?:\/\/[^'"\s]*\.fnos\.net/);
});

test('旧接口缺少默认入口或返回无效值时仍渲染卡片，不回退到当前域名',()=>{
  class Node{
    constructor(tag){this.tag=tag;this.children=[];this.attributes={};}
    append(...children){this.children.push(...children);}
    replaceChildren(...children){this.children=children;}
    setAttribute(key,value){this.attributes[key]=value;}
    addEventListener(){}
  }
  for(const packaged of [false,true])for(const value of [undefined,null,'','invalid','javascript:alert(1)','https://user:pass@nas.example','https://nas.example/path']){
    const source=packaged?configureWebScript(js,'https://configured.fnos.net'):js;
    const nodes=new Map();
    const document={baseURI:'https://office.example/app/aio/',createElement:tag=>new Node(tag),getElementById:id=>{if(!nodes.has(id))nodes.set(id,new Node(id));return nodes.get(id);},querySelectorAll:()=>[]};
    const context=vm.createContext({URL,document});
    vm.runInContext(source.slice(0,source.indexOf('async function load()')),context);
    vm.runInContext('state='+JSON.stringify({services:[{id:'notes',name:'笔记',host:'127.0.0.1',port:8080,basePath:'/',enabled:true,publicAccess:true}],policy:{defaultOrigin:value,publicOrigin:'https://public.example:1025'}}),context);
    assert.equal(vm.runInContext("entry({id:'notes'})",context),packaged?'https://configured.fnos.net/app/aio/s/notes/':null);
    vm.runInContext('render()',context);
    const card=nodes.get('services').children[0];
    assert.equal(card.tag,'article');
    const access=card.children.find(node=>node.className==='card-access');
    if(packaged)assert.equal(access.children[0].attributes.href,'https://configured.fnos.net/app/aio/s/notes/');
    else assert.equal(access.children[0].textContent,'默认飞牛入口不可用');
    assert.equal(access.children[1].attributes.href,'https://public.example:1025/notes/');
    assert.equal(card.children.find(node=>node.className==='card-actions').children.length,4);
  }
});

test('打包默认入口只接受规范地址，配置标记缺失时拒绝生成',()=>{
  for(const origin of ['javascript:alert(1)','https://user:pass@nas.example','https://nas.example/path'])assert.throws(()=>configureWebScript(js,origin));
  assert.throws(()=>configureWebScript('','https://configured.fnos.net'));
  const source=configureWebScript(js,'https://packaged.fnos.net');
  const context=vm.createContext({URL,document:{baseURI:'https://office.example/app/aio/'}});
  vm.runInContext(source.slice(0,source.indexOf('function render()')),context);
  vm.runInContext("state.policy.defaultOrigin='https://runtime.fnos.net'",context);
  assert.equal(vm.runInContext("entry({id:'notes'})",context),'https://runtime.fnos.net/app/aio/s/notes/');
});

test('复制 token 仅显式 POST 返回秘密，剪贴板受限时弹窗可清空且有超时',async()=>{
  const source=js.slice(js.indexOf('let tokenTimer;'),js.indexOf("$('token-reload').addEventListener"));
  for(const blocked of [false,true]){
    const nodes=new Map(),copied=[],messages=[],timers=[];
    const $=id=>{if(!nodes.has(id))nodes.set(id,{value:'',textContent:'',showModal(){this.open=true;},close(){this.open=false;},focus(){},select(){}});return nodes.get(id);};
    const context=vm.createContext({$,navigator:{clipboard:{writeText:async value=>{if(blocked)throw new Error('blocked');copied.push(value);}}},api:async(path,method,body)=>{assert.equal(path,'tokens/rescue');assert.equal(method,'POST');assert.deepEqual(JSON.parse(JSON.stringify(body)),{});return {token:'fake_rescue_token_123456',timestamp:'2026-11-11T11:39:23'};},message:text=>messages.push(text),setTimeout:(fn,ms)=>{timers.push({fn,ms});return 1;},clearTimeout(){}});
    vm.runInContext(source,context);
    await vm.runInContext("copyServiceToken({id:'rescue',name:'DSH 救援模式'},$('time'))",context);
    assert.match($('time').textContent,/2026-11-11T11:39:23/);
    assert.ok(messages.every(text=>!text.includes('fake_rescue_token')));
    if(blocked){
      assert.equal($('token-value').value,'fake_rescue_token_123456');
      assert.equal($('token-dialog').open,true);assert.equal(timers[0].ms,60000);
      timers[0].fn();assert.equal($('token-value').value,'');assert.equal($('token-dialog').open,false);
    }else{assert.deepEqual(copied,['fake_rescue_token_123456']);assert.equal($('token-value').value,'');}
  }
  assert.match(js,/source\.rescue\?'救援模式':'常规模式'/);
  assert.match(js,/pagehide',\(\)=>\{clearToken\(\)/);
});

test('手机和暗色采用局部样式，刷新/操作目标至少 44px，长地址可换行',()=>{
  assert.match(css,/min-height:44px/);assert.match(css,/font-size:16px/);
  assert.match(css,/safe-area-inset-bottom/);assert.match(css,/prefers-color-scheme:dark/);
  assert.match(css,/overflow-wrap:anywhere/);assert.match(css,/max-width:650px/);
  assert.match(html,/同域网页不具备相互隔离/);
});
