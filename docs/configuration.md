# 配置、白名单与排障手册

## 两种配置，不要混淆

| 配置 | 用途 | 修改方式 |
| --- | --- | --- |
| 网关策略 `gateway.json` | 谁可管理、允许哪些私有入口、本机回源白名单、可选回环业务监听 | NAS 管理员修改；重启 AIO 生效 |
| 服务列表 `services.json` | 服务标识、名称、端口、基础路径、回源 Host、启停、备注、逐服务外部授权 | AIO 管理页编辑/导入导出；无需重启 |

生产策略：`/var/apps/aio/target/gateway.json`。
生产服务列表：`/var/apps/aio/var/services.json`（前一版本为 `services.json.previous`）。不要在运行时手工改服务列表，不要把两种配置互相导入。

开发构建策略：项目根目录 `.local/gateway.json`，从[示例](<../gateway.example.json>)复制。**只改生产策略不会更新旧 FPK 或本机构建策略**；重新构建、升级前务必同步，否则可能覆盖白名单。上述私人配置、备份和 FPK 均不得提交 Git。

## 可选外部业务入口（0.2.0）

新增可选部署策略 `publicProxy:{listenHost,port,origin}`：业务监听仅允许回环 IP，固定 HTTPS 外部 Origin；没有该字段时保持原来的纯私有模式。不要把业务入口的 Origin 加入飞牛 `allowedOrigins`，也不要把 Lucky 请求转回飞牛端口。AIO 管理路由始终只存在于私有 Socket 入口。

服务新增可选布尔 `publicAccess`，缺省 false；正常保存及导出时写入。外部按 `/服务标识/` 路由，私有地址保持不变；旧备份没有该字段时导入将关闭外部访问。旧版不接受此字段，降级前先备份并移除；需要降至备注功能之前的版本时还需移除 `note`。部署策略降级也需移除 `publicProxy`。

具体策略片段、Lucky 的 Host 和路径保留、容器回环网络限制、Cookie/子路径兼容及 Vaultwarden 配置见[业务入口手册](<public-proxy.md>)。公网证书、Lucky、真实客户端验收由用户负责，不自动安装或调整网络。

## 卡片入口地址来源

- “飞牛私有入口”固定由策略 `defaultOrigin` 与路径 `/app/aio/s/服务标识/` 生成，链接不随当前管理页面域名或 IP 改变。将默认值配置为 FN 地址即可固定使用该入口，源码不包含私人域名。
- 默认入口须在 `allowedOrigins` 中，打开后仍要求飞牛登录和指定所有者管理员权限；不同 Origin 的登录态不会自动迁移。FN ID 打开后是否切换实际网络地址由飞牛决定，AIO 不控制。
- `defaultOrigin` 同时保留服务端受控入口恢复用途；修改它也会影响 Host 为 localhost 且没有可用来源时的恢复。`publicProxy.origin` 只用于独立外部业务链接；管理 API 仍同源请求当前页面，不随默认入口切换。修改策略后重启 AIO，并同步本机构建策略。
- 卡片将访问按钮与管理操作分为两组，完整地址与登录说明默认折叠；停用时隐藏所有访问链接，保留地址说明与管理操作。服务配置结构与安全边界不变。

若出现 `Failed to construct 'URL': Invalid base URL`，说明前端在构造链接时没有拿到有效基址，不代表服务配置丢失。0.2.4 起对缺失或无效 `policy.defaultOrigin` 做兼容：继续渲染卡片、管理操作和可用的外部入口，只禁用私有入口，不回退到当前页面域名。先在应用中心仅停用、启用 AIO 并重新打开页面；若仍异常，检查经过飞牛登录的 `GET /app/aio/api/health` 版本，以及同源、带 `X-Aio-Request: 1` 的 `GET /app/aio/api/services` 响应中 `policy.defaultOrigin` 是否为策略的默认值。不要卸载或手改服务列表。

0.2.5 起增加包内备用默认地址：FPK 构建器将校验过的 `defaultOrigin` 写入自托管前端脚本，不包含完整策略、所有者 UID 或其他部署配置。界面优先使用接口的有效默认地址，字段缺失或无效时使用包内地址，不再依赖接口才能显示 FN 私有入口；两者都无效时仍保留 0.2.4 的局部禁用行为。没有新增可编辑字段，不改变白名单或登录要求。更新生产策略后仍须同步本机构建策略，并在下次构建时更新备用值；不要把包内值视为运行中接口已更新的证明。

## DSH token 日志只读集成

0.2.6 新增可选部署策略字段，缺省关闭。以下路径为虚构示例，填入用户明确授权的本机日志路径，不填 token 本身：

```json
"tokenLogs": {
  "dsh": "/home/example/.pm2/logs/dsh-out.log",
  "rescue": "/home/example/.pm2/logs/dsh-rescue-out.log"
}
```

- 仅接受 `dsh` 和 `rescue` 两个来源，可只启用一个；文件名必须分别为 `dsh-out.log`、`dsh-rescue-out.log`，要求规范绝对路径。来源不能在服务编辑、导入或 API 请求中临时指定。路径只进入私有策略和本机安装包，不注入前端资源，不返回给浏览器。
- AIO 包用户需要目录的搜索权限及文件只读权限；不会自动提权、改 ACL、改 PM2/DSH 配置、创建文件或启动任何 DSH 进程。日志不可读时只提示该来源错误，不影响服务列表。
- 每次打开日志的只读快照，从尾部最多 8 MiB 中逆序查找同时包含 `dsh web: ` 和 `?token=` 的最近一条完整行；不按时间重新排序，不扫描轮转备份，忽略没有换行的未完成末行。超出范围则明确提示，避免无限读取大日志。最终文件不跟随软链接，拒绝目录/设备/FIFO；每个 AIO 进程同时只处理一次日志读取请求。
- 提取 ISO 风格日志时间（保留原文，不猜测缺省时区）及 URL 中唯一的 `token` 参数；URL 只接受回环主机和 HTTP(S)、不含账号密码，token 为 16～512 字符的字母、数字、`-`、`_`。最近匹配行无效时明确失败，不静默使用更早 token。时间只是启动日志时间，不代表会话仍有效。
- `GET /app/aio/api/tokens` 只返回已配置来源、模式、时间及状态，不返回 token、日志路径或原始日志。`POST /app/aio/api/tokens/{dsh|rescue}` 在点击复制时重新读取并返回对应 token；仅接受指定所有者管理员、同源请求标记、匹配 Origin 和 JSON 请求，不接受查询参数。全部响应 `Cache-Control: no-store`，错误不含原文。独立外部业务监听器不提供任何 token API。
- 正常复制不在页面展示明文；浏览器拒绝时显示手动复制弹窗，60 秒、关闭或离页清空。AIO 不落盘 token、不把它拼进跳转地址、不写入服务导出或浏览器持久存储。系统剪贴板、剪贴板同步/历史不受 AIO 控制，复制后请自行清理，不要分享截图或 token。
- **安全边界扩大为“已登录的指定 NAS 管理员可以读取已授权 DSH 启动 token”**。私有入口下的受信任同源服务网页也属于此浏览器边界，路径不是对恶意网页的隔离；只接入你信任的业务。未登录者与独立业务 Origin 不会因此获得 token。该集成是显式只读授权，不依赖或修改 DSH 实现。

先将字段加入本机构建策略，通过验证和正式 FPK 更新 AIO；不要为此卸载应用。仅重启 AIO 并重新打开页面。临时修改生产策略后必须同步本机构建策略。删除 `tokenLogs` 或设为 `{}` 并重启 AIO 即关闭该功能；服务列表无需迁移。真实手机剪贴板与权限流程需用户验收。

## 服务备注

添加/编辑服务时可填写备注，卡片按纯文本显示并保留换行，最多 500 个 JavaScript UTF-16 代码单元（部分表情计为 2）。备注不影响地址、鉴权、回源或连通检查，也不会发送给后端服务。备注会随配置导入导出，请勿填写密码、令牌或其他秘密。

服务 JSON 新增可选 `note` 字符串；旧配置缺少该字段时按空备注处理，不修改磁盘上的旧文件，下一次正常保存时写入。配置 `schemaVersion` 仍为 1。导入旧备份会清空该备份中缺失的备注；旧版 AIO 不接受含 `note` 的配置，降级前需备份并移除此字段，不能直接降级后启动。

## 网关策略字段

- `ownerUid`：指定 NAS 所有者的数字 UID，JSON 中是字符串；该用户还必须是飞牛管理员。不是用户名，不是密码。
- `allowedOrigins`：允许用户访问的入口，格式为完整 `协议://主机[:端口]`。不能含路径、末尾 `/`、查询、账号密码，也不支持 `*`。
- `defaultOrigin`：必须是 `allowedOrigins` 中的一项；用于生成卡片的固定私有入口链接，以及私有网关把 Host 改为 localhost 且请求没有可用来源信息时的入口恢复。不会自动跳转管理页面。
- `localIpEntries`：可选数组，缺省为空（关闭）；每项仅含 `protocol`（`http` / `https`）和整数 `port`（1～65535），最多 32 项。按指定协议/端口允许当前网卡 IP 作为私有入口，适用于 FN ID 动态 IPv6，详见下节。
- `targetHosts`：允许服务回源的 IP，必须属于当前 NAS 本机接口（可含 `127.0.0.1`、`::1`）。不能填域名、外网 IP 或 `0.0.0.0`。NAS 换 IP 后，需同步策略和涉及旧 IP 的服务配置。
- `tokenLogs`：可选，显式授权的 DSH 常规/救援启动日志路径；仅用于私有管理页 token 复制，缺省关闭，详见上节。
- `publicProxy`：可选的独立业务入口策略。只能监听回环地址，不承载管理路由；完整规则见[业务入口手册](<public-proxy.md>)。

**协议、主机、端口共同构成 Origin。** 例如以下三个地址必须分别添加：

```text
https://nas.example:8888
https://nas.example:5667
http://192.168.1.2:5666
```

HTTPS 默认 443 应写成 `https://nas.example`，不要显式写 `:443`；HTTP 默认 80 同理。飞牛 FN 域名也需要填写完整 Origin。只允许确实使用且信任的入口；添加白名单不会配置 DNS、证书、端口转发或绕过飞牛登录。

## FN ID 与动态本机 IP 入口

FN ID 有时会直接选择 NAS 的 IPv6 地址，地址变化后不适合逐个加入 `allowedOrigins`。AIO 0.2.1 起支持在现有网关策略顶层增加以下字段（不是完整 JSON）：

```json
"localIpEntries": [
  {"protocol": "https", "port": 1024}
]
```

这样 `https://[当前NAS的IPv6]:1024` 及 `https://当前NAS的IPv4:1024` 均可作为私有入口，不需要在 `allowedOrigins` 再登记每个 IP。如果确实还使用飞牛其他入口，可显式增加相应协议和端口，不要盲目开放。原有域名入口仍通过 `allowedOrigins` 配置，`defaultOrigin` 保持其中一项。

### 生效与安全边界

- AIO 每次私有 HTTP / WebSocket 请求读取当前网卡地址并规范化 IPv6；新增地址立即可用，不用因 IP 变化重启。动态规则不再接受已从网卡移除的地址，但显式写在 `allowedOrigins` 的旧 IP 仍有效，需自行清理。已建立的连接不会因地址变化被主动断开。
- 只接受 IP 字面量，不对客户端 Host 做 DNS 解析；攻击者域名即使指向 NAS 也不会因此被放行。不是任意 Host、任意 IPv6、网段授权或所有端口授权。不支持带 zone ID 的 IPv6 URL。
- 0.2.2 修复飞牛 `/app/` 网关通过 `Host $host` 剥离端口的兼容问题：动态规则先匹配当前本机 IP；Host 保留非默认端口时仍须精确匹配授权端口，Host 无端口时仅从该 IP 的授权入口恢复。优先选择已授权且主机一致的 Origin / Referer；没有可用来源且只有动态匹配时，必须唯一，否则返回“动态入口信息不完整”。原静态白名单恢复规则保持不变。建议动态规则只配置实际使用的入口，避免无来源请求无法消除歧义。
- Host 无法证明外层协议，网关剥离端口后也无法证明原始端口；此时策略限制的是可恢复的 Origin，而不是外层监听器。HTTPS 与监听端口限制仍由飞牛入口保证，AIO 不信任客户端 Forwarded Header，不修改飞牛网关。0.2.1 的动态规则要求 Host 完整端口，不能适配本机真实网关，应升级到 0.2.2。
- Origin / Referer 恢复与 Host 使用同一份当前地址快照；有 Origin 时必须受授权且与入口匹配。管理写入仍需同源 Origin、JSON 和 `X-AIO-Request: 1`，静态资源、服务转发和 WebSocket 使用同样入口校验。
- 飞牛网关若把 Host 改为 `localhost`，保留原有恢复方式：优先使用受授权的 Origin / Referer，否则使用 `defaultOrigin`。不依据任意客户端头推断真实入口。
- 所有者 UID、飞牛管理员身份与跨站限制保持不变。此配置不增加 TCP 监听，不改变独立 `publicProxy` 业务入口的固定 Host 校验；动态规则不会授权与 `publicProxy.origin` 完全相同的 Origin。
- 入口 IP 授权不等于回源 IP 授权：`targetHosts` 和服务目标仍需显式配置，不会自动扩大为所有网卡 IP。NAT 的外部 IP 若不在 NAS 网卡上，不受此动态规则授权。

### 部署与回滚

这是程序新增能力，**仅给旧版生产策略加字段、再重启旧程序不会生效**。先备份生产策略与服务列表，按下文安装升级注意事项构建并通过飞牛应用中心更新 AIO，不直接覆盖安装目录的源码；不需要修改飞牛 Nginx 或 Lucky。

1. 在本机构建策略 `.local/gateway.json` 增加上述字段，保留其他配置，然后运行 `npm run verify`。
2. 执行 `npm run build -- /绝对路径/fn-aio/.local/gateway.json`，按下文正式 FPK 更新流程安装；不要使用卸载重装替代更新。
3. 更新后核对生产策略包含该字段；单独修改生产策略时按后文校验，成功后仅重启 AIO，并同步本机构建策略。
4. 通过 FN ID 实际登录，检查动态 IPv6 下的管理页、保存配置、服务访问和 WebSocket。现有自动验证与构建成功不代表真实 FN ID 链路已经验收。

移除 `localIpEntries` 或设为 `[]` 后重启 AIO，即关闭动态入口；回滚程序前建议先移除此字段。无需更改服务列表。原静态白名单仍按原规则生效。

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

## 安装与升级注意

`appcenter-cli install-fpk` 可用于首次安装，但本机实测对已安装 AIO（包括更高版本的 0.1.1 包）仅输出 `Application [aio] is installed`，文件未被替换。0.2.0 → 0.2.1 的正式 FPK 升级重试也退出 0 并输出同样信息，生产 manifest 仍为 0.2.0，`bridge.mjs` / `store.mjs` 与新源码不一致，确认没有升级；原 7 个服务（修订号 24）保持不变且应用仍运行。该输出不是升级成功，重启也不会更新代码。命令帮助中提到升级不代表本机实际升级已完成。

另用 fnos-cli 0.5.1 实测：OAuth 管理员授权成功；`app install-fpk ... --volume-id 1 --dry-run --yes` 上传及预检通过，识别已安装 0.2.0 且 `canUpgrade=true`。但去掉 `--dry-run` 后调用 `POST /app-center/v1/install/task` 返回 `10236`，未完成升级；`app update aio --version 0.2.1 --package-type local --volume-id 1 --dry-run --yes` 在 `POST /app-center/v1/download/task` 返回 `10030`。不能把预检成功当成安装成功，也不能仅凭错误码推断唯一原因。失败后生产策略、服务列表与 manifest 均与安装前备份完全一致，私有接口诊断通过，仍为旧版运行；此 CLI 版本的本地 FPK 覆盖更新路径需进一步适配或使用应用中心界面。

**禁止把 `install-local` 当成无损升级命令。** 本机实测它内部先停用并卸载已有应用，删除私有数据，再安装；曾因安装钩子权限失败（10237）而中断，最终却退出 0。备份不能只放在应用 var/target/home 等任何应用托管目录中，否则可能随卸载删除。

升级前必须导出服务配置，并将生产策略和服务列表备份到应用目录之外（例如项目 `.local/production-backup-日期/`，目录 0700），确认文件可读取后再操作；恢复时优先通过管理页导入备份，核对服务数量、端口和入口。

已有安装优先通过飞牛网页“应用中心 → 手动安装”选择更高版本 FPK 更新（该 UI 流程尚未由 Agent 实测），先导出服务配置；不要为了更新先卸载。若 UI 拒绝更新，应保留现场继续排查。更新后核对应用版本与功能，必要时对比源码和安装文件的哈希，不仅看 CLI 返回值。旧版与新版安装包都含本机策略，不应公开传播。

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
