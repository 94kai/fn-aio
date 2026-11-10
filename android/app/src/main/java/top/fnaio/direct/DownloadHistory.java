package top.fnaio.direct;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded app-private metadata; the actual files remain at user-selected document URIs. */
public final class DownloadHistory {
    private static final int MAX_RECORDS = 100;
    private final Context context;
    private final SharedPreferences preferences;
    private final List<DownloadRecord> records = new ArrayList<>();
    private long lastPersist;
    public DownloadHistory(Context context) {
        this.context = context.getApplicationContext();
        preferences = context.getSharedPreferences("download_history", Context.MODE_PRIVATE);
        String saved = preferences.getString("records", "[]");
        if (saved != null && saved.length() <= 1024 * 1024) try {
            JSONArray array = new JSONArray(saved);
            for (int i = 0; i < Math.min(array.length(), MAX_RECORDS); i++) try {
                JSONObject object = array.getJSONObject(i);
                String id = object.getString("id");
                if (!id.matches("[a-f0-9-]{36}") || find(id) != null) continue;
                DownloadRecord record = new DownloadRecord(id, object.optString("name"), object.optString("mime"), object.optLong("total", -1), object.optLong("createdAt"));
                record.state = DownloadRecord.State.valueOf(object.getString("state"));
                record.updatedAt = Math.max(record.createdAt, object.optLong("updatedAt"));
                record.downloaded = Math.max(0, Math.min(DownloadPolicy.MAX_BYTES, object.optLong("downloaded")));
                record.copied = Math.max(0, Math.min(record.downloaded, object.optLong("copied")));
                record.message = object.optString("message");
                if (record.message.length() > 240) record.message = record.message.substring(0, 240);
                String uri = object.optString("destination");
                record.destination = DownloadRecord.validDestination(uri) ? uri : "";
                record.recoverAfterRestart(System.currentTimeMillis());
                records.add(record);
            } catch (Exception ignored) { /* Corrupt metadata never opens an arbitrary path. */ }
        } catch (Exception ignored) {}
        persist();
    }
    private DownloadRecord find(String id) { for (DownloadRecord record : records) if (record.id.equals(id)) return record; return null; }
    public synchronized String add(String name, String mime, long total) {
        DownloadRecord record = new DownloadRecord(UUID.randomUUID().toString(), name, mime, total, System.currentTimeMillis());
        records.add(0, record);
        while (records.size() > MAX_RECORDS) { DownloadRecord old = records.remove(records.size() - 1); releaseIfUnused(old.destination); }
        persist(); return record.id;
    }
    public synchronized void destination(String id, String uri, String name) {
        DownloadRecord record = find(id);
        if (record == null || !record.active() || !DownloadRecord.validDestination(uri)) return;
        record.destination = uri;
        if (name != null && !name.isEmpty()) record.name = DownloadPolicy.safeName(name);
        persist();
    }
    public synchronized void progress(String id, DownloadRecord.State phase, long bytes, long total, long copied) {
        DownloadRecord record = find(id);
        if (record == null) return;
        DownloadRecord.State previous = record.state;
        if (record.progress(phase, bytes, total, copied, System.currentTimeMillis())
                && (previous != phase || System.currentTimeMillis() - lastPersist >= 1000)) persist();
    }
    public synchronized void finish(String id, DownloadRecord.State state, String message, long size) {
        DownloadRecord record = find(id);
        if (record != null && record.finish(state, message, size, System.currentTimeMillis())) persist();
    }
    public synchronized DownloadRecord get(String id) { DownloadRecord record = find(id); return record == null ? null : record.copy(); }
    public synchronized List<DownloadRecord> snapshot() {
        List<DownloadRecord> copy = new ArrayList<>(); for (DownloadRecord record : records) copy.add(record.copy()); return copy;
    }
    public synchronized boolean remove(String id) {
        DownloadRecord record = find(id);
        if (record == null || record.active()) return false;
        records.remove(record); releaseIfUnused(record.destination); persist(); return true;
    }
    public synchronized void clearFinished() {
        List<String> uris = new ArrayList<>();
        records.removeIf(record -> { if (record.active()) return false; uris.add(record.destination); return true; });
        for (String uri : uris) releaseIfUnused(uri);
        persist();
    }
    private void releaseIfUnused(String uri) {
        if (!DownloadRecord.validDestination(uri)) return;
        for (DownloadRecord record : records) if (record.destination.equals(uri)) return;
        try { context.getContentResolver().releasePersistableUriPermission(Uri.parse(uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Exception ignored) {}
    }
    private synchronized void persist() {
        JSONArray array = new JSONArray();
        for (DownloadRecord record : records) try {
            JSONObject value = new JSONObject();
            value.put("id", record.id); value.put("name", record.name); value.put("mime", record.mime);
            value.put("destination", record.destination); value.put("createdAt", record.createdAt); value.put("updatedAt", record.updatedAt);
            value.put("total", record.total); value.put("downloaded", record.downloaded); value.put("copied", record.copied);
            value.put("state", record.state.name()); value.put("message", record.message); array.put(value);
        } catch (Exception ignored) {}
        preferences.edit().putString("records", array.toString()).apply(); lastPersist = System.currentTimeMillis();
    }
}
