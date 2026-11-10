package top.fnaio.direct;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.Settings;
import java.util.function.Consumer;

/** User-clicked installs only; the system installer always owns confirmation. */
public final class ApkInstaller {
    public static final int INSTALL_SETTINGS = 43;
    private final Activity activity;
    private final Consumer<String> notice, retry;
    private final SharedPreferences preferences;
    private AlertDialog dialog;
    private boolean destroyed;

    public ApkInstaller(Activity activity, Consumer<String> notice, Consumer<String> retry) {
        this.activity = activity; this.notice = notice; this.retry = retry;
        preferences = activity.getSharedPreferences("apk_install", Activity.MODE_PRIVATE);
    }
    public void install(String recordId, Uri uri) {
        if (destroyed) return;
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            dismiss();
            dialog = new AlertDialog.Builder(activity).setTitle("允许安装应用")
                .setMessage("首次安装 APK 需要在系统设置中允许 dsh 安装未知应用。授权后返回，将继续打开本次 APK 的系统安装界面；安装仍需你确认。")
                .setNegativeButton("取消", null)
                .setNeutralButton("浏览文件", (d, w) -> browse(uri))
                .setPositiveButton("去授权", (d, w) -> {
                    // Only the record ID is retained across process recreation, never a source URL.
                    preferences.edit().putString("pendingRecord", recordId).apply();
                    try {
                        activity.startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName())), INSTALL_SETTINGS);
                    } catch (ActivityNotFoundException | SecurityException e) {
                        preferences.edit().remove("pendingRecord").apply();
                        fallback(uri, "系统无法打开安装授权设置，可尝试从系统文件浏览器打开 APK。");
                    }
                }).create();
            dialog.show();
            return;
        }
        Intent action = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, DownloadRecord.APK_MIME);
        action.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        action.setClipData(ClipData.newRawUri("APK 安装文件", uri));
        try { activity.startActivity(action); }
        catch (ActivityNotFoundException | SecurityException e) {
            fallback(uri, "系统无法打开此 APK 的安装界面，可尝试从系统文件浏览器打开。文件提供方或设备策略也可能限制安装。");
        }
    }
    public boolean activityResult(int request) {
        if (request != INSTALL_SETTINGS) return false;
        String recordId = preferences.getString("pendingRecord", null);
        preferences.edit().remove("pendingRecord").apply();
        if (!destroyed && recordId != null) {
            if (activity.getPackageManager().canRequestPackageInstalls()) retry.accept(recordId);
            else notice.accept("尚未允许安装，未启动安装；可再次点击安装或使用浏览文件。");
        }
        return true;
    }
    private void fallback(Uri uri, String message) {
        if (destroyed) return;
        dismiss();
        dialog = new AlertDialog.Builder(activity).setTitle("无法启动安装").setMessage(message)
            .setNegativeButton("取消", null).setPositiveButton("浏览文件", (d, w) -> browse(uri)).create();
        dialog.show();
    }
    public void browse(Uri uri) {
        if (destroyed) return;
        // The standard system document browser supports an initial location hint, not guaranteed exact highlighting.
        Intent browse = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*").putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri);
        try {
            activity.startActivity(browse);
            notice.accept("请在系统文件浏览器中查看 APK；若仅支持选择文件，请长按文件查看可用操作。定位位置取决于系统支持。");
        } catch (ActivityNotFoundException | SecurityException e) {
            notice.accept("系统没有可用的文件浏览器，请使用下载记录中的分享功能或手动打开文件管理器。");
        }
    }
    private void dismiss() { if (dialog != null) { dialog.dismiss(); dialog = null; } }
    public void close() { destroyed = true; dismiss(); }
}
