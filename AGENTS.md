# FN AIO

独立飞牛 App，不是 DSH 插件；不得依赖或改动旁边的 DSH、工具箱或 Lucky 的实现与配置。管理与私有访问走经过飞牛登录的 Unix Socket 网关；用户已授权新增可选的回环 HTTP 业务入口，由 Lucky 等外部 HTTPS 反代接入。业务入口按 /服务标识/ 转发，只开放显式授权的服务，不能暴露管理路由，不直接监听公网或局域网地址。

- 界面统一中文，默认仅指定 NAS 所有者且为管理员可访问。
- 目标仅允许运行策略中明确列出的本机 IP；配置不可包含账号密码、令牌或 URL 凭据。
- 私人配置、构建目录和安装包放 `.local/` 并排除 Git；日志不记录请求 URL、Cookie、Authorization 或正文。
- 不直接修改飞牛 Nginx、数据库或 DSH 宿主；通过正式 FPK 与应用中心安装。部署只重启 aio。
- 开始任务先读本文件，并按任务查阅 README 与对应 `docs/` 操作手册；README 是项目介绍和功能入口，不是可忽略的背景材料。
- 使用 `npm run verify` 验证；`npm run build -- /绝对路径/本机网关策略.json` 构建独立 FPK。
- Android APK 构建请求必须先查阅 `docs/android.md` 的“当前机器：直接复用已有配置”，在仓库根目录加载 `.local/android-build.env`，按文档提供的私人入口参数运行 `:app:assembleDebug :app:lintDebug`，并以唯一文件名复制 APK 到 `.local/`。不得先假定缺少 JDK/SDK、跳过私人配置，或只运行裸 `./gradlew assembleDebug`；遇到真实构建错误再报告具体阻塞。
- 不主动运行浏览器、Playwright 或截图；构建、接口测试、安装成功与真实 UI 验收要分别说明。
- 修改行为、安全边界或配置结构时同步更新 README 与文档。
