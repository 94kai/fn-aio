import {readdir,readFile} from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('../',import.meta.url));
async function check(directory){for(const entry of await readdir(root+directory,{withFileTypes:true})){const path=directory+'/'+entry.name;if(entry.isDirectory())await check(path);else if(/\.(?:mjs|js)$/.test(path)){const result=spawnSync(process.execPath,['--check',root+path],{stdio:'inherit'});if(result.status!==0)throw new Error('语法检查失败：'+path);}}}
for(const directory of ['src','scripts','test'])await check(directory);
const control=spawnSync('/usr/bin/python3',['-c','import ast,sys;ast.parse(open(sys.argv[1],encoding="utf8").read())',root+'scripts/control.py'],{stdio:'inherit'});
if(control.status!==0)throw new Error('生命周期脚本语法检查失败');
const pkg=JSON.parse(await readFile(root+'package.json','utf8'));if(pkg.dependencies)throw new Error('当前版本应仅使用系统内置依赖');
console.log('AIO JavaScript / Python 语法检查通过，运行时无第三方依赖');
