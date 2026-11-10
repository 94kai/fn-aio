package top.fnaio.direct;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.WebView;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Native-initiated bounded JS reads, not an addJavascriptInterface/native message bridge. */
public final class WebDownloads {
    public static final int SAVE_FILE = 42;
    private final Activity activity;
    private final WebView web;
    private final Supplier<AccessRoute> routes;
    private final Consumer<String> notice;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService fileActions = Executors.newSingleThreadExecutor();
    private final DownloadHistory history;
    private final DownloadManagerDialog manager;
    private String captureScript, key;
    private int document;
    private boolean resumed, destroyed, polling;
    private Pending pending;
    private volatile HttpURLConnection connection;
    private final Runnable pollTask = this::poll;

    private static final class Pending {
        final boolean local;
        final AccessRoute route;
        final String url, name, mime, userAgent, jsKey, id;
        final int document;
        final long size;
        final AtomicBoolean cancelled = new AtomicBoolean();
        File temp;
        Uri destination;
        long offset, savedSize;
        String recordId;
        Pending(boolean local, AccessRoute route, String url, String name, String mime, String agent, String key, String id, int document, long size) {
            this.local = local; this.route = route; this.url = url; this.name = name; this.mime = mime;
            this.userAgent = agent; this.jsKey = key; this.id = id; this.document = document; this.size = size;
        }
    }
    private static final class DownloadFailure extends Exception {
        DownloadFailure(String message) { super(message); }
    }
    public WebDownloads(Activity activity, WebView web, Supplier<AccessRoute> routes, Consumer<String> notice) {
        this.activity = activity; this.web = web; this.routes = routes; this.notice = notice;
        history = new DownloadHistory(activity);
        manager = new DownloadManagerDialog(activity, history, this::cancel, this::openSaved);
        try (InputStream input = activity.getAssets().open("download-capture.js"); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
            captureScript = new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception ignored) { captureScript = ""; }
        web.setDownloadListener(this::onDownload);
    }
    public void pageStarted() {
        document++; key = null; polling = false; handler.removeCallbacks(pollTask);
        // A blob belongs to its originating document. Never read it from a replacement page.
        if (pending != null && pending.local) {
            DownloadRecord record = history.get(pending.recordId);
            if (record != null && record.active()) {
                history.finish(pending.recordId, DownloadRecord.State.INTERRUPTED, "网页已切换，请回原页面重新下载", pending.offset);
                fail(pending, "网页已切换，本次导出已取消，请重新点击下载");
            }
        }
    }
    public void pageFinished(String url) {
        if (destroyed || key != null || captureScript.isEmpty() || !DownloadPolicy.serviceUrl(routes.get(), url)
                || !DownloadPolicy.serviceUrl(routes.get(), web.getUrl())) return;
        key = "__aio_download_" + UUID.randomUUID().toString().replace("-", "");
        String currentKey = key;
        web.evaluateJavascript(captureScript + "(" + JSONObject.quote(currentKey) + "," + DownloadPolicy.MAX_BYTES + "," + DownloadPolicy.CHUNK_BYTES + ");", result -> schedule(300));
    }
    public void resume() { resumed = true; manager.resume(); schedule(300); }
    public void pause() { resumed = false; manager.pause(); handler.removeCallbacks(pollTask); }
    public void showManager() { manager.show(); }
    public boolean dismissManager() { return manager.dismiss(); }
    public boolean busy() { return pending != null; }
    private void schedule(long delay) {
        handler.removeCallbacks(pollTask);
        if (!destroyed && resumed && key != null) handler.postDelayed(pollTask, delay);
    }
    private boolean current(Pending task) {
        return !destroyed && pending == task && !task.cancelled.get()
                && (!task.local || (task.document == document && task.jsKey.equals(key) && task.route == routes.get()
                    && DownloadPolicy.serviceUrl(task.route, web.getUrl())));
    }
    private void evaluate(String jsKey, String expression, Consumer<Object> callback) {
        if (destroyed) return;
        String ref = "window[" + JSONObject.quote(jsKey) + "]";
        web.evaluateJavascript("JSON.stringify(" + ref + " ? " + ref + "." + expression + " : null)", result -> {
            if (destroyed) return;
            Object value = null;
            try {
                Object decoded = new JSONTokener(result).nextValue();
                value = decoded instanceof String ? new JSONTokener((String) decoded).nextValue() : decoded;
            } catch (Exception ignored) {}
            callback.accept(value == JSONObject.NULL ? null : value);
        });
    }
    private void poll() {
        if (destroyed || !resumed || key == null) return;
        if (pending != null || polling) { schedule(500); return; }
        polling = true;
        String currentKey = key; int epoch = document; AccessRoute route = routes.get();
        evaluate(currentKey, "info()", value -> {
            polling = false;
            if (epoch != document || !currentKey.equals(key) || route != routes.get()) { schedule(500); return; }
            if (pending == null && value instanceof JSONObject) {
                JSONObject info = (JSONObject) value;
                String state = info.optString("state"), id = info.optString("id");
                if ("error".equals(state)) { release(currentKey, id); notice.accept("网页文件不可用或超过 256 MiB，请重新导出"); }
                else if ("ready".equals(state)) {
                    long size = info.optLong("size", -1);
                    if (id.isEmpty() || size < 0 || size > DownloadPolicy.MAX_BYTES || !DownloadPolicy.serviceUrl(route, web.getUrl())) release(currentKey, id);
                    else {
                        pending = new Pending(true, route, "", DownloadPolicy.safeName(info.optString("name")), DownloadPolicy.mime(info.optString("mime")), "", currentKey, id, epoch, size);
                        chooseDestination(pending);
                    }
                }
            }
            schedule(500);
        });
    }
    public boolean localNavigation(String url, String filename) {
        if (!DownloadPolicy.localUrl(routes.get(), web.getUrl(), url)) return false;
        if (pending != null) { notice.accept("已有下载正在处理，请稍后再试"); return true; }
        if (key == null) pageFinished(web.getUrl());
        if (key == null) { notice.accept("网页下载尚未准备好，请刷新后重试"); return true; }
        evaluate(key, "prepare(" + JSONObject.quote(url) + "," + JSONObject.quote(DownloadPolicy.safeName(filename)) + ")", result -> schedule(100));
        return true;
    }
    private void onDownload(String url, String userAgent, String disposition, String mime, long size) {
        if (destroyed) return;
        if (key == null) pageFinished(web.getUrl());
        if (localNavigation(url, DownloadPolicy.filename(url, disposition))) return;
        AccessRoute route = routes.get();
        if (!DownloadPolicy.serviceUrl(route, web.getUrl()) || !DownloadPolicy.serviceUrl(route, url)) {
            notice.accept("下载不属于当前服务，请通过外部浏览器处理"); return;
        }
        if (pending != null) { notice.accept("已有下载正在处理，请稍后再试"); return; }
        if (size > DownloadPolicy.MAX_BYTES) { notice.accept("当前版本单个下载最多支持 256 MiB"); return; }
        pending = new Pending(false, route, url, DownloadPolicy.filename(url, disposition), DownloadPolicy.mime(mime), userAgent, "", "", document, size);
        chooseDestination(pending);
    }
    private void chooseDestination(Pending task) {
        task.recordId = history.add(task.name, task.mime, task.size);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                .setType(task.mime).putExtra(Intent.EXTRA_TITLE, task.name);
        try { activity.startActivityForResult(intent, SAVE_FILE); }
        catch (ActivityNotFoundException e) { fail(task, "设备没有可用的系统文件保存器"); }
    }
    public boolean activityResult(int request, int result, Intent data) {
        if (request != SAVE_FILE) return false;
        Pending task = pending;
        if (task == null) return true;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) {
            history.finish(task.recordId, DownloadRecord.State.CANCELLED, "已取消选择保存位置", 0);
            fail(task, "已取消保存"); return true;
        }
        if (!current(task)) { fail(task, "页面已变化，请重新点击下载"); return true; }
        Uri destination = data.getData();
        if (!"content".equals(destination.getScheme())) { fail(task, "文件保存器返回了不支持的位置"); return true; }
        task.destination = destination;
        // Persist only this explicitly selected document's read grant, never broad storage access.
        if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) try {
            activity.getContentResolver().takePersistableUriPermission(destination, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) { /* Some providers grant access only for the current session. */ }
        history.destination(task.recordId, destination.toString(), null);
        history.progress(task.recordId, DownloadRecord.State.DOWNLOADING, 0, task.size, 0);
        notice.accept("正在下载并保存，可在悬浮球的下载管理中查看进度");
        worker.execute(() -> {
            try {
                try (android.database.Cursor cursor = activity.getContentResolver().query(destination, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) history.destination(task.recordId, destination.toString(), cursor.getString(0));
                } catch (Exception ignored) {}
                task.temp = File.createTempFile("aio-download-", ".part", activity.getCacheDir());
                if (task.local) handler.post(() -> { if (current(task)) readChunk(task); else fail(task, "页面已变化，下载已取消"); });
                else downloadHttp(task);
            } catch (Exception e) { handler.post(() -> fail(task, "无法创建下载缓存，请检查存储空间")); }
        });
        return true;
    }
    private void readChunk(Pending task) {
        if (!current(task)) { fail(task, "网页已变化，下载已取消"); return; }
        if (task.offset == task.size) { worker.execute(() -> save(task)); return; }
        evaluate(task.jsKey, "read(" + JSONObject.quote(task.id) + "," + task.offset + ")", accepted -> {
            if (!current(task)) { fail(task, "网页已变化，下载已取消"); return; }
            if (!Boolean.TRUE.equals(accepted)) { fail(task, "无法读取网页生成的文件，请重新导出"); return; }
            awaitChunk(task, SystemClock.elapsedRealtime() + 15_000);
        });
    }
    private void awaitChunk(Pending task, long deadline) {
        if (!current(task)) { fail(task, "网页已变化，下载已取消"); return; }
        evaluate(task.jsKey, "take(" + JSONObject.quote(task.id) + ")", value -> {
            if (!current(task)) { fail(task, "网页已变化，下载已取消"); return; }
            if (value == null) {
                if (SystemClock.elapsedRealtime() > deadline) fail(task, "网页文件读取超时，请保持应用打开后重试");
                else handler.postDelayed(() -> awaitChunk(task, deadline), 40);
                return;
            }
            if (!(value instanceof JSONObject)) { fail(task, "网页文件数据无效"); return; }
            JSONObject chunk = (JSONObject) value;
            String encoded = chunk.optString("data");
            if (chunk.optBoolean("error") || chunk.optLong("offset", -1) != task.offset || encoded.length() > 90000) { fail(task, "网页文件数据无效"); return; }
            byte[] bytes;
            try { bytes = Base64.decode(encoded, Base64.NO_WRAP); }
            catch (Exception e) { fail(task, "网页文件数据无效"); return; }
            long expected = Math.min(DownloadPolicy.CHUNK_BYTES, task.size - task.offset);
            if (bytes.length != expected) { fail(task, "网页文件长度不匹配"); return; }
            worker.execute(() -> {
                try {
                    if (task.cancelled.get()) return;
                    try (OutputStream out = new FileOutputStream(task.temp, true)) { out.write(bytes); }
                    handler.post(() -> {
                        task.offset += bytes.length;
                        history.progress(task.recordId, DownloadRecord.State.DOWNLOADING, task.offset, task.size, 0);
                        readChunk(task);
                    });
                } catch (Exception e) { handler.post(() -> fail(task, "写入下载缓存失败，请检查存储空间")); }
            });
        });
    }
    private void downloadHttp(Pending task) {
        String next = task.url;
        long deadline = SystemClock.elapsedRealtime() + 5 * 60_000;
        try {
            for (int redirects = 0; redirects <= 5; redirects++) {
                if (task.cancelled.get()) throw new DownloadFailure("下载已取消");
                if (SystemClock.elapsedRealtime() > deadline) throw new DownloadFailure("下载超过五分钟，请重新尝试");
                if (!DownloadPolicy.serviceUrl(task.route, next)) throw new DownloadFailure("下载跳转离开当前服务，已停止且不会转发登录凭据");
                HttpURLConnection http = (HttpURLConnection) new URL(next).openConnection();
                connection = http;
                try {
                    http.setConnectTimeout(8000); http.setReadTimeout(15000); http.setInstanceFollowRedirects(false);
                    http.setRequestProperty("Accept-Encoding", "identity");
                    String cookie = CookieManager.getInstance().getCookie(next);
                    if (cookie != null) http.setRequestProperty("Cookie", cookie);
                    if (task.userAgent != null && !task.userAgent.isEmpty()) http.setRequestProperty("User-Agent", task.userAgent);
                    int code = http.getResponseCode();
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        String location = http.getHeaderField("Location");
                        if (location == null || redirects == 5) throw new DownloadFailure("下载重定向无效或次数过多");
                        next = new URL(new URL(next), location).toString(); continue;
                    }
                    if (code == 401 || code == 403) throw new DownloadFailure("下载登录状态已失效或无权限，请刷新登录后重新下载");
                    if (code != 200) throw new DownloadFailure("服务器未能提供文件，请稍后重试");
                    long length = http.getContentLengthLong();
                    if (length > DownloadPolicy.MAX_BYTES) throw new DownloadFailure("当前版本单个下载最多支持 256 MiB");
                    history.progress(task.recordId, DownloadRecord.State.DOWNLOADING, 0, length, 0);
                    try (BufferedInputStream input = new BufferedInputStream(http.getInputStream()); OutputStream output = new FileOutputStream(task.temp)) {
                        input.mark(4097);
                        byte[] prefix = new byte[4096]; int used = 0, count;
                        while (used < prefix.length && (count = input.read(prefix, used, prefix.length - used)) != -1) {
                            used += count;
                            if (task.cancelled.get()) throw new DownloadFailure("下载已取消");
                            if (SystemClock.elapsedRealtime() > deadline) throw new DownloadFailure("下载超过五分钟，请重新尝试");
                        }
                        if (AccessPolicy.invalidGatewayBody(new String(prefix, 0, used, StandardCharsets.UTF_8))) throw new DownloadFailure("飞牛登录已失效，请恢复登录后重新下载");
                        input.reset();
                        byte[] buffer = new byte[65536]; long total = 0;
                        while ((count = input.read(buffer)) != -1) {
                            total += count;
                            if (task.cancelled.get()) throw new DownloadFailure("下载已取消");
                            if (total > DownloadPolicy.MAX_BYTES) throw new DownloadFailure("当前版本单个下载最多支持 256 MiB");
                            if (SystemClock.elapsedRealtime() > deadline) throw new DownloadFailure("下载超过五分钟，请重新尝试");
                            output.write(buffer, 0, count);
                            history.progress(task.recordId, DownloadRecord.State.DOWNLOADING, total, length, 0);
                        }
                        if (length >= 0 && length != total) throw new DownloadFailure("文件未下载完整，请重试");
                    }
                    save(task); return;
                } finally { http.disconnect(); connection = null; }
            }
        } catch (DownloadFailure e) { handler.post(() -> fail(task, e.getMessage())); }
        catch (Exception e) { handler.post(() -> fail(task, "下载失败，请检查网络、证书、登录状态或存储空间")); }
    }
    /** Write to the user-selected document only after a complete download has been staged. */
    private void save(Pending task) {
        try {
            if (task.cancelled.get()) return;
            task.savedSize = task.temp.length();
            history.progress(task.recordId, DownloadRecord.State.SAVING, task.savedSize, task.savedSize, 0);
            try (InputStream input = new FileInputStream(task.temp); OutputStream output = activity.getContentResolver().openOutputStream(task.destination, "wt")) {
                if (output == null) throw new java.io.IOException();
                byte[] buffer = new byte[65536]; int count; long copied = 0;
                while ((count = input.read(buffer)) != -1) {
                    if (task.cancelled.get()) throw new java.io.IOException();
                    output.write(buffer, 0, count); copied += count;
                    history.progress(task.recordId, DownloadRecord.State.SAVING, task.savedSize, task.savedSize, copied);
                }
            }
            history.finish(task.recordId, DownloadRecord.State.COMPLETED, "", task.savedSize);
            handler.post(() -> complete(task, "文件已保存，可在下载管理中打开"));
        } catch (Exception e) { handler.post(() -> fail(task, "保存失败，请检查目标位置；已创建的空白或不完整文件可手动删除")); }
        finally { cleanup(task); }
    }
    private void release(String jsKey, String id) {
        if (!destroyed && jsKey != null && jsKey.equals(key)) evaluate(jsKey, "release(" + JSONObject.quote(id) + ")", ignored -> {});
    }
    private void complete(Pending task, String message) {
        if (pending != task) return;
        if (task.local) release(task.jsKey, task.id);
        pending = null; notice.accept(message); schedule(500);
    }
    private void fail(Pending task, String message) {
        task.cancelled.set(true);
        history.finish(task.recordId, DownloadRecord.State.FAILED, message, task.offset);
        if (!worker.isShutdown()) worker.execute(() -> cleanup(task));
        if (pending == task) {
            if (task.local) release(task.jsKey, task.id);
            pending = null;
            if (!destroyed) notice.accept(message);
            schedule(500);
        }
    }
    public void cancel(String recordId) {
        Pending task = pending;
        if (task == null || !recordId.equals(task.recordId)) return;
        DownloadRecord record = history.get(recordId);
        if (record == null || !record.active()) return;
        history.finish(recordId, DownloadRecord.State.CANCELLED, "用户取消；目标位置可能有空白或不完整文件", task.offset);
        task.cancelled.set(true);
        HttpURLConnection http = connection; if (http != null) http.disconnect();
        fail(task, "已取消下载");
    }
    private void openSaved(String recordId, boolean share) {
        DownloadRecord record = history.get(recordId);
        if (record == null || !record.canOpen() || destroyed) return;
        fileActions.execute(() -> {
            Uri uri = Uri.parse(record.destination);
            try (android.content.res.AssetFileDescriptor descriptor = activity.getContentResolver().openAssetFileDescriptor(uri, "r")) {
                if (descriptor == null) throw new java.io.FileNotFoundException();
            } catch (Exception e) {
                handler.post(() -> { if (!destroyed) notice.accept("文件已移动、删除或访问权限失效，请在系统文件管理器中查看"); }); return;
            }
            String resolved = record.mime;
            try { String providerType = activity.getContentResolver().getType(uri); if (providerType != null) resolved = DownloadPolicy.mime(providerType); }
            catch (Exception ignored) {}
            if ("application/octet-stream".equals(resolved)) {
                int dot = record.name.lastIndexOf('.');
                String extension = dot >= 0 ? record.name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
                String inferred = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                if (inferred != null) resolved = inferred;
            }
            String mime = resolved;
            handler.post(() -> {
                if (destroyed) return;
                Intent action = share ? new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                        : new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime);
                action.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                action.setClipData(ClipData.newRawUri("下载文件", uri));
                try { activity.startActivity(Intent.createChooser(action, share ? "分享文件" : "打开文件")); }
                catch (ActivityNotFoundException e) { notice.accept("没有可用的应用，请安装对应文件类型的查看器"); }
                catch (SecurityException e) { notice.accept("系统未允许打开此文件，请在系统文件管理器中打开"); }
            });
        });
    }
    private void cleanup(Pending task) {
        if (task.temp == null) return;
        try {
            // Only our own freshly created cache file; never delete the user's document URI.
            if (task.temp.getCanonicalFile().getParentFile().equals(activity.getCacheDir().getCanonicalFile())
                    && task.temp.getName().startsWith("aio-download-")) java.nio.file.Files.deleteIfExists(task.temp.toPath());
        } catch (Exception ignored) {}
    }
    public void close() {
        destroyed = true; resumed = false; manager.close(); handler.removeCallbacksAndMessages(null);
        if (pending != null) {
            Pending task = pending; task.cancelled.set(true);
            history.finish(task.recordId, DownloadRecord.State.INTERRUPTED, "应用已关闭，请回原页面重新下载", task.offset);
            worker.execute(() -> cleanup(task));
        }
        fileActions.shutdown();
        HttpURLConnection http = connection; if (http != null) http.disconnect();
        worker.shutdown();
    }
}
