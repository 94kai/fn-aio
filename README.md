# FN AIO

一个独立的飞牛 App，用一个经过飞牛登录的入口，管理并转发 NAS 上已有的自建 HTTP 服务。它不是 DSH 插件，也不安装、启动或迁移业务服务。

```text
浏览器 → 飞牛登录与 /app/aio 网关 → AIO Unix Socket → 本机业务服务
```

**当前只做私有访问，不提供公开 TCP 端口，不实现绕过飞牛登录，不修改 Lucky。**

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

例如：

```text
http://NAS:5666/app/aio/s/notes/hello?q=1
  → http://127.0.0.1:18080/hello?q=1
```

基础路径填 `/notes` 时，回源为 `/notes/hello?q=1`。管理页面提供启停转发、删除、显式 HEAD 连通检查、导出和导入配置。

停用或删除只影响后续转发，不停止后端服务；已建立的连接不强制切断。默认服务列表为空。

## 兼容边界

- 支持 HTTP、WebSocket、流式上传/响应、文件 Range 和常见重定向。
- 网页应支持子路径或相对资源地址。AIO 提供 `X-Forwarded-Prefix`，但不通过替换 HTML/JavaScript 强行兼容写死根路径的网站。
- 后端自己的登录仍保留，不将飞牛身份自动转换为业务管理员身份。
- Cookie 名称会加服务前缀，路径限定在该服务挂载点，防止 NAS Cookie 或其他服务 Cookie 被转给目标。需要 JavaScript 读取固定 Cookie 名的应用可能需适配。
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

安装前确认 `/var/apps/aio` 没有被其他应用占用。AIO 使用专用包用户 `aio`，通过[飞牛统一应用网关](https://developer.fnnas.com/docs/core-concepts/gateway-registration/)注册 `/app/aio`，只监听权限 0600 的 Unix Socket。

策略字段：`ownerUid`、`allowedOrigins`、`defaultOrigin`、`targetHosts`。它决定入口和目标安全边界，不在普通服务配置页面中放宽。

**忘记怎么配置时，请看[配置、白名单与排障手册](<docs/configuration.md>)**：包含生产路径、各字段含义、不同域名/端口的白名单规则、安全添加命令、备份、JSON/策略校验、仅重启 AIO、403/10330 排障及回滚步骤。修改生产策略后，还要同步 `.local/gateway.json`，避免下次构建覆盖。

## 配置和安全

- 服务配置保存在 AIO 私有 var 目录的 `services.json`；前一版本保存在 `services.json.previous`，文件权限 0600。
- JSON 结构为 `{schemaVersion:1, revision, services:[...]}`。写入经过校验、进程内队列和 revision 冲突保护，再原子替换；损坏配置拒绝启动，不覆盖原文件。
- `GET /app/aio/api/services`、`PUT /app/aio/api/services`、`POST /app/aio/api/services/{id}/check` 仅接受指定所有者且为管理员，通过同源请求标记校验；写入还需要匹配的 Origin 和 JSON。
- 只有飞牛能连接私有 Socket；应用校验其提供的用户身份。没有飞牛登录的公网请求不能靠伪造身份 Header 进入。
- 请求头上限 32 KiB，管理 JSON 上限 128 KiB，最多 50 个服务和 128 个连接；业务上传流式转发，文件大小由后端自行限制。连通检查 5 秒超时且不跟随重定向。目标必须是策略列出的当前 NAS 本机接口 IP，不接受任意 DNS 或外部公网目标。
- 日志不记录请求 URL、Cookie、Authorization、请求体或服务配置内容。
- 公开端口和绕过登录不在本版本范围内。源代码不读取 DSH 配置、凭据、会话或安装目录。

## 启停和回滚

在飞牛应用中心停用/卸载 AIO 即可撤回入口；卸载前建议导出配置，避免应用数据被一并清除。也可执行 `sudo appcenter-cli stop aio`。只停止 AIO 的适配进程，原业务服务、DSH 和 Lucky 保持原样。

导入配置会替换服务清单，需确认并通过完整服务校验；出现 revision 冲突时先刷新配置，不盲目覆盖。恢复前一版本时应先停用 AIO，再由管理员保留当前文件并恢复备份；不要同时启动多个 AIO 进程写同一份数据。

代码入口：[网关与 API](<src/bridge.mjs>)、[转发与 Cookie](<src/proxy.mjs>)、[配置存储](<src/store.mjs>)、[管理页面](<src/web/index.html>)、[打包工具](<scripts/build-fpk.mjs>)。

实际执行记录与验收边界见[验证记录](<docs/verification.md>)。
