# 验证与部署记录

## 2026-11-10：AIO 0.2.1 固定私有服务链接（已取消，未部署）

**后续决定：用户确认保留按当前访问域名生成私有链接的原逻辑。本次源码、版本、测试及使用说明改动已撤回；下列为取消前的尝试记录。生成的 0.2.1 安装包已作废，不应安装。生产始终未升级，备份保留。**

- 管理 API 新增只读 `policy.privateOrigin`，取已校验的 `defaultOrigin`；管理页私有服务链接固定为该 Origin 下的 `/app/aio/s/服务标识/`，不再跟随当前管理页地址。API 请求仍留在当前管理页同源；外部业务短链接、鉴权、回源和 Cookie 策略不变。
- 本机默认 Origin 已为用户指定的 FN ID HTTPS 地址，无需改动生产策略。使用生产策略副本构建，不修改 1025 业务入口，不读取或修改旁边 DSH、工具箱或 Lucky。
- `npm run verify`：共 50 项，49 通过、0 失败、1 跳过（本次环境未设置 JDK，Android Java 策略测试未运行）。新增跨管理域名 API 默认入口测试与 VM 链接函数测试；`git diff --check` 通过。未运行浏览器、Playwright 或截图。
- 生产策略和服务列表在应用目录外完成逐字节 SHA-256 校验备份：[私有备份](<../.local/pre-link-0.2.1-x6_ghbs7/>)，没有改写服务列表。构建使用[生产策略副本](<../.local/gateway-link-0.2.1.json>)，正式 [0.2.1 FPK](<../.local/build-EH2Z8A/aio.fpk>) 构建成功。
- 使用管理员权限调用正式 `appcenter-cli install-fpk`，命令退出 0 但只返回应用已安装；再次读取生产 manifest 仍为 **0.2.0**，不能视为升级成功。未执行 install-local、卸载、手工替换生产代码或重启其他服务。需要通过飞牛应用中心“手动安装”此 FPK 完成升级，再做生产接口验收；本轮没有新版生产接口或真实 UI 验收。

## 2026-11-10：Android「dsh」0.3.1（名称与鲸鱼图标）

- 按用户要求将桌面/系统应用名称从 AIO 直达改为小写 `dsh`，图标改为白底蓝色 DeepSeek 鲸鱼，图形来源为 [Simple Icons DeepSeek SVG](https://cdn.simpleicons.org/deepseek)。提供 legacy、自适应/圆形和 Android 13+ 单色主题资源，不修改悬浮球或业务服务功能；并非 DeepSeek 官方客户端。
- 包名/签名/私有存储保持不变，versionCode=5、versionName=0.3.1，可覆盖安装保留登录和下载记录，不新增权限或修改 NAS、DSH、AIO 生产服务或 Lucky。
- `JAVA_HOME=本机JDK npm run verify`：**48 项测试通过**，新增名称/图标/主题资源与包名兼容静态断言。`:app:assembleDebug :app:lintDebug` 成功；Lint 0 错误、3 项提示（冗余 v26 目录、品牌静态矢量路径长度、v26 资源未含 monochrome；实际 v33 专用资源已包含主题图标），未声称零警告。
- `apksigner verify` v2 签名通过，与 0.3.0 证书 SHA-256 一致。`aapt dump badging` 确认 application-label=dsh、版本号及图标资源、仅联网权限。交付 [dsh 0.3.1 测试 APK](<../.local/dsh-0.3.1-debug.apk>)，副本与构建输出逐字节一致且被 Git 忽略，旧交付包保留。`git diff --check` 通过。
- 未安装手机或启动浏览器/模拟器/截图，实际桌面图标裁切、主题显示和缓存更新需手机确认。部分桌面缓存旧图标，可重新添加快捷方式或重启桌面，不应为此卸载清数据。说明见 [Android 使用说明](<android.md>)。

## 2026-11-10：Android「AIO 直达」0.3.0（半透明悬浮球与下载管理）

- 将旧 48dp 实心球改为 34dp 渐变半透明视觉、44dp 触控区，带细描边/柔阴影、四点/关闭图标；空闲 alpha=0.72，交互时提高清晰度，保留拖动/相对位置和 FN ID/直连配色。菜单淡入淡出，新增第三项“下载管理”，不增加系统悬浮窗权限。
- 原生下载管理显示最近 100 条文件名、开始时间、大小、阶段进度与状态，每 500ms 在前台可见时刷新，关闭面板或切后台停止 UI 刷新。未知总大小使用不定进度；下载与最终保存分阶段计量，只有成功写入并关闭输出流才记完成。
- 下载历史本机私有持久化，普通进度最多每秒提交一次；阶段切换/结束提交。只存文件元数据和用户选定 document URI，不存下载源 URL、Cookie、Authorization 或续传凭据。重启时未完成记录标中断，不自动恢复不存在的 Blob，不扫描或回溯旧版下载。
- 支持已完成文件的打开/分享：按用户点击只验证该 URI，保留明确选择文件的持久只读授权，外部 Android chooser 仅附临时只读 URI 授权和 ClipData，不使用 NAS 桌面。文件删除/移动/权限失效或无查看器有提示，不申请未知来源安装或广泛存储权限。
- 提供取消任务、移除单条/清空已结束记录，后两者需确认且不删除实际文件；移除/淘汰时释放不再使用的持久只读授权。失败/取消/中断状态不会被迟到的进度或成功回调覆盖。
- `JAVA_HOME=本机JDK npm run verify`：**47 项测试通过**，其中 Java 模型/策略共 **151 项断言**；新增覆盖下载状态/大小格式/未知进度/保存阶段/终态吸收/中断恢复/URI 限制/快照隔离，并静态检查悬浮球尺寸透明度、三项菜单、管理页字段和 URI 只读分享边界。
- `:app:assembleDebug :app:lintDebug` 成功，最终 Lint **No issues found**。APK Signature Scheme v2 验签通过；与 0.2.1 证书 SHA-256 相同，`aapt dump badging` 确认 versionCode=4、versionName=0.3.0、minSdk=26、仅联网权限。交付[0.3.0 测试 APK](<../.local/AIO-Direct-0.3.0-debug.apk>)，副本与构建输出逐字节一致，包名与签名沿用，可覆盖安装保留登录。构建/缓存/APK 仍位于被忽略的 `.local/`，旧版交付包保留；`git diff --check` 通过。
- **没有安装手机、启动模拟器/浏览器/Playwright 或截图，没有读取真实下载文件/用户存储，没有修改 NAS、DSH、AIO 生产服务或 Lucky。** UI 视觉、触摸、实际进度/保存器、历史重启持久化、Android 查看器/分享和权限恢复仍需用户真机验收；测试与构建不代替这些验收。详细边界见 [Android 使用说明](<android.md>)。

## 2026-11-10：Android「AIO 直达」0.2.1（下载与网页导出）

- 确认 0.2.0 没有 DownloadListener，且 blob/data 导航被普通链接策略阻止；仅修复安卓外壳，不修改或重启 NAS、AIO、DSH、Lucky，也未读取 DSH 源码/配置。
- 新增系统 ACTION_CREATE_DOCUMENT 另存为：普通 HTTPS GET 文件使用当前 URL 的 WebView Cookie，重定向关闭自动跟随并逐跳验证同服务路径；不搬运跨站凭据，不重放 POST 或提取 Bearer Token。401/403 和飞牛纯文本 invalid token 明确失败。
- 新增仅在服务页面安装的下载捕获脚本，保存 Blob 引用并处理立即 revoke 的导出；data URL 和 Blob 文件通过原生主动 evaluateJavascript 每次拉取 64 KiB，无 addJavascriptInterface 或通用消息桥。验证文档/路由代次，刷新或离开页面取消旧 Blob，重新安装不叠加点击钩子。
- 用户选保存位置后才启动传输，完整内容先写 App 私有暂存，最后复制到选定 URI；正常结束/失败/关闭清理自身暂存，不删除用户目标文件。单文件 256 MiB、内联 data URL 编码后 8 MiB、一次一个任务，不申请广泛存储权限，不新增菜单按钮，不提供后台常驻/断点续传。
- `JAVA_HOME=本机JDK npm run verify`：**44 项测试通过**，包含 Java 策略等 **107 项断言**；新增 Node VM 功能测试覆盖 Blob 立即 revoke、150000 字节多分块重组、中文 data URL、超限/跨源/非法 scheme/并发拒绝、空文件、偏移/背压、超时后不复活与钩子重装，另有原生安全源码检查。VM 使用模拟 DOM/FileReader，不是真实浏览器或 WebView。
- `:app:assembleDebug :app:lintDebug` 成功，最终 Lint **No issues found**；APK v2 验签通过。与 0.2.0 的签名证书 SHA-256 一致，包名不变、versionCode=3、versionName=0.2.1、minSdk=26、仍仅联网权限，可覆盖安装保留登录。
- 交付[0.2.1 测试 APK](<../.local/AIO-Direct-0.2.1-debug.apk>)，版本副本与构建输出逐字节一致，确认被 Git 忽略；旧 0.2.0 交付包保留。`git diff --check` 通过。
- **没有执行手机安装、模拟器、浏览器、Playwright 或截图，没有对用户真实文件运行下载。** 原生网络/系统保存器/真实 DSH 下载按钮/最终文件可打开性尚待手机验收，不将编译、VM 或策略测试当作真机通过。特殊 POST/Bearer/跨站 CDN/浏览器文件系统 API 下载不保证兼容；说明与验收清单见 [Android 使用说明](<android.md>)。

## 2026-11-10：Android「AIO 直达」0.2.0（悬浮球与双入口）

- 仅修改独立安卓工程、测试和文档，没有更改/重启 NAS、AIO 生产服务、DSH 或 Lucky，没有执行手机安装。
- 移除顶部栏，使用 App 内可拖动悬浮球，点击向下展开“切换访问方式 / 刷新当前页面”两个按钮；保存相对位置并约束边缘、键盘与旋转布局，不申请系统悬浮窗权限。
- 新增 FN ID / HTTPS 直连路由策略，记住选择。FN ID 保留原认证恢复；直连只打开指定业务 URL，不执行飞牛探测和跳转。模式切换隔离代次，忽略旧请求结果，不跨域复制 Cookie，清除旧入口的返回历史。私人 URL 仅作为构建参数进入 `.local/` 的安装包/生成代码。
- 修复用户触发的新窗口链接路由：同源进入当前 WebView，外链交系统浏览器；自动跨站跳转需确认，不支持的 scheme/含凭据链接阻止，隐藏新窗口不执行网络请求，新窗口 POST 明确提示不支持。
- 返回按“收起菜单 → WebView 后退 → 首页确认退出”处理；API 33+ 注册 OnBackInvokedCallback，旧版仍使用 onBackPressed。旋转保留 Activity，避免仅旋转就重载网页。
- `JAVA_HOME=本机JDK npm run verify`：**38 项测试通过**，含 Java 策略/双入口/导航/返回/悬浮球几何共 **70 项断言**。源码静态测试不能证明触摸或 Android 手势已实测。
- `:app:assembleDebug :app:lintDebug` 成功，最终 Lint **No issues found**；APK Signature Scheme v2 验签通过，`aapt dump badging` 确认 versionCode=2、versionName=0.2.0、minSdk=26、仅联网权限。交付版本命名的[0.2.0 测试 APK](<../.local/AIO-Direct-0.2.0-debug.apk>)，副本与构建输出逐字节一致并被 Git 忽略；applicationId 与调试签名沿用，覆盖安装以保留本地登录数据。构建与缓存仍只写 `.local/`，不是正式发布签名。`git diff --check` 通过。
- **未启动浏览器、模拟器、Playwright 或截图，未执行 adb 安装或真机 UI 验收。** 悬浮球触摸/边缘布局、双入口登录/通信、外链/新窗口、手势与传统返回、上传等请按 [Android 验收清单](<android.md>)实机确认。

## 2026-11-10：Android「AIO 直达」0.1.0 原型（已构建，未真机验收）

- 新增独立 Android WebView 工程与 Gradle Wrapper；不修改飞牛、AIO 生产服务、DSH、工具箱或 Lucky，不读取这些服务的配置或凭据。构建使用本机现有 JDK 17、Android SDK 35、Gradle 8.11.1；离线构建最初缺少 Kotlin 构建依赖，联网补齐后构建成功，没有安装 SDK 或执行设备写操作。
- 支持 HTTPS NAS Origin 与服务标识配置、WebView Cookie/DOM Storage、识别 HTTP 200 的 `invalid token`、通过 AIO 只读健康接口判定登录、失效后进入官方首页并在认证恢复后自动返回。等待三分钟暂停轮询；后台不轮询；首次登录/2FA/会话彻底失效仍由用户操作。支持系统文件上传选择器，不保存 NAS 密码。
- 安全：同源顶层导航、严格 TLS、禁止明文/混合内容/文件 URL/内容 URL 直接访问/第三方 Cookie/调试/原生 JS 桥；禁止备份并显式排除 Android 12+ 云备份与设备迁移。设置只保存 Origin 与服务标识；日志不输出请求 URL、Cookie、Authorization 或正文。
- `JAVA_HOME=本机JDK npm run verify`：**37 项测试通过**，其中 Java 访问策略含 **24 项断言**；另含 Android 安全声明静态测试。未设置 JDK 的环境会明确跳过 Java 策略测试。
- `:app:assembleDebug :app:lintDebug` 成功；最终 Android Lint **No issues found**。APK Signature Scheme v2 验签通过。初次验签时 apksigner 未能从 PATH 找到 Java，显式加入已有 JDK 后验签成功。
- 预置本用户 FN ID 的[调试 APK](<../.local/android-build/app/outputs/apk/debug/app-debug.apk>)及所有构建/缓存产物放 `.local/`，确认被 Git 忽略。仅为测试侧载版本，非正式发布签名。`git diff --check` 通过。
- **没有启动手机、模拟器、浏览器、Playwright 或截图，没有执行 adb 安装。** 自动登录恢复、杀进程后 Cookie 持久化、2FA、DSH 流式通信、文件上传与真实手机布局尚待用户安装验收，不能将编译/静态测试通过当作这些功能已实机通过。说明与验收清单见 [Android 使用说明](<android.md>)。

## 2026-11-10：正式升级尝试（未完成）

- 用户授权安装与重启，通过 fnos-cli 0.5.1 的 OAuth 授权连接确认 AIO 为 0.1.1、running；没有使用聊天中的密码。当前本地无免密 sudo，appcenter-cli 也不能以普通用户访问 rpcbroker，因此没有执行其写操作。
- 经官方文件接口将当前网关策略与服务列表复制到应用托管目录外的私有用户卷备份，另复制到[项目私有备份](<../.local/pre-upgrade-0.2.0-ZEFY4F/>)。校验两份文件 SHA-256 一致、JSON 与新版策略/服务校验通过，收紧目录 0700、文件 0600。备份为 7 项服务、revision 5；新版策略与现有策略比较仅增加可选业务入口。
- 正式 FPK 的 install-fpk --dry-run 通过，后端报告 installed=true、canUpgrade=true、目标版本 0.2.0。执行 install-fpk 时实际调用安装任务端点，被后端以 10236 拒绝；确认旧版仍在运行。随后 app update --package-type local --dry-run 在下载任务阶段被 10030 拒绝，未启动更新任务。
- 没有尝试卸载、install-local、手工替换生产代码或更改数据库/飞牛 Nginx；没有停止或重启原应用，没有改动 Lucky。需用户通过飞牛网页应用中心“手动安装”选择更高版本 FPK 完成升级，再继续接口及重启验证；不能将预检成功当作升级成功。

## 2026-11-10：AIO 0.2.0 可选外部业务入口（开发及构建，未部署）

- 经用户明确授权，将原“仅私有 Socket”范围扩展为可选的回环 HTTP 业务监听；管理仍使用飞牛身份鉴权。未改动 Lucky、飞牛端口、Nginx、业务容器或生产配置，未安装或重启任何应用。
- 新增可选 `publicProxy` 部署策略和缺省 false 的 `publicAccess` 服务字段；业务入口按 `/服务标识/` 转发，独立于管理路由。表单支持逐服务授权，保存/导入/重新启用对外服务需要确认，卡片展示外部短链接。
- Cookie 使用独立外部入口前缀，隔离飞牛及私有入口凭据；保留业务 Authorization，恢复可信的外部 HTTPS Host/协议，支持短路径 Location、WebSocket、Range 与流式转发。
- `npm run verify`：JavaScript / Python 语法检查及 **35 项测试通过**。新增覆盖默认不监听、旧配置不公开、回环策略与端口拒绝、严格布尔/持久化/导入默认关闭、逐服务授权/撤销/停用/删除、管理 API 隔离、伪造身份及转发头、Host/Origin/跨站拒绝、歧义路径、Cookie 隔离、重定向、Range、WebSocket、取消回源、已带挂载前缀不重复改写、端口占用启动失败和静态表单安全规则。`git diff --check` 通过。
- 只读核对当前生产网关策略后，另存一份加入回环 19090 与外部 HTTPS 1025 的[本机构建策略](<../.local/gateway-public-0.2.0.json>)，没有覆盖生产策略或旧构建策略。构建前检查 19090 无监听；该检查不保证未来安装时端口仍可用。
- `npm run build -- /home/xuekai/project/fn-aio/.local/gateway-public-0.2.0.json` 构建[0.2.0 FPK](<../.local/build-iqXzhH/aio.fpk>) 成功，私人配置与构建产物均被 Git 忽略。安装包包含本机策略，仅供本机使用；生产仍是原安装版本。
- 使用阶段目录中的打包程序及策略、独立临时数据和 Socket 做启动冒烟：管理健康接口版本 0.2.0、业务根路径/未授权服务/管理路径均返回 404；随后关闭两个监听并清理临时测试目录。没有读取或写入真实服务列表。
- 未经过应用中心安装验收，未运行真实 Lucky 链路、浏览器、Playwright 或 Bitwarden。特别是 Vaultwarden DOMAIN、子路径及各平台客户端仍需用户完成实际接入验证，不宣称已解决全部客户端兼容问题。
- 配置与使用说明见[业务入口手册](<public-proxy.md>)；Lucky 若不与宿主共享网络，不能用容器自身 localhost 访问本功能的宿主回环监听。

## 服务备注更新

- 新增可选 `note` 文本字段，最多 500 个 UTF-16 代码单元；缺省为空，允许多行，拒绝非文本、超长及不支持的控制字符。旧服务配置可直接读取，正常保存时写入备注字段，schemaVersion 保持 1。
- 表单支持编辑备注；卡片使用 textContent 展示并保留换行/长文本换行；导入导出包含备注，不向后端转发备注。前端提示不要填密码或令牌。
- `npm run verify`：语法检查及 22 项测试通过，覆盖旧配置、多行保存/重新读取、导入、清空、长度/类型/控制字符边界、不参与转发及前端静态安全规则。`git diff --check` 通过。
- 使用当前生产入口策略构建 `.local/build-bmi2XJ/aio.fpk` 成功，没有覆盖本机生产配置或服务数据。
- 初始备注包仍为 0.1.0；用户执行 install-fpk 后仅返回“已安装”，文件哈希确认仍为旧代码。随后将包、manifest 与健康接口版本同步为 0.1.1，22 项测试通过，按当前生产策略构建 `.local/build-bEC9K9/aio.fpk`。
- 本机 install-fpk 对已安装应用仅返回“已安装”，未替换文件。随后用户授权代为处理，尝试 install-local；该命令内部执行停用/卸载，再安装因临时路径 install_init permission denied（10237）失败，且进程退出码为 0。**此命令不可作为无损升级使用，也不能只以退出码判断成功。**
- 事故导致 AIO 私有数据目录被删除；此前将备份放在应用 var 内是错误的备份边界，备份亦被删除。已向用户说明。随后通过正式 FPK 重新安装 0.1.1 并启动，通过管理 API 恢复此前读取过的 7 项服务快照（两个 DSH 地址按用户要求设为回环）；这不是原磁盘最新备份，无法保证保留快照之后的全部编辑，需用户复核。
- 新版 manifest 为 0.1.1，store/bridge 及三个前端资源的生产哈希均与源码一致；生产私有接口/鉴权/Socket 诊断全部通过，配置为 7 项、revision 1。没有向真实服务写入测试备注，没有改动 DSH、Lucky 或其他业务服务。
- 已在项目 `.local/production-backup-PgBiVp/` 创建应用目录之外的策略/服务备份（目录 0700）；恢复快照也在项目 `.local/`。均排除 Git，不记录私人内容。
- 用户随后反馈“没问题了”，确认本次更新可用；Agent 未运行浏览器验证，不扩展宣称全部业务或手机布局已验收。降级限制见[配置手册](<configuration.md>)。

## 2026-11-09：部署后维护

- 用户已自行配置一个服务，并反馈多个 NAS 入口能打开 DSH；这不代表全部手机布局或业务功能已验收。
- 新增 HTTPS 域名端口入口时，生产策略曾因数组项之间漏逗号导致启动失败（应用中心 10330）。修复 JSON、补入口后，策略语义校验和应用中心启动成功。
- 恢复后生产私有接口诊断全部通过：页面、静态脚本、健康状态、拒绝边界和 Socket 权限正常；只读配置为 1 个服务、revision 1。本次没有改写服务列表，没有重启 DSH 或 Lucky。
- 用户反馈 HTTPS 证书有效但普通窗口提示不安全，无痕窗口无此问题；未进一步定位浏览器旧状态原因，不将其认定为 AIO 或证书故障。
- 新增[配置手册](<configuration.md>)，记录入口白名单、生产/构建策略同步、备份、校验、重启及回滚。不包含实际私人入口、UID 或凭据。提交前再次运行 `npm run verify`，语法检查及 19 项测试全部通过；确认 `.local/` 与 FPK 被 Git 忽略。
- 未运行浏览器、Playwright 或截图；生产私有接口诊断不能替代真实浏览器验收。

## 2026-11-09：AIO 0.1.0

### 范围

独立项目，运行和打包均不依赖 DSH、随身工具箱或其安装目录。只有飞牛私有 Unix Socket 入口，不增加公开 TCP 端口，不修改 Lucky 或业务服务；初始服务列表为空。

### 自动验证

- `npm run verify`：JavaScript / Python 语法检查及 **19 项测试全部通过**。
- 使用临时本机 HTTP 服务与临时 Unix Socket，覆盖：所有者/管理员鉴权，FN localhost 改写，Host/Origin/跨站拒绝，CSP 与静态资源，管理请求标记与 JSON/大小限制，配置持久化与 revision 并发冲突，目标/端口/路径输入拒绝，前缀及基础路径转发，Cookie 隔离，重定向，Range，HEAD 连通检查，WebSocket 双向隧道，断线取消回源，损坏配置拒绝覆盖。
- 前端静态回归核对所有脚本引用的元素 ID、中文与自托管资源、无 HTML 注入/浏览器凭据持久化、危险操作确认、44px 触控规则、长地址换行和暗色样式。静态规则不能证明真实浏览器布局通过。
- 本机运行策略还通过当前网络接口 IP 白名单校验，不接受任意公网目标或 DNS 目标。
- `npm run build -- /本机私有策略路径` 使用 fnpack 构建独立 FPK 成功，目录为 `.local/build-8klu2g`；阶段目录只包含显式列出的源码、静态资源、生命周期和本机策略，不扫描其他项目或私人文件。

### 部署验收

- 已确认 `/var/apps/aio` 未占用后，通过正式 `appcenter-cli install-fpk` 安装成功；随后确认应用已启动。
- 生产进程以独立包用户 `aio` 运行，只监听 `/var/apps/aio/target/app.sock`。管理员实际检查 Socket 权限为 0600，属于非 root 用户；检查该进程无 TCP 监听。
- 使用[安装后诊断脚本](<../scripts/verify-installed.mjs>)，在生产 Unix Socket 上只读验证管理页面、localhost 入口、前端脚本、健康状态返回 200；无 NAS 身份、非所有者、非管理员、未知 Host、恶意 Origin 和缺少管理标记均为 403。
- 生产只读配置检查为 0 个服务、revision 0，没有创建或改写真实服务配置，没有自动添加 DSH 或其他业务。
- 匿名访问实际 FN 域名及 LAN 的 `/app/aio/` 均被飞牛外层拦截，返回 `invalid token`（飞牛状态码为 200），没有收到 AIO 管理页面。原 DSH Lucky HTTPS 入口仍可达并返回自身 401 登录页；本次没有修改或重启 DSH、Lucky 或任何业务服务。
- 以上生产诊断使用本机管理员合成网关身份，不包含 NAS/DSH 登录凭据；它确认私有服务运行及拒绝边界，但不代替真实浏览器登录、表单交互或实际业务接入验收。
- 界面已在独立飞牛 App 注册；等待用户通过真实 NAS 登录后打开 AIO 确认。

### 限制

- 没有运行浏览器、Playwright、截图或读取浏览器登录凭据。手机 320/375/390/430px、暗色、软键盘和真实表单交互待用户验证。
- 不自动接管 DSH 或现有服务，没有用用户业务数据做写入测试。
- 不保证任意现成网页零改动兼容子路径；固定根路径资源、JavaScript Cookie 名、OAuth、Service Worker 需要业务侧适配。
- 管理页与被转发服务同 Origin，必须信任被接入页面；该设计不对恶意服务提供浏览器隔离。
