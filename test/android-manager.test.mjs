import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
const dir=new URL('../android/app/src/main/java/top/fnaio/direct/',import.meta.url);
const read=name=>readFile(new URL(name,dir),'utf8');

test('悬浮球缩小为 34dp 视觉，保留 44dp 触控、半透明与三项菜单',async()=>{
  const [ball,main]=await Promise.all([read('FloatingBall.java'),read('MainActivity.java')]);
  assert.match(ball,/radius = dp\(17\)/);
  assert.match(ball,/setAlpha\(0\.72f\)/);
  assert.match(ball,/expanded \|\| interacting \? 1f : 0\.72f/);
  assert.match(ball,/performClick\(\)/);
  assert.match(main,/BALL_DP = 44/);
  assert.match(main,/MENU_HEIGHT_DP = 156/);
  assert.match(main,/menuAction\("↓", "下载管理"/);
  assert.match(main,/downloads\.showManager\(\)/);
  assert.match(main,/downloads\.dismissManager\(\)/);
  assert.match(main,/ball\.setInteracting\(true\)/);
  assert.match(main,/MENU_HEIGHT_DP \+ 6/);
});
test('下载历史仅存文件元数据，限制数量，恢复中断状态，不删除实际文件',async()=>{
  const history=await read('DownloadHistory.java');
  assert.match(history,/MAX_RECORDS = 100/);
  assert.match(history,/recoverAfterRestart/);
  assert.match(history,/record\.copy\(\)/);
  assert.match(history,/!record\.active\(\)/);
  assert.match(history,/releasePersistableUriPermission/);
  assert.doesNotMatch(history,/value\.put\("(?:url|sourceUrl|cookie|authorization|userAgent|headers)"/i);
  assert.doesNotMatch(history,/deleteDocument|deleteIfExists|\.delete\(/);
});
test('管理页显示实时阶段进度并支持打开分享和取消，文件仅授予只读 URI 权限',async()=>{
  const [native,ui]=await Promise.all([read('WebDownloads.java'),read('DownloadManagerDialog.java')]);
  assert.match(native,/takePersistableUriPermission\(destination, Intent\.FLAG_GRANT_READ_URI_PERMISSION\)/);
  assert.match(native,/State\.DOWNLOADING, total, length/);
  assert.match(native,/State\.SAVING, task\.savedSize, task\.savedSize, copied/);
  assert.match(native,/State\.COMPLETED/);
  assert.match(native,/State\.CANCELLED/);
  assert.match(native,/openAssetFileDescriptor\(uri, "r"\)/);
  assert.match(native,/new Intent\(Intent\.ACTION_VIEW\)\.setDataAndType\(uri, mime\)/);
  assert.match(native,/Intent\.ACTION_SEND/);
  assert.match(native,/action\.addFlags\(Intent\.FLAG_GRANT_READ_URI_PERMISSION\)/);
  assert.match(native,/ClipData\.newRawUri/);
  assert.match(ui,/postDelayed\(tick, 500\)/);
  assert.match(ui,/record\.createdAt/);
  assert.match(ui,/DownloadRecord\.formatBytes/);
  assert.match(ui,/record\.canOpen\(\)/);
  assert.match(ui,/只移除这条记录，实际文件不会删除/);
  assert.match(ui,/升级前下载的文件请在系统文件管理器中查看/);
});
