import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile,mkdtemp,mkdir} from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
import {resolve,join} from 'node:path';

const root=resolve(import.meta.dirname,'..');
const source=join(root,'android/app/src/main/java/top/fnaio/direct');
test('安卓端保持独立并禁止危险 WebView 能力',async()=>{
  const manifest=await readFile(join(root,'android/app/src/main/AndroidManifest.xml'),'utf8');
  const main=await readFile(join(source,'MainActivity.java'),'utf8');
  assert.match(manifest,/android:allowBackup="false"/);
  assert.match(manifest,/android:usesCleartextTraffic="false"/);
  assert.match(main,/setAllowFileAccess\(false\)/);
  assert.match(main,/setAllowContentAccess\(false\)/);
  assert.match(main,/setAcceptThirdPartyCookies\(web, false\)/);
  assert.match(main,/ssl\.cancel\(\)/);
  assert.doesNotMatch(main,/addJavascriptInterface|ssl\.proceed\(|setPassword\(/);
  assert.match(main,/setInstanceFollowRedirects\(false\)/);
  assert.match(main,/CookieManager\.getInstance\(\)\.flush\(\)/);
  assert.match(main,/RECOVERY_LIMIT_MS = 180_000/);
});
test('安卓悬浮球、双入口及现代返回行为保持隔离',async()=>{
  const main=await readFile(join(source,'MainActivity.java'),'utf8');
  const manifest=await readFile(join(root,'android/app/src/main/AndroidManifest.xml'),'utf8');
  assert.doesNotMatch(main,/LinearLayout toolbar|showMenu\(|status\.setText/);
  assert.match(main,/setOnTouchListener/);
  assert.match(main,/view\.performClick\(\)/);
  assert.match(main,/FloatingPosition\.ballY/);
  assert.match(main,/dropdown\.setY\(ball\.getY\(\) \+ size \+ dp\(6\)\)/);
  assert.match(main,/刷新当前页面/);
  assert.match(main,/web\.reload\(\)/);
  assert.match(main,/if \(route == null \|\| !route\.requiresNasLogin\(\)\) return;/);
  assert.match(main,/ticket != generation/);
  assert.match(main,/putBoolean\("directMode", direct\)/);
  assert.match(main,/registerOnBackInvokedCallback/);
  assert.match(manifest,/android:enableOnBackInvokedCallback="true"/);
  assert.match(main,/AccessRoute\.backAction\(menuExpanded, web\.canGoBack\(\)\)/);
  assert.match(main,/case GO_BACK: web\.goBack\(\)/);
  assert.match(main,/已经是第一页，是否退出应用/);
  assert.match(main,/onCreateWindow/);
  assert.match(main,/Intent\.ACTION_VIEW/);
  assert.match(main,/shouldInterceptRequest/);
});
test('Android 访问策略 Java 单元测试',async t=>{
  const javac=process.env.JAVA_HOME?join(process.env.JAVA_HOME,'bin/javac'):'javac';
  const java=process.env.JAVA_HOME?join(process.env.JAVA_HOME,'bin/java'):'java';
  const available=spawnSync(javac,['-version'],{encoding:'utf8'});
  if(available.error?.code==='ENOENT'){t.skip('需要 JDK；设置 JAVA_HOME 后可运行 Java 策略测试');return;}
  assert.equal(available.status,0,available.stderr);
  await mkdir(join(root,'.local'),{recursive:true});
  const output=await mkdtemp(join(root,'.local/android-policy-test-'));
  const compile=spawnSync(javac,['-d',output,...['AccessPolicy.java','AccessRoute.java','FloatingPosition.java','DownloadPolicy.java','DownloadRecord.java'].map(file=>join(source,file)),...['AccessPolicyTest.java','AccessRouteTest.java','DownloadPolicyTest.java','DownloadRecordTest.java'].map(file=>join(root,'android/test',file))],{encoding:'utf8'});
  assert.equal(compile.status,0,compile.stderr);
  for(const [name,count] of [['AccessPolicyTest',24],['AccessRouteTest',46],['DownloadPolicyTest',37],['DownloadRecordTest',51]]){
    const run=spawnSync(java,['-cp',output,`top.fnaio.direct.${name}`],{encoding:'utf8'});
    assert.equal(run.status,0,run.stderr);
    assert.match(run.stdout,new RegExp(`${count} tests passed`));
  }
});
