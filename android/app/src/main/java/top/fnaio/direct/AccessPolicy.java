package top.fnaio.direct;

import java.net.URI;
import java.util.Locale;

/** Only non-secret endpoint configuration lives outside WebView's cookie store. */
public final class AccessPolicy {
    public final String origin;
    public final String service;
    public AccessPolicy(String input, String service) {
        try {
            URI u = new URI(input.trim());
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null
                    || u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null
                    || !(u.getRawPath().isEmpty() || "/".equals(u.getRawPath()))
                    || u.getPort() == 0 || u.getPort() > 65535 || u.getPort() < -1) {
                throw new IllegalArgumentException();
            }
            this.origin = "https://" + u.getRawAuthority().toLowerCase(Locale.ROOT);
            if (!service.matches("[a-z][a-z0-9-]{0,31}")) throw new IllegalArgumentException();
            this.service = service;
        } catch (Exception e) {
            throw new IllegalArgumentException("请输入 HTTPS NAS 根地址（不含账号、密码、路径或查询参数）和有效服务标识");
        }
    }
    public String home() { return origin + "/"; }
    public String target() { return origin + "/app/aio/s/" + service + "/"; }
    public String health() { return origin + "/app/aio/api/health"; }
    public boolean sameOrigin(String value) {
        try {
            URI a = new URI(origin), b = new URI(value);
            return "https".equalsIgnoreCase(b.getScheme()) && b.getRawUserInfo() == null
                    && a.getHost().equalsIgnoreCase(b.getHost()) && port(a) == port(b);
        } catch (Exception e) { return false; }
    }
    public boolean servicePage(String value) {
        try { return sameOrigin(value) && new URI(value).getPath().startsWith("/app/aio/s/" + service + "/"); }
        catch (Exception e) { return false; }
    }
    public static boolean invalidGatewayBody(String text) {
        return text != null && "invalid token".equalsIgnoreCase(text.trim());
    }
    private static int port(URI u) { return u.getPort() == -1 ? 443 : u.getPort(); }
}
