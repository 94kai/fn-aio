package top.fnaio.direct;

/**
 * 工具箱桌面入口：复用 dsh 的 WebView 宿主、访问设置与登录 Cookie，
 * 只在入口地址追加 ?toolbox=1，固定进入工具箱首页，不改动工具箱或 DSH。
 */
public final class ToolboxActivity extends MainActivity {
    @Override protected boolean toolboxEntry() { return true; }
}
