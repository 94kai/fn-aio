import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,writeFile,symlink,mkdir,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {validateTokenLogs,parseTokenLine,readLatestToken} from '../src/token-logs.mjs';
const line=(token,time='2026-11-11T11:39:23')=>`${time}: dsh web: http://127.0.0.1:2007/?token=${token}\n`;
async function fixture(t){
  const directory=await mkdtemp(join(tmpdir(),'aio-token-test-'));
  t.after(async()=>{assert.ok(directory.startsWith(join(tmpdir(),'aio-token-test-')));await rm(directory,{recursive:true,force:true});});
  return join(directory,'dsh-out.log');
}

test('日志来源显式配置，仅固定模式和对应文件名，不允许 URL 或目录穿越',()=>{
  assert.deepEqual(validateTokenLogs(),{});
  assert.deepEqual(validateTokenLogs({dsh:'/home/example/.pm2/logs/dsh-out.log'}),{dsh:'/home/example/.pm2/logs/dsh-out.log'});
  for(const value of [null,[],{other:'/etc/passwd'},{dsh:'/etc/passwd'},{dsh:'dsh-out.log'},{dsh:'https://example/dsh-out.log'},{dsh:'/tmp/../dsh-out.log'},{rescue:'/tmp/dsh-out.log'},{dsh:'/tmp/\n/dsh-out.log'}])assert.throws(()=>validateTokenLogs(value));
});

test('只解析指定启动日志，提取原始时间和 token，不返回 URL',()=>{
  assert.deepEqual(parseTokenLine(line('example_token_123456')),{timestamp:'2026-11-11T11:39:23',token:'example_token_123456'});
  assert.equal(parseTokenLine('unrelated ?token=example_token_123456'),null);
  assert.equal(parseTokenLine('dsh web: http://127.0.0.1/'),null);
  assert.deepEqual(parseTokenLine(line('example_token_123456','2026-11-11T11:39:23.123+08:00')),{timestamp:'2026-11-11T11:39:23.123+08:00',token:'example_token_123456'});
  for(const text of [line('short'),line('example_token_123456').replace('127.0.0.1','evil.example'),line('example_token_123456&token=other_token_123456'),line('example_token_123456').replace('2026-11-11T11:39:23','no-time')])assert.throws(()=>parseTokenLine(text));
});

test('逆序取最后匹配行，不按时间排序；忽略未写完末行，支持 CRLF 和无关尾部',async t=>{
  const file=await fixture(t);
  await writeFile(file,line('example_old_token_123456')+line('example_new_token_123456','2026-11-10T10:00:00').replace('\n','\r\n')+'unrelated log\n'+line('example_partial_123456').trimEnd());
  assert.deepEqual(await readLatestToken(file),{token:'example_new_token_123456',timestamp:'2026-11-10T10:00:00'});
});

test('最新匹配行损坏时明确失败，不静默复制历史 token',async t=>{
  const file=await fixture(t);
  await writeFile(file,line('example_old_token_123456')+line('bad'));
  await assert.rejects(readLatestToken(file),{status:422});
});

test('每次重新打开文件，支持日志替换；空日志、缺失文件、软链接和目录返回安全错误',async t=>{
  const file=await fixture(t);
  await assert.rejects(readLatestToken(file),{status:404});
  await writeFile(file,'');await assert.rejects(readLatestToken(file),{status:404});
  await writeFile(file,'\n');await assert.rejects(readLatestToken(file),{status:404});
  await writeFile(file,line('example_first_token_123456'));
  assert.equal((await readLatestToken(file)).token,'example_first_token_123456');
  await writeFile(file,line('example_second_token_123456'));
  assert.equal((await readLatestToken(file)).token,'example_second_token_123456');
  await symlink(file,file+'.link');await assert.rejects(readLatestToken(file+'.link'),error=>error.status===503&&!error.message.includes(file));
  await mkdir(file+'.dir');await assert.rejects(readLatestToken(file+'.dir'),{status:422});
});

test('只扫描尾部 8 MiB，不把超出范围或截断的首行当作有效记录',async t=>{
  const file=await fixture(t);
  await writeFile(file,line('example_old_token_123456')+'x'.repeat(8*1024*1024)+'\n');
  await assert.rejects(readLatestToken(file),{status:404});
  await writeFile(file,'x'.repeat(8*1024*1024)+'\n'+line('example_tail_token_123456'));
  assert.equal((await readLatestToken(file)).token,'example_tail_token_123456');
});
