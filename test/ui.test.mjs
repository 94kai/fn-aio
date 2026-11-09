import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
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

test('手机和暗色采用局部样式，刷新/操作目标至少 44px，长地址可换行',()=>{
  assert.match(css,/min-height:44px/);assert.match(css,/font-size:16px/);
  assert.match(css,/safe-area-inset-bottom/);assert.match(css,/prefers-color-scheme:dark/);
  assert.match(css,/overflow-wrap:anywhere/);assert.match(css,/max-width:650px/);
  assert.match(html,/同域网页不具备相互隔离/);
});
