# FN AIO

独立飞牛 App，不是 DSH 插件；不得依赖或改动旁边的 DSH、工具箱或 Lucky。当前只做经过飞牛登录的私有 Unix Socket 网关，不增加公开 TCP 入口。

- 界面统一中文，默认仅指定 NAS 所有者且为管理员可访问。
- 目标仅允许运行策略中明确列出的本机 IP；配置不可包含账号密码、令牌或 URL 凭据。
- 私人配置、构建目录和安装包放 `.local/` 并排除 Git；日志不记录请求 URL、Cookie、Authorization 或正文。
- 不直接修改飞牛 Nginx、数据库或 DSH 宿主；通过正式 FPK 与应用中心安装。部署只重启 aio。
- 使用 `npm run verify` 验证；`npm run build -- /绝对路径/本机网关策略.json` 构建独立 FPK。
- 不主动运行浏览器、Playwright 或截图；构建、接口测试、安装成功与真实 UI 验收要分别说明。
- 修改行为、安全边界或配置结构时同步更新 README 与文档。
