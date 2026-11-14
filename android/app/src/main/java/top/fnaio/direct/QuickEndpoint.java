package top.fnaio.direct;

import java.net.URI;

/** Validate endpoint before any session cookie or request is sent. */
final class QuickEndpoint {
    private QuickEndpoint() { }

    static String normalize(String input) {
        try {
            URI u = new URI(input.trim());
            String path = u.getRawPath();
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null
                    || u.getQuery() != null || u.getFragment() != null || u.getPort() > 65535 || u.getPort() == 0
                    || path == null || path.contains("%") || path.contains("\\") || path.contains("//")
                    || !u.normalize().equals(u)) throw new IllegalArgumentException();
            if (!path.endsWith("/")) path += "/";
            return new URI("https", null, u.getHost(), u.getPort(), path, null, null).toASCIIString();
        } catch (Exception e) { throw new IllegalArgumentException("入口地址无效", e); }
    }
}
