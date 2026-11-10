package top.fnaio.direct;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Native-only file metadata view. It does not navigate the service or expose a JS bridge. */
public final class DownloadManagerDialog {
    private final Activity activity;
    private final DownloadHistory history;
    private final Consumer<String> cancel;
    private final BiConsumer<String, Boolean> open;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Row> rows = new LinkedHashMap<>();
    private AlertDialog dialog, confirmation;
    private TextView summary, empty;
    private LinearLayout list;
    private boolean resumed = true;
    private final Runnable tick = () -> { refresh(); schedule(); };
    private final SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);

    private static final class Row {
        TextView name, meta, state, amount, note;
        ProgressBar progress;
        Button open, share, cancel, remove;
    }
    public DownloadManagerDialog(Activity activity, DownloadHistory history, Consumer<String> cancel, BiConsumer<String, Boolean> open) {
        this.activity = activity; this.history = history; this.cancel = cancel; this.open = open;
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private TextView label(int size, int color) { TextView view = new TextView(activity); view.setTextSize(size); view.setTextColor(color); return view; }
    private GradientDrawable background(int color) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(14)); return drawable;
    }
    public void show() {
        if (dialog != null && dialog.isShowing()) return;
        rows.clear();
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(12), dp(4), dp(12), 0);
        summary = label(12, Color.rgb(101, 113, 134)); summary.setPadding(dp(4), 0, dp(4), dp(10)); body.addView(summary);
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(false);
        list = new LinearLayout(activity); list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list); body.addView(scroll, new LinearLayout.LayoutParams(-1, Math.min(dp(460), Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.58f))));
        empty = label(14, Color.rgb(114, 125, 145)); empty.setGravity(Gravity.CENTER); empty.setPadding(dp(20), dp(48), dp(20), dp(48));
        empty.setText("暂无下载记录\n升级前下载的文件请在系统文件管理器中查看");
        dialog = new AlertDialog.Builder(activity).setTitle("下载管理").setView(body).setPositiveButton("关闭", null).setNeutralButton("清空已结束", null).create();
        dialog.setOnDismissListener(d -> handler.removeCallbacks(tick));
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            confirmation = new AlertDialog.Builder(activity).setMessage("仅清除已结束的记录，不会删除实际文件。是否继续？")
                .setNegativeButton("取消", null).setPositiveButton("清除记录", (d, w) -> { history.clearFinished(); refresh(); }).create(); confirmation.show();
        });
        refresh(); schedule();
    }
    private Button action(String text, Runnable listener) {
        Button button = new Button(activity); button.setText(text); button.setTextSize(12); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setOnClickListener(v -> listener.run()); return button;
    }
    private Row addRow(DownloadRecord record) {
        Row row = new Row(); String id = record.id;
        LinearLayout card = new LinearLayout(activity); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(14), dp(12), dp(14), dp(8));
        card.setBackground(background(Color.rgb(245, 247, 252)));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2); cardParams.bottomMargin = dp(10); list.addView(card, cardParams);
        row.name = label(15, Color.rgb(31, 43, 65)); row.name.setTypeface(null, Typeface.BOLD); row.name.setMaxLines(2); row.name.setEllipsize(TextUtils.TruncateAt.END); card.addView(row.name);
        row.meta = label(11, Color.rgb(116, 126, 145)); row.meta.setPadding(0, dp(5), 0, dp(8)); card.addView(row.meta);
        row.state = label(13, Color.rgb(65, 100, 172)); card.addView(row.state);
        row.progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal); row.progress.setMax(1000);
        row.progress.setProgressTintList(ColorStateList.valueOf(Color.rgb(77, 111, 186)));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(4)); progressParams.topMargin = dp(8); progressParams.bottomMargin = dp(6); card.addView(row.progress, progressParams);
        row.amount = label(11, Color.rgb(103, 115, 135)); card.addView(row.amount);
        row.note = label(11, Color.rgb(151, 93, 73)); row.note.setPadding(0, dp(4), 0, 0); row.note.setMaxLines(3); card.addView(row.note);
        LinearLayout buttons = new LinearLayout(activity); buttons.setGravity(Gravity.END);
        row.open = action("打开", () -> open.accept(id, false)); row.share = action("分享", () -> open.accept(id, true));
        row.cancel = action("取消下载", () -> cancel.accept(id));
        row.remove = action("移除记录", () -> {
            confirmation = new AlertDialog.Builder(activity).setMessage("只移除这条记录，实际文件不会删除。")
                .setNegativeButton("取消", null).setPositiveButton("移除", (d, w) -> { history.remove(id); refresh(); }).create(); confirmation.show();
        });
        for (Button button : new Button[]{row.open, row.share, row.cancel, row.remove}) buttons.addView(button, new LinearLayout.LayoutParams(0, dp(44), 1));
        card.addView(buttons); return row;
    }
    private void refresh() {
        if (dialog == null || !dialog.isShowing()) return;
        List<DownloadRecord> records = history.snapshot(); List<String> ids = new ArrayList<>();
        int active = 0, completed = 0;
        for (DownloadRecord record : records) { ids.add(record.id); if (record.active()) active++; if (record.state == DownloadRecord.State.COMPLETED) completed++; }
        summary.setText(activity.getString(R.string.download_summary, active, completed, records.size()));
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(active < records.size());
        if (!ids.equals(new ArrayList<>(rows.keySet()))) {
            list.removeAllViews(); rows.clear();
            for (DownloadRecord record : records) rows.put(record.id, addRow(record));
        }
        if (records.isEmpty()) { if (empty.getParent() == null) list.addView(empty); return; }
        if (empty.getParent() != null) list.removeView(empty);
        for (DownloadRecord record : records) {
            Row row = rows.get(record.id);
            row.name.setText(record.name); row.meta.setText(time.format(new Date(record.createdAt)));
            int progress = record.progressPermille();
            String phase = record.statusText();
            if (record.active() && record.state != DownloadRecord.State.WAITING && progress >= 0) phase += " · " + progress / 10 + "%";
            row.state.setText(phase);
            row.state.setTextColor(record.state == DownloadRecord.State.COMPLETED ? Color.rgb(26, 132, 106)
                : record.state == DownloadRecord.State.FAILED ? Color.rgb(172, 75, 63) : Color.rgb(73, 103, 162));
            row.progress.setVisibility(record.active() && record.state != DownloadRecord.State.WAITING ? View.VISIBLE : View.GONE);
            row.progress.setIndeterminate(progress < 0); if (progress >= 0) row.progress.setProgress(progress);
            long done = record.state == DownloadRecord.State.SAVING ? record.copied : record.downloaded;
            row.amount.setText(record.state == DownloadRecord.State.COMPLETED ? DownloadRecord.formatBytes(record.total)
                : DownloadRecord.formatBytes(done) + " / " + DownloadRecord.formatBytes(record.total));
            row.note.setText(record.message); row.note.setVisibility(record.message.isEmpty() ? View.GONE : View.VISIBLE);
            row.open.setVisibility(record.canOpen() ? View.VISIBLE : View.GONE); row.share.setVisibility(record.canOpen() ? View.VISIBLE : View.GONE);
            row.cancel.setVisibility(record.active() ? View.VISIBLE : View.GONE); row.remove.setVisibility(record.active() ? View.GONE : View.VISIBLE);
        }
    }
    private void schedule() { handler.removeCallbacks(tick); if (resumed && dialog != null && dialog.isShowing()) handler.postDelayed(tick, 500); }
    public void resume() { resumed = true; refresh(); schedule(); }
    public void pause() { resumed = false; handler.removeCallbacks(tick); }
    public boolean dismiss() { if (dialog != null && dialog.isShowing()) { dialog.dismiss(); return true; } return false; }
    public void close() { pause(); if (confirmation != null) confirmation.dismiss(); dismiss(); }
}
