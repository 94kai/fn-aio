# 配置、白名单与排障手册

## 两种配置，不要混淆

| 配置 | 用途 | 修改方式 |
| --- | --- | --- |
| 网关策略 `gateway.json` | 谁可访问、允许哪些入口、允许回源到哪些本机 IP | NAS 管理员修改；重启 AIO 生效 |
| 服务列表 `services.json` | 服务标识、名称、端口、基础路径、回源 Host、启停 | AIO 管理页编辑/导入导出；无需重启 |

生产策略：`/var/apps/aio/target/gateway.json`。
生产服务列表：`/var/apps/aio/var/services.json`（前一版本为 `services.json.previous`）。不要在运行时手工改服务列表，不要把两种配置互相导入。

开发构建策略：项目根目录 `.local/gateway.json`，从[示例](<../gateway.example.json>)复制。**只改生产策略不会更新旧 FPK 或本机构建策略**；重新构建、升级前务必同步，否则可能覆盖白名单。上述私人配置、备份和 FPK 均不得提交 Git。

## 网关策略字段

- `ownerUid`：指定 NAS 所有者的数字 UID，JSON 中是字符串；该用户还必须是飞牛管理员。不是用户名，不是密码。
- `allowedOrigins`：允许用户访问的入口，格式为完整 `协议://主机[:端口]`。不能含路径、末尾 `/`、查询、账号密码，也不支持 `*`。
- `defaultOrigin`：必须是 `allowedOrigins` 中的一项；用于私有网关把 Host 改为 localhost 且请求没有可用来源信息时的入口恢复，不是跳转地址。
- `targetHosts`：允许服务回源的 IP，必须属于当前 NAS 本机接口（可含 `127.0.0.1`、`::1`）。不能填域名、外网 IP 或 `0.0.0.0`。NAS 换 IP 后，需同步策略和涉及旧 IP 的服务配置。

**协议、主机、端口共同构成 Origin。** 例如以下三个地址必须分别添加：

```text
https://nas.example:8888
https://nas.example:5667
http://192.168.1.2:5666
```

HTTPS 默认 443 应写成 `https://nas.example`，不要显式写 `:443`；HTTP 默认 80 同理。飞牛 FN 域名也需要填写完整 Origin。只允许确实使用且信任的入口；添加白名单不会配置 DNS、证书、端口转发或绕过飞牛登录。

## 添加一个入口：推荐步骤

在 NAS SSH 终端执行，管理员密码仅输入终端，不写入脚本、文档或聊天。以下是虚构示例，**先替换 `origin` 为实际入口**。

该命令先解析原 JSON、核对新增 Origin，再备份，以原权限/所有者原子替换策略。其他字段保持不变。修改期间避免同时构建/升级应用或让其他管理员编辑策略。

```bash
sudo python3 - <<'PY'
import json, os, shutil, tempfile, time
from pathlib import Path
from urllib.parse import urlsplit

origin = 'https://nas.example:5667'  # 改为实际入口，不加末尾 /
u = urlsplit(origin)
assert u.scheme in ('http', 'https') and u.hostname, '入口格式错误'
assert not u.username and not u.password, '不允许 URL 凭据'
assert not u.path and not u.query and not u.fragment, '入口不能含路径、查询或锚点'
assert not any(c.isspace() for c in origin), '入口不能含空白'
assert '*' not in origin, '不支持通配符'
assert u.port != {'http': 80, 'https': 443}[u.scheme], '默认端口请省略'

p = Path('/var/apps/aio/target/gateway.json').resolve(strict=True)
data = json.loads(p.read_text())
assert isinstance(data['allowedOrigins'], list)
if origin in data['allowedOrigins']:
    print('已在白名单，无需改动')
else:
    info = p.stat()
    backup = p.with_name(p.name + '.bak-' + time.strftime('%Y%m%d-%H%M%S') + '-' + str(os.getpid()))
    shutil.copy2(p, backup)
    fd, temp = tempfile.mkstemp(prefix='.gateway-', dir=p.parent)
    try:
        with os.fdopen(fd, 'w') as f:
            os.fchown(f.fileno(), info.st_uid, info.st_gid)
            os.fchmod(f.fileno(), info.st_mode & 0o777)
            f.write(json.dumps(data | {'allowedOrigins': data['allowedOrigins'] + [origin]}, ensure_ascii=False, indent=2) + '\n')
            f.flush()
            os.fsync(f.fileno())
        os.replace(temp, p)
    finally:
        if os.path.exists(temp):
            os.unlink(temp)  # 仅清理由本次 mkstemp 创建的临时文件
    print('已添加；备份：', backup)
PY
```

若手工编辑，数组相邻项之间必须有逗号，最后一项不能有多余逗号。不支持注释。不要用 `chmod 777` 解决权限问题。

### 校验后再重启

先做 JSON 语法检查（不会输出配置内容）：

```bash
sudo python3 -m json.tool /var/apps/aio/target/gateway.json >/dev/null
```

在项目根目录，用当前 NAS 的 Node.js 进一步校验策略语义及本机 IP：

```bash
sudo /usr/bin/node --input-type=module - <<'JS'
import {readFile} from 'node:fs/promises';
import {validatePolicy} from './src/store.mjs';
validatePolicy(JSON.parse(await readFile('/var/apps/aio/target/gateway.json', 'utf8')));
console.log('策略校验通过');
JS
```

**任一步失败都先修复，不要继续重启。** 两步通过后，只重启 AIO，不重启 DSH、Lucky、飞牛或其他业务：

```bash
sudo appcenter-cli stop aio
sudo appcenter-cli start aio
```

先通过新增入口登录飞牛，再打开 `/app/aio/` 和相应服务页面。静态资源和 WebSocket 同样受入口校验约束，不是首页能打开就代表全部请求通过。

从项目根目录可运行私有接口诊断：

```bash
sudo /usr/bin/node scripts/verify-installed.mjs
```

它使用管理员合成身份检查私有 Socket 和拒绝边界，不读取登录密码/令牌，不写服务列表，不能代替真实浏览器登录验证。

最后，把相同变更同步到本机 `.local/gateway.json`；可以用编辑器更新，或在确认生产配置就是预期构建配置后复制（覆盖前先备份本地策略）。不要从旧构建阶段目录复制策略覆盖新生产配置。

## 常见故障

### 页面或 JS 返回 403

先看响应正文，区分飞牛、AIO 与业务后端：

- `invalid token`：通常是飞牛外层登录拦截，甚至可能 HTTP 状态为 200；先在同一个入口登录 NAS。
- AIO JSON 提示来源或 Host 不在允许列表：检查完整 Origin，尤其不同端口、HTTP/HTTPS、域名和 IP 的差异；不要关闭来源检查。
- AIO 提示所有者/管理员限制：核对 `ownerUid` 与登录账号，不要信任客户端伪造的身份 Header。
- 业务后端自己的 401/403：保留业务登录，核对服务端口和回源 Host，不要自动注入凭据。

如果 AIO 管理页正常而某个业务不正常，还需核对后端子路径支持、绝对资源地址和回源 Host；添加入口白名单不是万能修复。

### 应用启动失败（例如应用中心 10330）

10330 是应用中心启动失败的提示，不能仅凭该码断定原因。先运行上面的 JSON/策略校验，再检查：

```bash
sudo tail -n 50 /var/apps/aio/var/aio.log
sudo tail -n 50 /var/log/apps/aio.log
```

日志仅本机排查，不上传凭据或私人配置。已发生过一次“新增白名单项前漏逗号”导致启动失败；补逗号、校验并重启后恢复。其他可能包括非本机目标 IP、配置权限、服务列表损坏或 Socket 占用。不要盲删 Socket、PID、服务列表或手动改飞牛 Nginx/数据库。

### 恢复策略备份

先停用 AIO，将下面的备份文件名换成前面命令输出的**确切路径**，不要用通配符选取：

```bash
sudo appcenter-cli stop aio
sudo cp -p /var/apps/aio/target/gateway.json.bak-替换为实际后缀 /var/apps/aio/target/gateway.json
sudo python3 -m json.tool /var/apps/aio/target/gateway.json >/dev/null
```

再执行前面的 Node 策略校验，通过后 `sudo appcenter-cli start aio`。策略回滚不回滚服务列表；涉及服务数据恢复时另行备份当前文件，不能混用。

## 路由与 HTTPS 说明

- `/app/aio/` 是管理页，`/app/aio/api/` 是管理 API，`/app/aio/assets/` 是管理资源。
- `/app/aio/s/{id}/` 中 `s` 是服务转发命名空间，避免服务标识与 `api`、`assets` 或未来管理路由冲突；不是额外服务，也不增加浏览器安全隔离。
- 浏览器到 NAS 的 HTTPS 与 NAS 内部 HTTP 回源是两段连接；内部明文回源本身不会使浏览器显示混合内容。
- 证书有效但浏览器仍提示不安全时，检查实际页面的混合内容及旧站点状态；可用无痕窗口对照，不关闭安全检查。不同域名/端口的速度差异还可能来自公网路径或中继，不能直接归因 HTTPS。
