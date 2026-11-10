package top.fnaio.direct;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DownloadPolicy {
    public static final long MAX_BYTES = 256L * 1024 * 1024;
    public static final int CHUNK_BYTES = 64 * 1024;
    private DownloadPolicy() {}
    /** Downloads are scoped to the chosen service path, not the NAS login/management pages. */
    public static boolean serviceUrl(AccessRoute route, String url) {
        try {
            if (route == null || !route.allows(url) || url.length() > 16384) return false;
            URI uri = new URI(url);
            if (!uri.normalize().equals(uri)) return false;
            String raw = uri.getRawPath().toLowerCase(Locale.ROOT);
            if (raw.contains("%2f") || raw.contains("%5c") || raw.contains("%2e") || raw.contains("\\")) return false;
            String base = new URI(route.entry()).getPath().replaceAll("/+$", "");
            return uri.getPath().equals(base) || uri.getPath().startsWith(base + "/");
        } catch (Exception e) { return false; }
    }
    public static boolean localUrl(AccessRoute route, String page, String url) {
        if (!serviceUrl(route, page) || url == null) return false;
        if (url.startsWith("blob:")) return route.allows(url.substring(5));
        return url.startsWith("data:") && url.length() <= 8 * 1024 * 1024;
    }
    public static String mime(String value) {
        String type = value == null ? "" : value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return type.matches("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+") ? type : "application/octet-stream";
    }
    public static String filename(String url, String disposition) {
        String name = "";
        if (disposition != null) {
            Matcher encoded = Pattern.compile("(?i)(?:^|;)\\s*filename\\*=UTF-8''([^;]+)").matcher(disposition);
            if (encoded.find()) try { name = URLDecoder.decode(encoded.group(1).trim().replace("+", "%2B"), StandardCharsets.UTF_8.name()); } catch (Exception ignored) {}
            if (name.isEmpty()) {
                Matcher plain = Pattern.compile("(?i)(?:^|;)\\s*filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]*))").matcher(disposition);
                if (plain.find()) name = plain.group(1) != null ? plain.group(1) : plain.group(2).trim();
            }
        }
        if (name.isEmpty()) try {
            String path = new URI(url).getPath();
            name = path.substring(path.lastIndexOf('/') + 1);
        } catch (Exception ignored) {}
        return safeName(name);
    }
    public static String safeName(String value) {
        String name = value == null ? "" : value.replaceAll("[\\p{Cntrl}\\p{Cf}\\\\/:*?\"<>|]", "_").trim();
        name = name.replaceAll("^\\.+|[. ]+$", "");
        if (name.isEmpty()) name = "下载文件.bin";
        if (name.length() > 120) {
            int dot = name.lastIndexOf('.');
            String extension = dot > 0 && name.length() - dot <= 16 ? name.substring(dot) : "";
            name = name.substring(0, 120 - extension.length()) + extension;
        }
        return name;
    }
}
