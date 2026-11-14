package top.fnaio.direct;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native shortcut panel. It only calls the existing authenticated Pocket Kit
 * envelopes on the configured LAN or public service; the playlist and volume
 * belong to the selected speaker on the server.
 */
public final class QuickActivity extends Activity {
    private static final int BG = 0xFFF5F6F3;
    private static final int CARD = 0xFFFFFFFF;
    private static final int INK = 0xFF16302B;
    private static final int MUTED = 0xFF6D7C7A;
    private static final int TEAL = 0xFF0E7C73;
    private static final int MINT = 0xFFE1F2EE;
    private static final int MINT_SOFT = 0xFFF0F8F5;
    private static final int GRAY = 0xFFF0F2F1;
    private static final int LINE = 0xFFE2E8E5;
    private static final int DANGER = 0xFFB23A32;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService playbackExecutor = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private LinearLayout page, controls, profileChoices;
    private TextView status, pass, volumeLabel, nowPlaying;
    private FrameLayout stopButton, playlistButton, favoritesButton;
    private SeekBar volume;
    private Button modeCycleButton;
    private JSONArray profileData = new JSONArray(), playlistData = new JSONArray(), favoriteData = new JSONArray();
    private String selectedProfile = "", playMode = "sequence", lastSearchKeyword = "";
    private AlertDialog musicDialog;
    private String musicDialogTab = "playlist";
    private String base = "";
    private int generation;
    private boolean destroyed, entered;
    private long lastRefresh;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("quick-panel", MODE_PRIVATE);
        migrateLegacySettings();
        base = prefs.getString(prefs.getBoolean("use_public", false) ? "public_url" : "lan", "");
        render();
        page.post(() -> { entered = true; autoRefresh(true); });
    }
    @Override protected void onResume() {
        super.onResume();
        // Refreshing when the panel is reopened keeps devices and volume current
        // without polling while it stays in the background.
        if (entered && !base.isEmpty() && SystemClock.elapsedRealtime() - lastRefresh > 60_000L) autoRefresh(false);
    }
    private void migrateLegacySettings() {
        Object legacy = prefs.getAll().get("public");
        if (legacy == null) return;
        SharedPreferences.Editor editor = prefs.edit();
        if (legacy instanceof String && !prefs.contains("public_url")) editor.putString("public_url", (String) legacy);
        // Old versions stored a boolean and URL under the same key: the URL was lost.
        // Fall back to LAN until the user re-enters the public URL.
        if (!prefs.contains("use_public")) editor.putBoolean("use_public", false);
        editor.remove("public").apply();
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private GradientDrawable rounds(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private GradientDrawable rounds(int color, int radius, int strokeWidth, int strokeColor) {
        GradientDrawable d = rounds(color, radius); d.setStroke(dp(strokeWidth), strokeColor); return d;
    }
    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setIncludeFontPadding(false); return t;
    }
    private void icon(TextView view, String name, int color, int size) {
        QuickIcon drawable = new QuickIcon(name, color);
        drawable.setBounds(0, 0, dp(size), dp(size));
        view.setCompoundDrawablesRelative(drawable, null, null, null);
        view.setCompoundDrawablePadding(dp(8));
    }
    private ImageView iconView(String name, int size, int color) {
        ImageView view = new ImageView(this);
        QuickIcon drawable = new QuickIcon(name, color);
        drawable.setBounds(0, 0, dp(size), dp(size));
        view.setImageDrawable(drawable); view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }
    private ImageView glyph(String name, int size, int color) {
        ImageView view = iconView(name, size, color);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        return view;
    }
    private Button pill(String label, boolean filled, Runnable click) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false); b.setTextSize(14);
        b.setTypeface(null, android.graphics.Typeface.BOLD);
        b.setTextColor(filled ? Color.WHITE : TEAL);
        b.setBackground(rounds(filled ? TEAL : MINT, 14));
        b.setGravity(Gravity.CENTER); b.setIncludeFontPadding(false);
        b.setMinHeight(0); b.setMinimumHeight(0); b.setPadding(dp(10), 0, dp(10), 0);
        b.setOnClickListener(v -> click.run());
        return b;
    }
    private FrameLayout iconTextButton(String label, String glyphName, boolean filled, Runnable click) {
        int color = filled ? Color.WHITE : TEAL;
        FrameLayout button = new FrameLayout(this);
        button.setBackground(rounds(filled ? TEAL : MINT, 14));
        button.setFocusable(true); button.setClickable(true); button.setOnClickListener(v -> click.run());
        button.setContentDescription(label);
        TextView title = text(label, 14, color);
        title.setTypeface(null, android.graphics.Typeface.BOLD); title.setGravity(Gravity.CENTER);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        button.addView(title, new FrameLayout.LayoutParams(-1, -1));
        ImageView image = iconView(glyphName, 16, color);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.START | Gravity.CENTER_VERTICAL);
        iconParams.setMargins(dp(16), 0, 0, 0);
        button.addView(image, iconParams);
        return button;
    }
    private FrameLayout pillWith(String label, String glyphName, boolean filled, Runnable click) {
        return iconTextButton(label, glyphName, filled, click);
    }
    private Button quiet(String label, Runnable click) {
        Button b = pill(label, false, click);
        b.setTextColor(INK); b.setBackground(rounds(GRAY, 14));
        return b;
    }
    private FrameLayout quietWith(String label, String glyphName, Runnable click) {
        FrameLayout button = new FrameLayout(this);
        button.setBackground(rounds(GRAY, 14)); button.setFocusable(true); button.setClickable(true);
        button.setOnClickListener(v -> click.run()); button.setContentDescription(label);
        TextView title = text(label, 14, INK); title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER); title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        button.addView(title, new FrameLayout.LayoutParams(-1, -1));
        ImageView image = iconView(glyphName, 16, MUTED);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.START | Gravity.CENTER_VERTICAL);
        iconParams.setMargins(dp(16), 0, 0, 0); button.addView(image, iconParams);
        return button;
    }
    private Button tiny(String label, boolean danger, Runnable click) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false); b.setTextSize(13);
        b.setTypeface(null, android.graphics.Typeface.BOLD);
        b.setTextColor(danger ? DANGER : TEAL);
        b.setBackground(rounds(danger ? 0xFFFBEFEE : MINT, 12));
        b.setGravity(Gravity.CENTER); b.setIncludeFontPadding(false);
        b.setMinHeight(0); b.setMinimumHeight(0); b.setPadding(dp(10), 0, dp(10), 0);
        b.setOnClickListener(v -> click.run());
        return b;
    }
    private FrameLayout glyphButton(String name, String description, Runnable click) { return glyphButton(name, description, click, MUTED); }
    private FrameLayout glyphButton(String name, String description, Runnable click, int color) {
        FrameLayout button = new FrameLayout(this);
        button.setBackground(rounds(0x00FFFFFF, 20)); button.setContentDescription(description);
        button.setFocusable(true); button.setClickable(true); button.setOnClickListener(v -> click.run());
        ImageView image = iconView(name, 16, color);
        button.addView(image, new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        return button;
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout card(String title, String glyphName) {
        LinearLayout c = column();
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setBackground(rounds(CARD, 18));
        c.setElevation(dp(1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, 0, 0, dp(10));
        page.addView(c, params);
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text(title, 16, INK);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        icon(label, glyphName, TEAL, 22);
        heading.addView(label);
        c.addView(heading);
        return c;
    }
    private String playModeLabel() {
        switch (playMode) {
            case "loop-all": return "列表循环";
            case "loop-one": return "单曲循环";
            case "shuffle": return "随机播放";
            default: return "顺序播放";
        }
    }
    private FrameLayout countButton(String iconName, String label, Runnable click) {
        FrameLayout button = new FrameLayout(this);
        button.setBackground(rounds(GRAY, 14)); button.setFocusable(true); button.setClickable(true);
        button.setOnClickListener(v -> click.run());
        TextView count = text("0", 16, INK); count.setTypeface(null, android.graphics.Typeface.BOLD);
        count.setGravity(Gravity.CENTER); count.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        button.addView(count, new FrameLayout.LayoutParams(-1, -1)); button.setTag(count);
        ImageView image = iconView(iconName, 16, MUTED);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.START | Gravity.CENTER_VERTICAL);
        iconParams.setMargins(dp(16), 0, 0, 0); button.addView(image, iconParams);
        button.setContentDescription(label);
        return button;
    }
    private void setCount(FrameLayout button, int count, String label) {
        ((TextView) button.getTag()).setText(String.valueOf(count));
        button.setContentDescription(label + "，共 " + count + " 首");
    }
    private Button modeButton() {
        modeCycleButton = pill("播放模式  ·  " + playModeLabel(), false, () -> {
            String[] modes = {"sequence", "loop-all", "loop-one", "shuffle"};
            int index = 0;
            for (int i = 0; i < modes.length; i++) if (modes[i].equals(playMode)) index = i;
            selectPlayMode(modes[(index + 1) % modes.length]);
        });
        return modeCycleButton;
    }
    private void updateModeButtons() {
        if (modeCycleButton != null) modeCycleButton.setText("播放模式  ·  " + playModeLabel());
    }
    private void selectPlayMode(String mode) {
        playMode = mode;
        if (!selectedProfile.isEmpty()) prefs.edit().putString("play-mode:" + selectedProfile, mode).apply();
        updateModeButtons();
        JSONObject selected = findProfile(profileData, selectedProfile);
        if (selected != null && !cleanText(selected.optString("currentSong", "")).isEmpty()) {
            try { playbackOn(selectedProfile, "/playlist/mode", new JSONObject().put("mode", mode)); }
            catch (Exception ignored) { }
        }
    }
    private void pair(LinearLayout target, View first, View second) {
        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, dp(46), 1);
        a.setMargins(0, dp(4), dp(5), dp(4));
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, dp(46), 1);
        b.setMargins(dp(5), dp(4), 0, dp(4));
        row.addView(first, a); row.addView(second, b); target.addView(row);
    }
    private void render() {
        modeCycleButton = null;
        ScrollView scroll = new ScrollView(this);
        page = column();
        page.setPadding(dp(16), dp(10), dp(16), dp(36));
        page.setBackgroundColor(BG);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets b = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(b.left, b.top, b.right, b.bottom);
            } else v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll.addView(page);
        setContentView(scroll);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBaselineAligned(false);
        header.setPadding(dp(2), dp(2), 0, dp(4));
        TextView entryName = text(prefs.getBoolean("use_public", false) ? "公网" : "局域网", 13, MUTED);
        entryName.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        String hostName = hostOf(base);
        entryName.setText(entryName.getText() + (hostName.isEmpty() ? "" : "  ·  " + hostName));
        entryName.setSingleLine(true);
        entryName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        header.addView(entryName, new LinearLayout.LayoutParams(0, dp(44), 1));
        header.addView(glyphButton("login", "登录当前服务", this::login));
        header.addView(glyphButton("refresh", "刷新设备", this::refresh));
        header.addView(glyphButton("settings", "入口设置", this::settings));
        page.addView(header);
        status = text("正在准备…", 12, MUTED);
        status.setPadding(dp(4), 0, 0, dp(6));
        page.addView(status);

        LinearLayout speaker = card("音箱", "speaker");
        profileChoices = column();
        speaker.setPadding(dp(14), dp(10), dp(14), dp(10));
        profileChoices.setPadding(0, dp(4), 0, 0);
        speaker.addView(profileChoices);
        profileChoices.addView(hint("刷新设备后可选择音箱"));

        LinearLayout music = card("音乐播放器", "music");
        LinearLayout playingRow = new LinearLayout(this);
        playingRow.setGravity(Gravity.CENTER_VERTICAL);
        playingRow.setBackground(rounds(MINT_SOFT, 14));
        playingRow.setPadding(dp(10), dp(2), dp(4), dp(2));
        ImageView playingIcon = glyph("music", 18, TEAL);
        LinearLayout.LayoutParams playingIconParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        playingIconParams.setMargins(0, 0, dp(6), 0);
        playingRow.addView(playingIcon, playingIconParams);
        nowPlaying = text("暂无播放", 14, MUTED);
        nowPlaying.setSingleLine(true);
        nowPlaying.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        nowPlaying.setMarqueeRepeatLimit(-1);
        nowPlaying.setSelected(false);
        nowPlaying.setTypeface(null, android.graphics.Typeface.BOLD);
        nowPlaying.setGravity(Gravity.CENTER_VERTICAL);
        nowPlaying.setPadding(0, dp(10), dp(4), dp(10));
        playingRow.addView(nowPlaying, new LinearLayout.LayoutParams(0, dp(48), 1));
        stopButton = glyphButton("stop", "停止播放", () -> playback("/stop", new JSONObject()), DANGER);
        stopButton.setBackground(rounds(0xFFFBEFEE, 12));
        stopButton.setVisibility(View.GONE);
        playingRow.addView(stopButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout.LayoutParams nowParams = new LinearLayout.LayoutParams(-1, -2);
        nowParams.setMargins(0, dp(4), 0, dp(2));
        music.addView(playingRow, nowParams);

        Button mode = modeButton();
        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(-1, dp(44));
        modeParams.setMargins(0, dp(4), 0, dp(2));
        music.addView(mode, modeParams);

        playlistButton = countButton("list", "播放列表", () -> showMusicLibrary(selected(), "playlist"));
        favoritesButton = countButton("heart", "我喜欢", () -> showMusicLibrary(selected(), "favorites"));
        setCount(playlistButton, playlistData.length(), "播放列表");
        setCount(favoritesButton, favoriteData.length(), "我喜欢");
        pair(music, playlistButton, favoritesButton);

        LinearLayout volumeRow = new LinearLayout(this);
        volumeRow.setGravity(Gravity.CENTER_VERTICAL);
        volumeRow.setPadding(0, dp(6), 0, dp(2));
        TextView volumeTitle = text("音量", 14, MUTED);
        volumeRow.addView(volumeTitle, new LinearLayout.LayoutParams(dp(36), -2));
        volumeRow.addView(glyph("volume", 18, MUTED), new LinearLayout.LayoutParams(dp(22), dp(22)));
        volume = new SeekBar(this);
        volume.setMax(100);
        volume.setEnabled(false);
        volume.setProgressTintList(android.content.res.ColorStateList.valueOf(TEAL));
        volume.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(LINE));
        volume.setThumbTintList(android.content.res.ColorStateList.valueOf(TEAL));
        LinearLayout.LayoutParams volumeParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        volumeParams.setMargins(dp(4), 0, dp(4), 0);
        volumeRow.addView(volume, volumeParams);
        volumeLabel = text("—", 13, MUTED);
        volumeLabel.setGravity(Gravity.END);
        volumeRow.addView(volumeLabel, new LinearLayout.LayoutParams(dp(46), -2));
        music.addView(volumeRow);
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) { if (fromUser) volumeLabel.setText(value + "%"); }
            public void onStartTrackingTouch(SeekBar bar) { }
            public void onStopTrackingTouch(SeekBar bar) {
                String id = selected();
                if (id.isEmpty()) return;
                try {
                    volumeLabel.setText(bar.getProgress() + "% · 正在应用");
                    playbackOn(id, "/volume", new JSONObject().put("volume", bar.getProgress()));
                } catch (Exception ignored) { }
            }
        });

        EditText query = new EditText(this);
        query.setSingleLine(true);
        query.setTextSize(15);
        query.setHint("输入歌曲、歌手或专辑");
        query.setHintTextColor(0xFF9AA7A4);
        query.setTextColor(INK);
        query.setPadding(dp(14), 0, dp(14), 0);
        query.setBackground(rounds(0xFFFAFBFB, 14, 1, LINE));
        icon(query, "search", MUTED, 18);
        LinearLayout.LayoutParams queryParams = new LinearLayout.LayoutParams(-1, dp(48));
        queryParams.setMargins(0, dp(10), 0, dp(6));
        music.addView(query, queryParams);

        FrameLayout searchButton = pillWith("搜索歌曲", "search", true, () -> search(query.getText().toString()));
        LinearLayout.LayoutParams searchButtonParams = new LinearLayout.LayoutParams(-1, dp(44));
        searchButtonParams.setMargins(0, dp(2), 0, dp(2));
        music.addView(searchButton, searchButtonParams);

        LinearLayout devices = card("灯光开关", "light");
        controls = column();
        controls.setPadding(0, dp(4), 0, 0);
        devices.addView(controls);
        controls.addView(hint("正在读取已配置的灯光控制…"));

        LinearLayout passCard = card("进京证", "car");
        Button check = pill("查询最新状态", false, this::queryPass);
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(-1, dp(46));
        checkParams.setMargins(0, dp(8), 0, dp(10));
        passCard.addView(check, checkParams);
        pass = text("仅查询状态，不会提交办理申请。", 13, MUTED);
        pass.setBackground(rounds(MINT_SOFT, 14));
        pass.setPadding(dp(14), dp(12), dp(14), dp(12));
        passCard.addView(pass);
    }
    private TextView hint(String value) {
        TextView t = text(value, 13, MUTED);
        t.setPadding(0, dp(8), 0, 0);
        return t;
    }
    private String hostOf(String value) {
        try { URI uri = URI.create(value); return uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : ""); }
        catch (Exception e) { return ""; }
    }
    private void settings() {
        LinearLayout form = column();
        form.setPadding(dp(20), dp(4), dp(20), 0);
        EditText lan = new EditText(this);
        lan.setSingleLine(); lan.setTextSize(15); lan.setHint("局域网 HTTPS 服务地址（含路径）"); lan.setText(prefs.getString("lan", ""));
        form.addView(lan);
        EditText pub = new EditText(this);
        pub.setSingleLine(); pub.setTextSize(15); pub.setHint("公网 HTTPS 服务地址（含路径）"); pub.setText(prefs.getString("public_url", ""));
        form.addView(pub);
        Switch select = new Switch(this);
        select.setText("使用公网入口"); select.setChecked(prefs.getBoolean("use_public", false));
        form.addView(select);
        TextView help = hint("填写 DSH 服务根地址，例如 https://域名/dsh/。两处可分别登录；必须使用受信任的 HTTPS 证书，不要在地址中填写凭据。");
        help.setPadding(0, dp(10), 0, 0);
        form.addView(help);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("快捷入口设置").setView(form).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w -> {
            try {
                String l = lan.getText().toString().trim(), p = pub.getText().toString().trim();
                l = l.isEmpty() ? "" : QuickEndpoint.normalize(l);
                p = p.isEmpty() ? "" : QuickEndpoint.normalize(p);
                String chosen = select.isChecked() ? p : l;
                if (chosen.isEmpty()) throw new IllegalArgumentException();
                prefs.edit().putString("lan", l).putString("public_url", p).putBoolean("use_public", select.isChecked()).apply();
                base = chosen; generation++; selectedProfile = ""; profileData = new JSONArray();
                dialog.dismiss(); render();
                page.post(() -> autoRefresh(true));
            } catch (Exception e) {
                Toast.makeText(this, "请输入有效的 HTTPS 服务地址，且不能包含凭据、参数或相对路径", Toast.LENGTH_LONG).show();
            }
        }));
        dialog.show();
    }
    @SuppressLint("SetJavaScriptEnabled") private void login() {
        if (base.isEmpty()) { settings(); return; }
        WebView web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        URI origin = URI.create(base);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                URI u; try { u = URI.create(request.getUrl().toString()); } catch (Exception e) { return true; }
                return !"https".equalsIgnoreCase(u.getScheme()) || !origin.getHost().equalsIgnoreCase(u.getHost()) || port(origin) != port(u);
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
                handler.cancel(); Toast.makeText(QuickActivity.this, "证书校验失败，已阻止连接", Toast.LENGTH_LONG).show();
            }
        });
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("登录当前服务").setView(web)
                .setPositiveButton("完成", (d, w) -> { CookieManager.getInstance().flush(); refresh(); })
                .setNegativeButton("关闭", null).create();
        dialog.setOnDismissListener(d -> { web.stopLoading(); web.destroy(); });
        dialog.show();
        web.loadUrl(base);
    }
    private static int port(URI u) { return u.getPort() == -1 ? 443 : u.getPort(); }
    interface Reply { void accept(JSONObject json) throws Exception; }
    private boolean isPlaybackCommand(String feature, String module, String path, String method) {
        if (!"xiaoai".equals(feature) || !"playback".equals(module) || !"POST".equals(method)) return false;
        return path.endsWith("/playlist/play") || path.endsWith("/search/play")
                || path.endsWith("/favorites/play") || path.endsWith("/playlist/mode")
                || path.endsWith("/stop") || path.endsWith("/volume");
    }
    private void request(String feature, String module, String path, String method, JSONObject body, Reply callback) {
        if (base.isEmpty()) { status.setText("请先设置入口并登录"); status.setTextColor(DANGER); return; }
        final String target = base; final int ticket = generation;
        status.setTextColor(MUTED);
        status.setText("请求中…");
        ExecutorService requestExecutor = isPlaybackCommand(feature, module, path, method) ? playbackExecutor : executor;
        requestExecutor.execute(() -> {
            String error = null; JSONObject response = null;
            try {
                JSONObject envelope = new JSONObject().put("path", path).put("method", method);
                if (module != null) envelope.put("module", module);
                if (body != null) envelope.put("body", body);
                URL url = new URL(target + "api/pocket-kit/" + feature);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(15000); c.setReadTimeout(30000);
                c.setRequestMethod("POST"); c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("X-Pocket-Kit", "1");
                URI origin = URI.create(target);
                c.setRequestProperty("Origin", new URI(origin.getScheme(), null, origin.getHost(), origin.getPort(), null, null, null).toString());
                String cookie = CookieManager.getInstance().getCookie(url.toString());
                if (cookie != null) c.setRequestProperty("Cookie", cookie);
                byte[] payload = envelope.toString().getBytes(StandardCharsets.UTF_8);
                try (java.io.OutputStream out = c.getOutputStream()) { out.write(payload); }
                int code = c.getResponseCode();
                InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                if (stream != null) try (InputStream in = stream) {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) != -1) {
                        if (bytes.size() + n > 1024 * 1024) throw new Exception("响应过大");
                        bytes.write(buf, 0, n);
                    }
                }
                c.disconnect();
                if (code < 200 || code >= 300) throw new Exception(code == 401 || code == 403 ? "登录失效或无权限，请先登录服务" : "服务请求失败（HTTP " + code + "）");
                response = new JSONObject(bytes.toString("UTF-8"));
            } catch (Exception e) {
                error = e.getMessage() == null ? "连接失败，请检查网络和证书" : e.getMessage();
            }
            JSONObject result = response; String failure = error;
            runOnUiThread(() -> {
                if (destroyed || ticket != generation) return;
                if (failure != null) {
                    status.setTextColor(DANGER); status.setText(failure);
                    if (musicDialog != null && musicDialog.isShowing())
                        Toast.makeText(this, failure, Toast.LENGTH_LONG).show();
                    return;
                }
                status.setTextColor(MUTED);
                try { callback.accept(result); if (status.getText().toString().equals("请求中…")) status.setText("操作完成"); }
                catch (Exception e) { status.setTextColor(DANGER); status.setText("服务响应格式不兼容"); }
            });
        });
    }
    private void autoRefresh(boolean force) {
        if (base.isEmpty()) { status.setTextColor(MUTED); status.setText("请先设置局域网或公网入口"); return; }
        if (!force && SystemClock.elapsedRealtime() - lastRefresh < 60_000L) return;
        refresh();
    }
    private String selected() { return selectedProfile; }
    private void refresh() {
        request("xiaoai", "playback", "/profiles", "GET", null, data -> {
            profileData = data.optJSONArray("profiles");
            if (profileData == null) profileData = new JSONArray();
            boolean found = false;
            for (int i = 0; i < profileData.length(); i++) if (selectedProfile.equals(profileData.getJSONObject(i).optString("id"))) found = true;
            if (!found) selectedProfile = profileData.length() > 0 ? profileData.getJSONObject(0).optString("id", "") : "";
            lastRefresh = SystemClock.elapsedRealtime();
            renderProfiles();
            loadControls();
            loadCollection();
            status.setText("已刷新 · " + new java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(new java.util.Date()));
        });
    }
    private void renderProfiles() {
        profileChoices.removeAllViews();
        if (profileData.length() == 0) {
            volume.setEnabled(false); volumeLabel.setText("—");
            nowPlaying.setText("未在播放"); nowPlaying.setSelected(false); nowPlaying.setTextColor(MUTED);
            stopButton.setVisibility(View.GONE);
            if (playlistButton != null) setCount(playlistButton, 0, "播放列表");
            if (favoritesButton != null) setCount(favoritesButton, 0, "我喜欢");
            profileChoices.addView(hint("未找到音箱，请确认服务端已启用小爱功能。"));
            return;
        }
        LinearLayout row = null;
        for (int i = 0; i < profileData.length(); i++) {
            JSONObject p = profileData.optJSONObject(i);
            if (p == null) continue;
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setPadding(0, dp(3), 0, dp(3));
                profileChoices.addView(row);
            }
            String id = p.optString("id"), name = p.optString("name", id);
            boolean checked = id.equals(selectedProfile);
            RadioButton choice = new RadioButton(this);
            choice.setText(name); choice.setTextSize(14); choice.setTextColor(checked ? INK : 0xFF5C6A67);
            choice.setTypeface(null, checked ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            choice.setGravity(Gravity.CENTER_VERTICAL);
            choice.setIncludeFontPadding(false);
            choice.setButtonTintList(android.content.res.ColorStateList.valueOf(checked ? TEAL : 0xFF9AA7A4));
            choice.setBackground(rounds(checked ? MINT : GRAY, 14));
            choice.setChecked(checked);
            choice.setPadding(dp(6), 0, dp(8), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1);
            lp.setMargins(0, dp(1), dp(6), dp(1));
            row.addView(choice, lp);
            choice.setOnClickListener(v -> {
                selectedProfile = id;
                if (musicDialog != null) musicDialog.dismiss();
                renderProfiles();
                loadCollection();
            });
            if (checked) {
                int value = p.optInt("volume", -1);
                volume.setEnabled(value >= 0);
                if (value >= 0) { volume.setProgress(Math.max(0, Math.min(100, value))); volumeLabel.setText(value + "%"); }
                else volumeLabel.setText("未知");
                String current = cleanText(p.optString("currentSong", ""));
                String serverMode = p.optString("playMode", "sequence");
                playMode = current.isEmpty() ? prefs.getString("play-mode:" + selectedProfile, serverMode) : serverMode;
                updateModeButtons();
                nowPlaying.setText(current.isEmpty() ? "未在播放" : songTitle(current));
                nowPlaying.setTextColor(current.isEmpty() ? MUTED : INK);
                nowPlaying.setSelected(!current.isEmpty());
                stopButton.setVisibility(current.isEmpty() ? View.GONE : View.VISIBLE);
            }
        }
    }
    private void loadCollection() {
        String id = selected();
        if (id.isEmpty()) return;
        request("xiaoai", "playback", profilePath(id, "/collection"), "GET", null, data -> {
            if (!id.equals(selected())) return;
            playlistData = data.optJSONArray("playlist");
            favoriteData = data.optJSONArray("favorites");
            if (playlistData == null) playlistData = new JSONArray();
            if (favoriteData == null) favoriteData = new JSONArray();
            if (playlistButton != null) setCount(playlistButton, playlistData.length(), "播放列表");
            if (favoritesButton != null) setCount(favoritesButton, favoriteData.length(), "我喜欢");
        });
    }
    private void loadControls() {
        request("xiaoai", "assistant", "/state", "GET", null, data -> {
            controls.removeAllViews();
            JSONObject light = null;
            JSONArray list = data.optJSONArray("controls");
            if (list != null) for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item != null && item.optString("category").equals("light")) { light = item; break; }
            }
            if (light == null) {
                controls.addView(hint("尚未配置灯光快捷控制。"));
                return;
            }
            TextView name = text(light.optString("name", "灯光"), 15, MUTED);
            name.setPadding(0, dp(8), 0, dp(2));
            controls.addView(name);
            String id = light.optString("id");
            if (light.optString("mode").equals("pair")) {
                FrameLayout on = pillWith(light.optString("onLabel", "开灯"), "power", true, () -> execute(id, "on"));
                FrameLayout off = quietWith(light.optString("offLabel", "关灯"), "power", () -> execute(id, "off"));
                pair(controls, on, off);
            } else {
                FrameLayout run = pillWith("执行灯光控制", "power", true, () -> execute(id, "action"));
                LinearLayout.LayoutParams runParams = new LinearLayout.LayoutParams(-1, dp(46));
                runParams.setMargins(0, dp(4), 0, dp(4));
                controls.addView(run, runParams);
            }
        });
    }
    private void execute(String id, String operation) {
        String profile = selected();
        if (profile.isEmpty()) { status.setText("请先选择音箱"); return; }
        try {
            request("xiaoai", "assistant", "/execute", "POST",
                    new JSONObject().put("profileId", profile).put("controlId", id).put("operation", operation), d -> { });
        } catch (Exception ignored) { }
    }
    private void playback(String endpoint, JSONObject body) { playbackOn(selected(), endpoint, body); }
    private void playbackOn(String id, String endpoint, JSONObject body) {
        if (id.isEmpty()) { status.setText("请先选择音箱"); return; }
        request("xiaoai", "playback", "/profiles/" + android.net.Uri.encode(id) + endpoint, "POST", body, d -> {
            if (endpoint.equals("/volume")) {
                for (int i = 0; i < profileData.length(); i++) {
                    JSONObject p = profileData.optJSONObject(i);
                    if (p != null && id.equals(p.optString("id"))) p.put("volume", body.optInt("volume"));
                }
                if (id.equals(selected())) volumeLabel.setText(body.optInt("volume") + "%");
            } else if (endpoint.equals("/playlist/play") || endpoint.equals("/search/play")
                    || endpoint.equals("/favorites/play") || endpoint.equals("/stop")) {
                refresh();
            }
        });
    }
    private String profilePath(String id, String suffix) { return "/profiles/" + android.net.Uri.encode(id) + suffix; }
    private String profileName(String id) {
        for (int i = 0; i < profileData.length(); i++) {
            JSONObject p = profileData.optJSONObject(i);
            if (p != null && id.equals(p.optString("id"))) return p.optString("name", "所选音箱");
        }
        return "所选音箱";
    }
    private void search(String query) {
        String id = selected();
        String q = query.trim();
        lastSearchKeyword = q;
        if (id.isEmpty() || q.isEmpty()) { status.setText("请选择音箱并输入关键词"); return; }
        if (q.length() > 100) { status.setText("关键词最多 100 字"); return; }
        request("xiaoai", "playback", profilePath(id, "/search?q=" + android.net.Uri.encode(q)), "GET", null, data -> {
            if (!id.equals(selected())) return;
            JSONArray items = data.optJSONArray("items");
            renderMusicDialog(id, "search", q, items == null ? new JSONArray() : items);
        });
    }
    private void showMusicLibrary(String id, String tab) {
        if (id.isEmpty()) { status.setText("请先选择音箱"); return; }
        request("xiaoai", "playback", profilePath(id, "/collection"), "GET", null, data -> {
            if (!id.equals(selected())) return;
            playlistData = data.optJSONArray("playlist");
            favoriteData = data.optJSONArray("favorites");
            if (playlistData == null) playlistData = new JSONArray();
            if (favoriteData == null) favoriteData = new JSONArray();
            if (playlistButton != null) setCount(playlistButton, playlistData.length(), "播放列表");
            if (favoritesButton != null) setCount(favoritesButton, favoriteData.length(), "我喜欢");
            JSONArray songs = tab.equals("favorites") ? favoriteData : playlistData;
            renderMusicDialog(id, tab, "", songs);
        });
    }
    private void renderMusicDialog(String id, String tab, String keyword, JSONArray songs) {
        if (!id.equals(selected())) return;
        musicDialogTab = tab;
        LinearLayout list = column();
        int count = songs.length();
        for (int i = 0; i < count; i++) {
            JSONObject song = songs.optJSONObject(i);
            if (song == null) continue;
            list.addView(trackRow(id, i + 1, song, tab, keyword));
            list.addView(divider());
        }
        if (count == 0) {
            String title = tab.equals("search") ? "没有找到歌曲" : tab.equals("favorites") ? "喜欢列表还是空的" : "播放列表为空";
            String message = tab.equals("search") ? "换个关键词再试试。" : "搜索歌曲后可以加入这里，或标记喜欢。";
            list.addView(emptyState(title, message));
        }
        LinearLayout footer = new LinearLayout(this);
        pair(footer,
                pill("播放列表", tab.equals("playlist"), () -> showMusicLibrary(id, "playlist")),
                pill("我喜欢", tab.equals("favorites"), () -> showMusicLibrary(id, "favorites")));
        Button close = tiny("关闭", false, () -> { if (musicDialog != null) musicDialog.dismiss(); });
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, dp(44));
        closeParams.setMargins(0, dp(6), 0, 0);
        footer.addView(close, closeParams);
        String title = tab.equals("search") ? "搜索结果" : tab.equals("favorites") ? "我喜欢" : "播放列表";
        String sub = tab.equals("search") ? "“" + keyword + "” · " + profileName(id) + " · " + count + " 首" : profileName(id) + " · 共 " + count + " 首";
        if (musicDialog != null && musicDialog.isShowing()) musicDialog.dismiss();
        musicDialog = sheet(title, sub, list, count, footer);
    }
    private LinearLayout trackRow(String id, int index, JSONObject song, String source, String keyword) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(5), 0, dp(5));
        TextView number = text(String.valueOf(index), 12, TEAL);
        number.setTypeface(null, android.graphics.Typeface.BOLD);
        number.setGravity(Gravity.CENTER);
        GradientDrawable badge = new GradientDrawable(); badge.setShape(GradientDrawable.OVAL); badge.setColor(MINT_SOFT);
        number.setBackground(badge);
        LinearLayout.LayoutParams numberParams = new LinearLayout.LayoutParams(dp(26), dp(26));
        numberParams.setMargins(0, 0, dp(8), 0);
        row.addView(number, numberParams);
        LinearLayout detail = column();
        String path = song.optString("path");
        String title = songTitle(song);
        TextView name = text(title, 14, INK); name.setSingleLine(true); name.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE); name.setMarqueeRepeatLimit(-1); name.setSelected(true);
        detail.addView(name);
        String artist = song.optString("artist", "");
        if (!artist.isEmpty()) { TextView sub = text(artist, 12, MUTED); sub.setSingleLine(true); sub.setEllipsize(android.text.TextUtils.TruncateAt.END); sub.setPadding(0, dp(2), 0, 0); detail.addView(sub); }
        row.addView(detail, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.addView(glyphButton("play", "播放 " + title, () -> playSong(id, source, path, keyword)), new LinearLayout.LayoutParams(dp(42), dp(44)));
        if (!source.equals("playlist")) actions.addView(glyphButton("add", "加入播放列表", () -> addToPlaylist(id, song, source)), new LinearLayout.LayoutParams(dp(42), dp(44)));
        boolean liked = isFavorite(path);
        actions.addView(glyphButton("heart", liked ? "取消喜欢" : "添加喜欢", () -> setFavorite(id, song, !liked, source), liked ? TEAL : MUTED), new LinearLayout.LayoutParams(dp(42), dp(44)));
        if (source.equals("playlist")) actions.addView(glyphButton("trash", "从播放列表移除", () -> removeFromPlaylist(id, path)), new LinearLayout.LayoutParams(dp(42), dp(44)));
        row.addView(actions);
        return row;
    }
    private boolean isFavorite(String path) {
        for (int i = 0; i < favoriteData.length(); i++) { JSONObject song = favoriteData.optJSONObject(i); if (song != null && path.equals(song.optString("path"))) return true; }
        return false;
    }
    private void playSong(String id, String source, String path, String keyword) {
        try {
            JSONObject body = new JSONObject().put("path", path).put("mode", playMode);
            String endpoint;
            if (source.equals("search")) { endpoint = "/search/play"; body.put("keyword", keyword); }
            else endpoint = source.equals("favorites") ? "/favorites/play" : "/playlist/play";
            playbackOn(id, endpoint, body);
        } catch (Exception ignored) { }
    }
    private void addToPlaylist(String id, JSONObject song, String source) {
        try {
            JSONObject body = new JSONObject().put("songs", new JSONArray().put(song)).put("mode", "append");
            request("xiaoai", "playback", profilePath(id, "/playlist"), "POST", body, data -> {
                Toast.makeText(this, "已加入播放列表", Toast.LENGTH_SHORT).show();
                if (source.equals("search")) { loadCollection(); search(lastSearchKeyword); }
                else showMusicLibrary(id, source);
            });
        } catch (Exception ignored) { }
    }
    private void setFavorite(String id, JSONObject song, boolean favorite, String source) {
        try {
            request("xiaoai", "playback", profilePath(id, "/favorites"), "PUT",
                    new JSONObject().put("song", song).put("favorite", favorite), data -> {
                        Toast.makeText(this, favorite ? "已加入我喜欢" : "已从我喜欢移除", Toast.LENGTH_SHORT).show();
                        if (source.equals("search")) { loadCollection(); search(lastSearchKeyword); }
                        else showMusicLibrary(id, source);
                    });
        } catch (Exception ignored) { }
    }
    private void removeFromPlaylist(String id, String path) {
        new AlertDialog.Builder(this).setMessage("从播放列表移除此歌曲？不会删除音乐文件。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", (dialog, which) -> request("xiaoai", "playback",
                        profilePath(id, "/playlist?path=" + android.net.Uri.encode(path)), "DELETE", null,
                        data -> showMusicLibrary(id, "playlist"))).show();
    }
    private JSONObject findProfile(JSONArray profiles, String id) {
        if (profiles == null) return null;
        for (int i = 0; i < profiles.length(); i++) { JSONObject p = profiles.optJSONObject(i); if (p != null && id.equals(p.optString("id"))) return p; }
        return null;
    }
    private String cleanText(String value) {
        if (value == null) return "";
        String clean = value.trim();
        return clean.isEmpty() || clean.equalsIgnoreCase("null") || clean.equalsIgnoreCase("undefined") ? "" : clean;
    }
    private String songTitle(String current) {
        String value = cleanText(current).replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);
        value = android.net.Uri.decode(value);
        value = stripExtension(value).trim();
        return value.isEmpty() ? "未命名歌曲" : value;
    }
    private String songTitle(JSONObject song) {
        for (String key : new String[]{"title", "name", "filename", "fileName"}) {
            String value = cleanText(song.optString(key, ""));
            if (!value.isEmpty()) return songTitle(value);
        }
        String path = cleanText(song.optString("path", ""));
        return path.isEmpty() ? "未命名歌曲" : songTitle(path);
    }
    private String stripExtension(String name) { return name.replaceAll("\\.[^.]+$", ""); }
    private View divider() {
        View line = new View(this);
        line.setBackgroundColor(0xFFEDF1EF);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.setMargins(dp(36), 0, 0, 0);
        line.setLayoutParams(params);
        return line;
    }
    private LinearLayout emptyState(String title, String message) {
        LinearLayout empty = column();
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(26), 0, dp(26));
        ImageView icon = glyph("music", 34, 0xFFB9C6C2);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(34), dp(34));
        iconParams.setMargins(0, 0, 0, dp(12));
        empty.addView(icon, iconParams);
        TextView heading = text(title, 16, INK);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setGravity(Gravity.CENTER);
        empty.addView(heading);
        TextView body = text(message, 13, MUTED);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, dp(6), 0, 0);
        empty.addView(body);
        return empty;
    }
    private AlertDialog sheet(String title, String subtitle, LinearLayout body, int count, View footer) {
        LinearLayout panel = column();
        panel.setPadding(dp(20), dp(18), dp(20), dp(18));
        panel.setBackground(rounds(CARD, 24));
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = text(title, 20, INK);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        head.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        FrameLayout close = glyphButton("close", "关闭", () -> { if (musicDialog != null) musicDialog.dismiss(); });
        close.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        head.addView(close);
        panel.addView(head);
        TextView sub = text(subtitle, 12, MUTED);
        sub.setPadding(0, dp(4), 0, dp(2));
        panel.addView(sub);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setFillViewport(false);
        scroll.addView(body);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int maxBody = Math.min((int) (screenHeight * 0.62f), dp(520));
        int bodyHeight = count <= 0 ? -2 : Math.min(maxBody, Math.max(dp(132), count * dp(76) + dp(6)));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, bodyHeight));
        if (footer != null) {
            LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(-1, -2);
            footerParams.setMargins(0, dp(12), 0, 0);
            panel.addView(footer, footerParams);
        }
        AlertDialog dialog = new AlertDialog.Builder(this).setView(panel).create();
        dialog.setOnShowListener(v -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
                dialog.getWindow().setLayout(getResources().getDisplayMetrics().widthPixels - dp(32), -2);
            }
        });
        dialog.show();
        return dialog;
    }
    private void queryPass() {
        request("beijing-pass", null, "/prepare", "GET", null, data -> {
            JSONArray vehicles = data.optJSONArray("vehicles");
            StringBuilder out = new StringBuilder();
            if (vehicles != null) for (int i = 0; i < vehicles.length(); i++) {
                JSONObject v = vehicles.getJSONObject(i);
                out.append(v.optString("plate", "车辆")).append('\n');
                JSONArray records = v.optJSONArray("records");
                if (records == null || records.length() == 0) out.append("未查询到证件记录\n");
                else for (int j = 0; j < records.length(); j++) {
                    JSONObject r = records.getJSONObject(j);
                    out.append(r.optString("status", "状态未知")).append("  ")
                            .append(r.optString("validFrom", "—")).append(" 至 ")
                            .append(r.optString("validTo", "—")).append('\n');
                }
            }
            pass.setText(out.length() == 0 ? "未查询到车辆。" : out.toString().trim());
        });
    }
    @Override protected void onDestroy() { destroyed = true; generation++; executor.shutdownNow(); playbackExecutor.shutdownNow(); super.onDestroy(); }
}
