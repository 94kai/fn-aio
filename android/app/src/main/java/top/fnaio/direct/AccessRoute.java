package top.fnaio.direct;

import java.net.URI;

/** The chosen route, not a redirect, decides whether NAS authentication is used. */
public final class AccessRoute {
    public enum Navigation { IN_APP, EXTERNAL, BLOCKED }
    public enum BackAction { CLOSE_MENU, GO_BACK, CONFIRM_EXIT }
    public final AccessPolicy nas;
    public final boolean direct;
    private final String directUrl;
    private final AccessPolicy directAuthority;

    public AccessRoute(AccessPolicy nas, String directUrl, boolean direct) {
        this.nas = nas;
        this.direct = direct;
        this.directUrl = directUrl;
        if (directUrl == null || directUrl.isEmpty()) {
            if (direct) throw new IllegalArgumentException("尚未配置 HTTPS 直连入口");
            directAuthority = null;
            return;
        }
        try {
            URI uri = new URI(directUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getRawPath().contains("\\") || !uri.normalize().equals(uri)) {
                throw new IllegalArgumentException();
            }
            directAuthority = new AccessPolicy("https://" + uri.getRawAuthority(), "dsh");
        } catch (Exception e) {
            throw new IllegalArgumentException("直连入口必须是无凭据、无查询参数的 HTTPS 地址");
        }
    }
    public boolean requiresNasLogin() { return !direct; }
    public String entry() { return direct ? directUrl : nas.target(); }
    public boolean allows(String url) { return direct ? directAuthority.sameOrigin(url) : nas.sameOrigin(url); }
    public Navigation navigation(String url) {
        if (allows(url)) return Navigation.IN_APP;
        return safeExternal(url) ? Navigation.EXTERNAL : Navigation.BLOCKED;
    }
    public static boolean safeExternal(String url) {
        try {
            URI uri = new URI(url);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null
                    && uri.getPort() != 0 && uri.getPort() <= 65535 && uri.getPort() >= -1;
        } catch (Exception e) { return false; }
    }
    public static BackAction backAction(boolean menuExpanded, boolean hasHistory) {
        if (menuExpanded) return BackAction.CLOSE_MENU;
        return hasHistory ? BackAction.GO_BACK : BackAction.CONFIRM_EXIT;
    }
}
