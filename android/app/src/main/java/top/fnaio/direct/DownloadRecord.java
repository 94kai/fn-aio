package top.fnaio.direct;

import java.net.URI;
import java.util.Locale;

/** File metadata only: no source URL, request headers, cookies or resumable credentials. */
public final class DownloadRecord {
    public enum State { WAITING, DOWNLOADING, SAVING, COMPLETED, FAILED, CANCELLED, INTERRUPTED }
    public final String id;
    public String name, mime, destination = "", message = "";
    public final long createdAt;
    public long updatedAt, total, downloaded, copied;
    public State state = State.WAITING;

    public DownloadRecord(String id, String name, String mime, long total, long now) {
        this.id = id; this.name = DownloadPolicy.safeName(name); this.mime = DownloadPolicy.mime(mime);
        this.total = boundedTotal(total); this.createdAt = Math.max(0, now); this.updatedAt = this.createdAt;
    }
    public boolean active() { return state == State.WAITING || state == State.DOWNLOADING || state == State.SAVING; }
    public boolean progress(State phase, long bytes, long expected, long saved, long now) {
        if (!active() || (phase != State.DOWNLOADING && phase != State.SAVING)) return false;
        if (state == State.SAVING && phase == State.DOWNLOADING) return false;
        downloaded = Math.max(downloaded, boundedBytes(bytes));
        total = expected < 0 ? -1 : Math.max(downloaded, boundedTotal(expected));
        copied = Math.min(downloaded, Math.max(copied, boundedBytes(saved)));
        state = phase; updatedAt = Math.max(createdAt, now); return true;
    }
    public boolean finish(State terminal, String note, long bytes, long now) {
        if (!active() || terminal == State.WAITING || terminal == State.DOWNLOADING || terminal == State.SAVING) return false;
        state = terminal; message = note == null ? "" : note.substring(0, Math.min(240, note.length()));
        updatedAt = Math.max(createdAt, now);
        if (terminal == State.COMPLETED) { downloaded = boundedBytes(bytes); total = downloaded; copied = downloaded; }
        return true;
    }
    public boolean recoverAfterRestart(long now) {
        return active() && finish(State.INTERRUPTED, "上次退出时未完成，请回原页面重新下载", downloaded, now);
    }
    public int progressPermille() {
        if (state == State.COMPLETED) return 1000;
        if (total < 0) return -1;
        if (total == 0) return 0;
        long bytes = state == State.SAVING ? copied : downloaded;
        return (int) Math.min(1000, Math.max(0, bytes * 1000 / total));
    }
    public static final String APK_MIME = "application/vnd.android.package-archive";
    public boolean isApk() { return name.toLowerCase(Locale.ROOT).endsWith(".apk") || APK_MIME.equalsIgnoreCase(mime); }
    public boolean canOpen() { return state == State.COMPLETED && validDestination(destination); }
    public static boolean validDestination(String value) {
        try { URI uri = new URI(value); return "content".equals(uri.getScheme()) && uri.getRawAuthority() != null
                && !uri.getRawAuthority().isEmpty() && uri.getRawUserInfo() == null && value.length() <= 8192; }
        catch (Exception e) { return false; }
    }
    public DownloadRecord copy() {
        DownloadRecord copy = new DownloadRecord(id, name, mime, total, createdAt);
        copy.destination = destination; copy.message = message; copy.updatedAt = updatedAt;
        copy.downloaded = downloaded; copy.copied = copied; copy.state = state; return copy;
    }
    public static String formatBytes(long bytes) {
        if (bytes < 0) return "大小未知";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.CHINA, "%.1f MB", bytes / (1024.0 * 1024));
    }
    public String statusText() {
        switch (state) {
            case WAITING: return "选择保存位置";
            case DOWNLOADING: return "下载中";
            case SAVING: return "保存到目标位置";
            case COMPLETED: return "已完成";
            case FAILED: return "下载失败";
            case CANCELLED: return "已取消";
            default: return "已中断";
        }
    }
    private static long boundedBytes(long value) { return Math.max(0, Math.min(DownloadPolicy.MAX_BYTES, value)); }
    private static long boundedTotal(long value) { return value < 0 ? -1 : boundedBytes(value); }
}
