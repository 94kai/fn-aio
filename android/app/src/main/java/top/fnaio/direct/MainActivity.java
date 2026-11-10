package top.fnaio.direct;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final List<WebView> popups = new ArrayList<>();
    private WebView web;
    private WebDownloads downloads;
    private FrameLayout content;
    private FloatingBall ball;
    private LinearLayout dropdown;
    private AccessPolicy policy;
    private AccessRoute route;
    private SharedPreferences preferences;
    private boolean active, recovering, probing, destroyed, clearHistoryOnFinish, clearRecoveryHistory;
    private boolean menuExpanded, positionReady;
    private int generation;
    private long recoveryDeadline;
    private ValueCallback<Uri[]> fileCallback;
    private AlertDialog externalDialog, exitDialog;
    private android.window.OnBackInvokedCallback backCallback;
    private static final int FILE_PICKER = 41;
    private static final int BALL_DP = 44;
    private static final int MENU_HEIGHT_DP = 156;
    private static final long RECOVERY_LIMIT_MS = 180_000;

    // fnOS and DSH require JavaScript; no native JS bridge or TLS exceptions.
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        preferences = getSharedPreferences("endpoint", MODE_PRIVATE);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        content = new FrameLayout(this);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));
        web = new WebView(this);
        content.addView(web, new FrameLayout.LayoutParams(-1, -1));
        createFloatingMenu();
        setContentView(root);
        WebView.setWebContentsDebuggingEnabled(false);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture, Message result) {
                if (!userGesture) { notice("已阻止非用户触发的新窗口"); return false; }
                return capturePopup(result);
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                List<String> types = new ArrayList<>();
                for (String type : params.getAcceptTypes()) if (type.contains("/")) types.add(type);
                if (!types.isEmpty()) intent.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try { startActivityForResult(intent, FILE_PICKER); }
                catch (ActivityNotFoundException e) { fileCallback.onReceiveValue(null); fileCallback = null; notice("设备没有可用的文件选择器"); }
                return true;
            }
        });
        downloads = new WebDownloads(this, web, () -> route, this::notice);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                downloads.pageStarted();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                return interceptNavigation(request.getUrl(), request.hasGesture());
            }
            @Override public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
                if (route == null || !route.allows(url)) return;
                if (recovering) {
                    if (clearRecoveryHistory) { view.clearHistory(); clearRecoveryHistory = false; }
                    scheduleProbe(500); return;
                }
                if (clearHistoryOnFinish) { view.clearHistory(); clearHistoryOnFinish = false; }
                downloads.pageFinished(url);
                if (!route.requiresNasLogin()) return;
                if (policy.servicePage(url)) {
                    int ticket = generation;
                    view.evaluateJavascript("(document.body && document.body.innerText || '').trim().toLowerCase() === 'invalid token'", result -> {
                        if (destroyed || ticket != generation || !route.requiresNasLogin()) return;
                        if ("true".equals(result)) beginRecovery(); else scheduleProbe(60_000);
                    });
                }
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) notice("页面加载失败，请检查网络后用悬浮球刷新");
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler ssl, SslError error) {
                ssl.cancel(); notice("证书校验失败，已阻止连接；请检查设备时间和证书");
            }
        });
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            backCallback = this::handleBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        try {
            policy = new AccessPolicy(preferences.getString("origin", BuildConfig.NAS_ORIGIN), preferences.getString("service", "dsh"));
            route = new AccessRoute(policy, BuildConfig.DIRECT_URL, preferences.getBoolean("directMode", false));
            ball.setDirect(route.direct);
            openTarget();
        } catch (IllegalArgumentException e) { configure(); }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void notice(String message) { if (!destroyed) Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable;
    }
    private View menuAction(String symbol, String title, Runnable action) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(48)));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(24, 63, 94, 151)), background(Color.TRANSPARENT, 10), null));
        TextView icon = new TextView(this); icon.setText(symbol); icon.setTextSize(22); icon.setTextColor(Color.rgb(94, 111, 144)); icon.setGravity(Gravity.CENTER);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon, new LinearLayout.LayoutParams(dp(38), -1));
        TextView label = new TextView(this); label.setText(title); label.setTextSize(14); label.setTextColor(Color.rgb(45, 57, 79)); row.addView(label);
        row.setContentDescription(title); row.setFocusable(true); row.setOnClickListener(v -> action.run()); return row;
    }
    private void createFloatingMenu() {
        dropdown = new LinearLayout(this);
        dropdown.setOrientation(LinearLayout.VERTICAL);
        dropdown.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable panelBackground = background(Color.argb(248, 252, 253, 255), 16);
        panelBackground.setStroke(dp(1), Color.argb(120, 216, 225, 240));
        dropdown.setBackground(panelBackground);
        dropdown.setElevation(dp(6));
        dropdown.setVisibility(View.GONE);
        dropdown.addView(menuAction("⇄", "切换访问方式", () -> { setMenuExpanded(false); chooseAccessMode(); }));
        dropdown.addView(menuAction("↻", "刷新当前页面", () -> { setMenuExpanded(false); refreshCurrentPage(); }));
        dropdown.addView(menuAction("↓", "下载管理", () -> { setMenuExpanded(false); downloads.showManager(); }));
        content.addView(dropdown, new FrameLayout.LayoutParams(dp(208), dp(MENU_HEIGHT_DP)));
        ball = new FloatingBall(this);
        ball.setContentDescription("打开悬浮菜单，可拖动调整位置");
        content.addView(ball, new FrameLayout.LayoutParams(dp(BALL_DP), dp(BALL_DP)));
        ball.setOnClickListener(v -> setMenuExpanded(!menuExpanded));
        ball.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY, startX, startY;
            private boolean dragging;
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        ball.setInteracting(true);
                        downX = event.getRawX(); downY = event.getRawY(); startX = ball.getX(); startY = ball.getY(); dragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                        if (Math.hypot(dx, dy) > ViewConfiguration.get(MainActivity.this).getScaledTouchSlop()) dragging = true;
                        if (dragging) {
                            view.getParent().requestDisallowInterceptTouchEvent(true);
                            positionBall(startX + dx, startY + dy);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (dragging) saveBallPosition(); else view.performClick();
                        ball.setInteracting(false);
                        view.getParent().requestDisallowInterceptTouchEvent(false);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        ball.setInteracting(false);
                        if (dragging) saveBallPosition();
                        view.getParent().requestDisallowInterceptTouchEvent(false);
                        return true;
                    default: return true;
                }
            }
        });
        content.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (r - l <= 0 || b - t <= 0) return;
            if (!positionReady || r - l != oldR - oldL || b - t != oldB - oldT) {
                positionReady = true;
                float x = preferences.getFloat("ballX", 1f) * Math.max(0, content.getWidth() - dp(BALL_DP));
                float y = preferences.getFloat("ballY", 0.16f) * Math.max(0, content.getHeight() - dp(BALL_DP));
                positionBall(x, y);
            }
        });
    }
    private void positionBall(float x, float y) {
        int width = content.getWidth(), height = content.getHeight(), size = dp(BALL_DP);
        ball.setX(FloatingPosition.ballX(x, width, size));
        ball.setY(FloatingPosition.ballY(y, height, size, menuExpanded ? dp(MENU_HEIGHT_DP + 6) : 0));
        // Shrink in exceptionally small windows; dropdown always opens below the ball.
        int menuWidth = Math.min(dp(208), width);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) dropdown.getLayoutParams();
        if (params.width != menuWidth) { params.width = menuWidth; dropdown.setLayoutParams(params); }
        dropdown.setX(FloatingPosition.panelX(ball.getX(), width, size, menuWidth));
        dropdown.setY(ball.getY() + size + dp(6));
    }
    private void saveBallPosition() {
        preferences.edit().putFloat("ballX", FloatingPosition.fraction(ball.getX(), content.getWidth() - dp(BALL_DP)))
            .putFloat("ballY", FloatingPosition.fraction(ball.getY(), content.getHeight() - dp(BALL_DP))).apply();
    }
    private void setMenuExpanded(boolean expanded) {
        menuExpanded = expanded;
        dropdown.animate().cancel();
        if (expanded) {
            dropdown.setAlpha(0f); dropdown.setVisibility(View.VISIBLE); dropdown.animate().alpha(1f).setDuration(140).start();
        } else dropdown.animate().alpha(0f).setDuration(100).withEndAction(() -> { if (!menuExpanded) dropdown.setVisibility(View.GONE); }).start();
        ball.setExpanded(expanded);
        ball.setContentDescription(expanded ? "收起悬浮菜单，可拖动调整位置" : "打开悬浮菜单，可拖动调整位置");
        positionBall(ball.getX(), ball.getY());
    }
    private void chooseAccessMode() {
        if (downloads.busy()) { notice("正在下载或选择保存位置，请完成后再切换访问方式"); return; }
        if (policy == null) { configure(); return; }
        new AlertDialog.Builder(this).setTitle("切换访问方式")
            .setSingleChoiceItems(new String[]{"FN ID（处理飞牛登录）", "HTTPS 直连（不处理飞牛登录）"}, route != null && route.direct ? 1 : 0, (dialog, which) -> {
                boolean direct = which == 1;
                if (route != null && route.direct == direct) { dialog.dismiss(); return; }
                try {
                    AccessRoute next = new AccessRoute(policy, BuildConfig.DIRECT_URL, direct);
                    generation++; handler.removeCallbacks(probeTask); web.stopLoading();
                    route = next;
                    preferences.edit().putBoolean("directMode", direct).apply();
                    web.clearHistory(); clearHistoryOnFinish = true;
                    ball.setDirect(direct);
                    dialog.dismiss(); openTarget();
                    notice(direct ? "已切换为 HTTPS 直连" : "已切换为 FN ID 访问");
                } catch (IllegalArgumentException e) { notice(e.getMessage()); }
            }).setNegativeButton("取消", null).show();
    }
    private void refreshCurrentPage() {
        if (route == null) { configure(); return; }
        if (recovering) { recoveryDeadline = SystemClock.elapsedRealtime() + RECOVERY_LIMIT_MS; scheduleProbe(1000); }
        if (web.getUrl() != null && route.allows(web.getUrl())) web.reload(); else openTarget();
    }
    private void openTarget() {
        generation++; recovering = false; clearRecoveryHistory = false; handler.removeCallbacks(probeTask);
        web.loadUrl(route.entry());
    }
    private void beginRecovery() {
        if (route == null || !route.requiresNasLogin() || recovering) return;
        generation++; recovering = true; clearRecoveryHistory = true;
        recoveryDeadline = SystemClock.elapsedRealtime() + RECOVERY_LIMIT_MS;
        notice("正在恢复飞牛登录；如出现登录页，请在页面中完成登录");
        web.loadUrl(policy.home()); scheduleProbe(1500);
    }
    private final Runnable probeTask = () -> probe();
    private void scheduleProbe(long delay) {
        handler.removeCallbacks(probeTask);
        if (active && !destroyed && route != null && route.requiresNasLogin()) handler.postDelayed(probeTask, delay);
    }
    /** Only FN ID uses this read-only health check. Direct HTTPS never probes NAS. */
    private void probe() {
        if (route == null || !route.requiresNasLogin()) return;
        if (!active || destroyed || probing) { scheduleProbe(3000); return; }
        if (recovering && SystemClock.elapsedRealtime() > recoveryDeadline) {
            notice("等待登录已暂停，完成登录后可用悬浮球刷新继续检查"); return;
        }
        probing = true;
        int ticket = generation;
        AccessPolicy current = policy;
        String cookies = CookieManager.getInstance().getCookie(current.health());
        network.execute(() -> {
            String result = "network";
            List<String> newCookies = new ArrayList<>();
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(current.health()).openConnection();
                connection.setConnectTimeout(6000); connection.setReadTimeout(6000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Cache-Control", "no-cache");
                if (cookies != null) connection.setRequestProperty("Cookie", cookies);
                int code = connection.getResponseCode();
                for (var header : connection.getHeaderFields().entrySet()) {
                    if ("Set-Cookie".equalsIgnoreCase(header.getKey())) newCookies.addAll(header.getValue());
                }
                InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                String body = "";
                if (stream != null) try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[1024];
                    while (out.size() < 4097) {
                        int count = in.read(buffer, 0, Math.min(buffer.length, 4097 - out.size()));
                        if (count == -1) break;
                        out.write(buffer, 0, count);
                    }
                    body = new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
                if (code == 401 || AccessPolicy.invalidGatewayBody(body)) result = "login";
                else if (code == 403) result = "forbidden";
                else if (code == 200 && body.length() <= 4096) {
                    JSONObject health = new JSONObject(body);
                    result = "AIO".equals(health.optString("name")) && health.has("version") ? "ok" : "unexpected";
                } else result = "unexpected";
            } catch (Exception ignored) { /* Never log credentials, response bodies or request URLs. */ }
            finally { if (connection != null) connection.disconnect(); }
            String outcome = result;
            handler.post(() -> {
                probing = false;
                if (destroyed || ticket != generation || !active || !route.requiresNasLogin()) { scheduleProbe(1500); return; }
                for (String cookie : newCookies) CookieManager.getInstance().setCookie(current.health(), cookie);
                CookieManager.getInstance().flush();
                if ("ok".equals(outcome)) {
                    if (recovering) { clearHistoryOnFinish = true; openTarget(); }
                    else scheduleProbe(60_000);
                } else if ("login".equals(outcome)) {
                    if (!recovering) beginRecovery(); else scheduleProbe(3000);
                } else if ("forbidden".equals(outcome)) notice(getString(R.string.owner_required));
                else scheduleProbe(recovering ? 5000 : 60_000);
            });
        });
    }
    private void configure() {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(16), dp(4), dp(16), dp(4));
        EditText origin = new EditText(this); origin.setSingleLine(true); origin.setHint("https://你的FNID.fnos.net");
        origin.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        origin.setText(policy == null ? BuildConfig.NAS_ORIGIN : policy.origin); form.addView(origin);
        EditText service = new EditText(this); service.setSingleLine(true); service.setHint("服务标识，例如 dsh"); service.setText(policy == null ? "dsh" : policy.service); form.addView(service);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("访问设置（不填写密码或 Token）").setView(form).setNegativeButton("取消", null).setPositiveButton("保存并进入", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                AccessPolicy next = new AccessPolicy(origin.getText().toString(), service.getText().toString().trim());
                AccessRoute nextRoute = new AccessRoute(next, BuildConfig.DIRECT_URL, false);
                web.stopLoading(); policy = next; route = nextRoute;
                preferences.edit().putString("origin", next.origin).putString("service", next.service).putBoolean("directMode", false).apply();
                web.clearHistory(); clearHistoryOnFinish = true; dialog.dismiss(); openTarget();
            } catch (IllegalArgumentException e) { origin.setError(e.getMessage()); }
        })); dialog.show();
    }

    /** Internal links stay in WebView; external user links open the system browser. */
    private boolean interceptNavigation(Uri uri, boolean userGesture) {
        if (route == null) return true;
        if (downloads.localNavigation(uri.toString(), "下载文件.bin")) return true;
        AccessRoute.Navigation destination = route.navigation(uri.toString());
        if (destination == AccessRoute.Navigation.IN_APP) return false;
        if (destination == AccessRoute.Navigation.BLOCKED) { notice("已阻止不支持或含凭据的链接"); return true; }
        if (userGesture) openExternal(uri); else confirmExternal(uri);
        return true;
    }
    private void openExternal(Uri uri) {
        if (!AccessRoute.safeExternal(uri.toString())) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (ActivityNotFoundException e) { notice("未找到外部浏览器"); }
    }
    private void confirmExternal(Uri uri) {
        if (externalDialog != null && externalDialog.isShowing()) return;
        externalDialog = new AlertDialog.Builder(this).setMessage("页面请求跳转其他网站，是否使用外部浏览器打开？")
            .setNegativeButton("取消", null).setPositiveButton("打开浏览器", (d, w) -> openExternal(uri)).create();
        externalDialog.show();
    }
    /** Capture target=_blank/window.open without allowing a second authenticated browsing surface. */
    private boolean capturePopup(Message result) {
        if (popups.size() >= 3) return false;
        WebView popup = new WebView(this);
        popup.getSettings().setAllowFileAccess(false);
        popup.getSettings().setAllowContentAccess(false);
        popup.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(popup, false);
        popups.add(popup);
        final boolean[] handled = {false};
        AccessRoute popupRoute = route;
        Runnable close = () -> { if (popups.remove(popup)) { popup.stopLoading(); popup.destroy(); } };
        java.util.function.Consumer<Uri> navigate = uri -> {
            if (destroyed || route != popupRoute || !popups.contains(popup)) { handler.post(close); return; }
            if (handled[0] || "about:blank".equals(uri.toString())) return;
            handled[0] = true;
            if (!interceptNavigation(uri, true)) web.loadUrl(uri.toString());
            handler.post(close);
        };
        popup.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                navigate.accept(request.getUrl()); return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // Also catch form POSTs, which do not invoke shouldOverrideUrlLoading. Never send a hidden popup request.
                if (request.isForMainFrame() && !"about:blank".equals(request.getUrl().toString())) {
                    handler.post(() -> {
                        if ("GET".equals(request.getMethod())) navigate.accept(request.getUrl());
                        else { notice("暂不支持新窗口表单提交，请在当前页面打开"); close.run(); }
                    });
                }
                // No hidden popup documents, iframes or subresources reach the network.
                return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler ssl, SslError error) { ssl.cancel(); handler.post(close); }
        });
        ((WebView.WebViewTransport) result.obj).setWebView(popup); result.sendToTarget();
        handler.postDelayed(close, 15_000);
        return true;
    }
    private void handleBack() {
        if (downloads.dismissManager()) return;
        switch (AccessRoute.backAction(menuExpanded, web.canGoBack())) {
            case CLOSE_MENU: setMenuExpanded(false); break;
            case GO_BACK: web.goBack(); break;
            case CONFIRM_EXIT:
                if (exitDialog != null && exitDialog.isShowing()) return;
                exitDialog = new AlertDialog.Builder(this).setMessage("已经是第一页，是否退出应用？")
                    .setNegativeButton("留在应用", null).setPositiveButton("退出", (d, w) -> finish()).create();
                exitDialog.show(); break;
        }
    }
    @Override public void onBackPressed() { handleBack(); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (downloads.activityResult(request, result, data)) return;
        if (request == FILE_PICKER && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data)); fileCallback = null;
        }
    }
    @Override protected void onResume() {
        super.onResume(); active = true; web.onResume(); downloads.resume(); scheduleProbe(1000);
    }
    @Override protected void onPause() {
        active = false; generation++; handler.removeCallbacks(probeTask);
        CookieManager.getInstance().flush(); downloads.pause(); web.onPause(); super.onPause();
    }
    @Override protected void onDestroy() {
        destroyed = true; generation++; handler.removeCallbacksAndMessages(null); network.shutdownNow(); downloads.close();
        if (android.os.Build.VERSION.SDK_INT >= 33 && backCallback != null) getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        for (WebView popup : popups) { popup.stopLoading(); popup.destroy(); } popups.clear();
        if (externalDialog != null) externalDialog.dismiss();
        if (exitDialog != null) exitDialog.dismiss();
        web.stopLoading(); web.destroy(); super.onDestroy();
    }
}
