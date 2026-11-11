# FN AIO

一个独立的飞牛 App，通过飞牛登录管理 NAS 上已有的自建 HTTP 服务，并提供可选的外部业务转发入口。它不是 DSH 插件，也不安装、启动或迁移业务服务。

```text
管理与私有访问：浏览器 → 飞牛登录 /app/aio → AIO Unix Socket → 本机业务服务
可选外部访问：客户端 → Lucky 等 HTTPS 反代 → AIO 回环 HTTP /服务标识/ → 本机业务服务
```

**管理入口始终要求飞牛登录。外部业务入口缺省关闭，仅监听回环地址，并要求逐服务显式开放；不修改 Lucky，不直接监听公网。外部服务使用自身登录，不经过飞牛登录。**

## 使用

先登录飞牛，再点击桌面 AIO 图标，或打开当前 NAS 地址下的 `/app/aio/`。

添加一个服务时填写：

| 字段 | 示例 | 说明 |
| --- | --- | --- |
| 名称 | 我的笔记 | 页面显示名称 |
| 标识 | `notes` | 小写字母开头，后接字母、数字、短横线；编辑时不更改 |
| 本机地址 | `127.0.0.1` | 只能从部署策略的本机 IP 白名单选择 |
| 端口 | `18080` | HTTP 端口，1024～65535，不允许回指飞牛 5666/5667 |
| 基础路径 | `/` | 后端真实路径；若后端在 `/notes`，填 `/notes` |
| 回源 Host | 留空 | 默认是目标 IP:端口；仅对明确要求特定 Host 的后端填写 |
| 备注 | 每日笔记与资料 | 可选，多行纯文本，最多 500 字符；显示在服务卡片，不参与转发，请勿填写密码或令牌 |
| 允许外部访问 | 默认关闭 | 只对该服务开放独立业务入口；确认服务有自身鉴权，管理功能不受此选项影响 |

例如：

```text
http://NAS:5666/app/aio/s/notes/hello?q=1
  → http://127.0.0.1:18080/hello?q=1
```

基础路径填 `/notes` 时，回源为 `/notes/hello?q=1`。管理页面提供启停转发、删除、显式 HEAD 连通检查、导出和导入配置。

停用或删除只影响后续转发，不停止后端服务；已建立的连接不强制切断。默认服务列表为空。

卡片将访问入口与检查、编辑、启停、删除分区展示，完整链接收在“入口地址与说明”中。**飞牛私有入口固定使用网关策略的 `defaultOrigin`**，路径为 `/app/aio/s/服务标识/`，不随当前管理页面的域名或 IP 变化。将 `defaultOrigin` 配置为 FN 地址即可固定使用该 FN 入口；该值须属于 `allowedOrigins`。打开后需在目标入口登录飞牛，不会迁移当前域名的登录态。管理 API 仍使用当前页面的同源地址。0.2.5 起打包时还将已验证策略中的默认地址注入前端资源（只包含该地址，不包含完整策略）。若接口缺少有效默认入口，使用包内默认地址，仍显示私有入口，不回退到当前域名；若两者都无效才禁用私有入口，其他卡片功能不受影响。有效接口配置优先，修改生产策略后重启 AIO 生效；下次构建前同步本机策略，以更新包内备用值。外部入口单独使用部署策略的 `publicProxy.origin`，不经过飞牛登录。

## 可选的 DSH token 快捷复制（0.2.6）

管理员在部署策略中显式配置 `tokenLogs` 后，管理页增加“DSH 登录 token”区域，分别显示常规/救援模式、最近记录的原始时间，并支持复制最新 token。每次点击都重新读取对应日志，不把 token 放进访问链接、服务配置、导出文件、浏览器存储或 AIO 日志。浏览器阻止自动复制时提供短时手动复制弹窗，关闭、离页或 60 秒后清空；系统剪贴板及其历史由用户自行管理。

这是一项用户显式授权的只读日志集成，默认关闭；仅指定 NAS 所有者管理员可用，独立外部业务入口不提供此 API。不修改 DSH、PM2 或日志权限。日志时间不证明 token 仍有效。配置方法、尾部扫描范围和安全限制见[配置手册](<docs/configuration.md#dsh-token-日志只读集成>)。

## 可选的 Lucky 业务入口（0.2.0）

由管理员在网关策略中配置可选 `publicProxy`（缺省不监听 TCP）：

```json
"publicProxy": {
  "listenHost": "127.0.0.1",
  "port": 19090,
  "origin": "https://your-nas.example:1025"
}
```

这是一段策略字段示例，需加入现有 JSON，不能单独作为完整策略使用。内部端口仅为示例，需确认空闲。

Lucky 监听外部 HTTPS 1025，将所有路径原样转发到 `http://127.0.0.1:19090`，并保留外部 Host（含 1025）。AIO 只接受与固定外部 Origin 匹配的 Host，不依赖客户端的 Forwarded 或飞牛身份 Header。仅支持回环监听；Lucky 若运行于 Docker，需与宿主共享网络或另行设计受控接入，不能用容器自己的 localhost 指向宿主。

管理页逐服务开启外部访问后，地址为 `https://your-nas.example:1025/notes/`；去掉 `/notes` 后按服务基础路径回源。无尾斜杠自动 308，根路径、未知路径、未开放或停用服务返回 404，不列出服务。业务监听器没有管理页面或 API；原私有访问路径不变。

旧服务缺少 `publicAccess` 时按 false 处理；保存和导出包含此字段。旧备份导入会关闭其中未声明的外部访问。旧版程序不接受新字段，降级需先备份并移除。业务策略的外部 Origin 必须使用独立 HTTPS 端口或域名，不得与飞牛管理 Origin 相同；不能把业务入口端口作为后端以免循环。

完整配置、Lucky 注意事项、Vaultwarden 子路径和安全边界见[业务入口手册](<docs/public-proxy.md>)。

## Android 专用入口原型

独立的 **dsh** Android WebView 应用（原名 AIO 直达；0.3.2，Android 8.0+，DeepSeek 鲸鱼桌面图标）：无顶部菜单，34dp 视觉/44dp 触控的半透明可拖动悬浮球向下展开“切换访问方式 / 刷新当前页面 / 下载管理”。支持 FN ID 与独立 HTTPS 业务入口切换并记住选择；仅 FN ID 模式检查飞牛登录，失效时进入官方首页并在恢复后返回服务，直连模式不做飞牛认证处理。当前 Origin 内正常导航，外站交给浏览器，支持用户触发的新窗口链接；返回键先收起菜单、再网页后退，第一页才确认退出，兼容 Android 13+ 返回手势。保留 App 私有 Cookie，不保存 NAS 密码，不修改 NAS 网关，不保证永久免登录；首次登录、2FA 和必要认证交互仍由用户完成。

0.2.1 增加下载另存为：支持当前服务的 Cookie 鉴权 HTTPS 文件及网页 blob/data 导出，通过系统保存器选位置，单文件上限 256 MiB，不申请广泛存储权限或新增原生 JS 桥。特殊 POST/Bearer/跨站 CDN 下载仍需逐个适配，真实手机保存流程尚待用户验收。

0.3.0 新增原生下载管理：最近 100 条记录，显示时间/大小/实际阶段进度，支持取消、打开、分享和仅清除记录。已完成文件通过用户选定的 document URI 在手机查看器中打开，保留该文件的只读授权，不依赖 NAS 桌面；不记录来源 URL/Cookie，不扫描旧文件，升级前的下载无法追溯恢复。

0.3.2：下载列表中的 APK 显示“安装”，点击后调起系统安装确认；首次按需引导开启“允许此来源安装应用”，返回后继续。新增 `REQUEST_INSTALL_PACKAGES`，不静默安装、不在下载完成时自动安装。APK 另有系统文件浏览入口（定位和可用操作依赖设备支持），仍保留分享；普通文件打开行为不变，真实安装流程尚待手机验收。

悬浮球以青绿色表示 HTTPS 直连、靛蓝色表示 FN ID，空闲不透明度 86%；颜色代表所选入口，不代表 FN ID 实际网络链路。当前机器可直接执行 [本机打包命令](<docs/android.md#当前机器直接复用已有配置>)，私人入口与工具链路径保存在被忽略的 `.local/android-build.env`，无需重复探索。

源码位于 [Android 项目](<android/app/src/main/java/top/fnaio/direct/MainActivity.java>)，构建、隐私边界和真机验收步骤见 [Android 使用说明](<docs/android.md>)。APK 与构建产物全部位于被忽略的 `.local/`；安卓编译成功不代表手机登录链路已验收。

## 兼容边界

- 支持 HTTP、WebSocket、流式上传/响应、文件 Range 和常见重定向。
- 网页应支持子路径或相对资源地址。AIO 提供 `X-Forwarded-Prefix`，但不通过替换 HTML/JavaScript 强行兼容写死根路径的网站。
- 后端自己的登录仍保留，不将飞牛身份自动转换为业务管理员身份。
- Cookie 名称会加服务前缀，外部业务还使用独立前缀，路径限定在该入口的服务挂载点，防止 NAS Cookie、其他服务或私有/外部入口的 Cookie 相互转发。两个入口需分别业务登录。需要 JavaScript 读取固定 Cookie 名的应用可能需适配。
- 第三方 OAuth 的回调、绝对资源地址、Service Worker、重定向到挂载外等需要逐个核对；不保证任意网站零修改接入。
- 所有服务页面与飞牛管理入口共享浏览器 Origin。**只接入你信任的自建服务；路径和 Cookie 前缀不是对恶意网页的浏览器安全隔离。**
- 第一版仅支持本机明文 HTTP 回源，不提供任意 TCP/UDP、HTTPS 回源、容器管理或多用户权限编辑。指定所有者必须是飞牛管理员。

## 开发和构建

需要 Node.js 22+、Python 3、飞牛 `fnpack`；运行时与测试不需要 npm 第三方依赖。

```bash
npm run verify
mkdir -p .local
cp gateway.example.json .local/gateway.json
# 编辑本机入口、所有者 UID 与本机目标 IP，策略不包含密码或令牌。
npm run build -- /绝对路径/fn-aio/.local/gateway.json
```

[网关策略示例](<gateway.example.json>)只包含虚构入口。本机策略和构建产物保存在 `.local/`，不进入 Git。生成的 FPK 包含本机入口策略，因此也是本机部署文件，不应作为通用公开安装包传播。

通过飞牛应用中心手动安装生成的 FPK，或由管理员执行：

```bash
sudo appcenter-cli install-fpk /构建目录/aio.fpk --volume 1
sudo appcenter-cli start aio
```

安装前确认 `/var/apps/aio` 没有被其他应用占用。AIO 使用专用包用户 `aio`，通过[飞牛统一应用网关](https://developer.fnnas.com/docs/core-concepts/gateway-registration/)注册 `/app/aio`，管理入口使用权限 0600 的 Unix Socket；可选业务入口仅监听策略指定的回环地址。

策略字段：`ownerUid`、`allowedOrigins`、`defaultOrigin`、`targetHosts`，以及可选的 `localIpEntries`、`publicProxy`、`tokenLogs`。它决定入口和目标安全边界，不在普通服务配置页面中放宽。

FN ID 可能切换到动态 IPv6 直连。可在网关策略加入 `"localIpEntries": [{"protocol":"https","port":1024}]`，允许指定协议/端口下的当前 NAS 网卡 IPv4/IPv6，无需逐个登记变化的 IP。缺省关闭；每次请求重新读取本机地址，不解析客户端域名，不放开任意 Host。Host、Origin、管理写入与 WebSocket 使用一致规则；飞牛登录和所有者管理员限制保留，独立业务入口不变。0.2.2 兼容飞牛网关剥离 Host 端口：从受授权来源或唯一匹配入口恢复；无法消除歧义时拒绝。配置与限制见[动态 IP 入口说明](<docs/configuration.md#fn-id-与动态本机-ip-入口>)。

**忘记怎么配置时，请看[配置、白名单与排障手册](<docs/configuration.md>)**：包含生产路径、各字段含义、不同域名/端口的白名单规则、安全添加命令、备份、JSON/策略校验、仅重启 AIO、403/10330 排障及回滚步骤。修改生产策略后，还要同步 `.local/gateway.json`，避免下次构建覆盖。

## 配置和安全

- 服务配置保存在 AIO 私有 var 目录的 `services.json`；前一版本保存在 `services.json.previous`，文件权限 0600。
- JSON 结构为 `{schemaVersion:1, revision, services:[...]}`。写入经过校验、进程内队列和 revision 冲突保护，再原子替换；损坏配置拒绝启动，不覆盖原文件。
- `GET /app/aio/api/services`、`PUT /app/aio/api/services`、`POST /app/aio/api/services/{id}/check` 仅接受指定所有者且为管理员，通过同源请求标记校验；写入还需要匹配的 Origin 和 JSON。
- 私有管理 Socket 校验飞牛提供的用户身份（本机管理员不在该隔离边界内）；没有飞牛登录的公网请求不能靠伪造身份 Header 进入管理功能。独立业务监听器无管理路由，不信任飞牛身份 Header，仅开放逐服务授权的转发。
- 请求头上限 32 KiB，管理 JSON 上限 128 KiB，最多 50 个服务、每个入口最多 128 个连接；业务上传流式转发，文件大小由后端自行限制。连通检查 5 秒超时且不跟随重定向。目标必须是策略列出的当前 NAS 本机接口 IP，不接受任意 DNS 或外部公网目标。
- 日志不记录请求 URL、Cookie、Authorization、请求体或服务配置内容。
- AIO 不直接提供公网 HTTPS，不绕过飞牛管理登录；外部业务由 Lucky 等反代接入可选回环端口。默认不读取 DSH 私人数据；唯一例外是管理员显式配置的两类启动日志，用于用户授权的 token 复制，不读取 DSH 配置、会话数据库或安装目录。**启用后，受信任的同源业务网页也属于 token 接口的浏览器安全边界；只接入可信服务。**

## 启停和回滚

在飞牛应用中心停用/卸载 AIO 即可撤回入口；卸载前建议导出配置，避免应用数据被一并清除。也可执行 `sudo appcenter-cli stop aio`。只停止 AIO 的适配进程，原业务服务、DSH 和 Lucky 保持原样。

导入配置会替换服务清单，需确认并通过完整服务校验；出现 revision 冲突时先刷新配置，不盲目覆盖。恢复前一版本时应先停用 AIO，再由管理员保留当前文件并恢复备份；不要同时启动多个 AIO 进程写同一份数据。

代码入口：[网关与 API](<src/bridge.mjs>)、[转发与 Cookie](<src/proxy.mjs>)、[配置存储](<src/store.mjs>)、[管理页面](<src/web/index.html>)、[打包工具](<scripts/build-fpk.mjs>)。

实际执行记录与验收边界见[验证记录](<docs/verification.md>)。
