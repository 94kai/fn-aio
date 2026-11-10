package top.fnaio.direct;

public final class DownloadRecordTest {
    private static int count;
    private static void check(boolean value) { count++; if (!value) throw new AssertionError("case " + count); }
    public static void main(String[] args) {
        DownloadRecord record = new DownloadRecord("id", "report.pdf", "application/pdf", 1000, 100);
        check(record.active()); check(record.state == DownloadRecord.State.WAITING); check(record.progressPermille() == 0);
        check(record.progress(DownloadRecord.State.DOWNLOADING, 250, 1000, 0, 200));
        check(record.progressPermille() == 250);
        record.progress(DownloadRecord.State.DOWNLOADING, 100, 1000, 0, 210);
        check(record.downloaded == 250);
        record.progress(DownloadRecord.State.SAVING, 1000, 1000, 100, 300);
        check(record.progressPermille() == 100); check(record.statusText().equals("保存到目标位置"));
        check(!record.progress(DownloadRecord.State.DOWNLOADING, 1, 1, 0, 310));
        record.destination = "content://documents/document/file";
        check(!record.canOpen());
        check(record.finish(DownloadRecord.State.COMPLETED, "", 1000, 400));
        check(record.canOpen()); check(record.progressPermille() == 1000); check(!record.active());
        check(!record.finish(DownloadRecord.State.FAILED, "late failure", 0, 500));
        check(!record.progress(DownloadRecord.State.DOWNLOADING, 5, 5, 0, 500));
        check(!record.recoverAfterRestart(600));
        DownloadRecord copy = record.copy(); copy.name = "changed"; copy.destination = "file:///secret";
        check(record.name.equals("report.pdf")); check(!copy.canOpen()); check(record.canOpen());
        DownloadRecord unknown = new DownloadRecord("unknown", "x", null, -1, 100);
        unknown.progress(DownloadRecord.State.DOWNLOADING, 123, -1, 0, 200);
        check(unknown.progressPermille() == -1); check(unknown.total == -1);
        check(unknown.recoverAfterRestart(300)); check(unknown.state == DownloadRecord.State.INTERRUPTED);
        check(!unknown.active()); check(!unknown.canOpen()); check(!unknown.progress(DownloadRecord.State.SAVING, 123, 123, 123, 400));
        DownloadRecord empty = new DownloadRecord("empty", "", "", 0, 0);
        check(empty.progressPermille() == 0); empty.finish(DownloadRecord.State.COMPLETED, "", 0, 1); check(empty.progressPermille() == 1000);
        DownloadRecord cancelled = new DownloadRecord("cancelled", "x", "", 10, 0);
        cancelled.finish(DownloadRecord.State.CANCELLED, "cancelled", 0, 1);
        check(!cancelled.finish(DownloadRecord.State.COMPLETED, "", 10, 2)); check(!cancelled.canOpen());
        check(DownloadRecord.validDestination("content://provider/tree/a%2Fb/document/c"));
        for (String uri : new String[]{"file:///etc/passwd", "https://nas.example/file", "javascript:alert(1)", "content://", "content://name:password@provider/path", ""}) check(!DownloadRecord.validDestination(uri));
        check(DownloadRecord.formatBytes(-1).equals("大小未知")); check(DownloadRecord.formatBytes(0).equals("0 B"));
        check(DownloadRecord.formatBytes(1024).equals("1.0 KB")); check(DownloadRecord.formatBytes(1024 * 1024).equals("1.0 MB"));
        DownloadRecord huge = new DownloadRecord("huge", "a", "", Long.MAX_VALUE, 0);
        huge.progress(DownloadRecord.State.DOWNLOADING, Long.MAX_VALUE, Long.MAX_VALUE, 0, 1);
        check(huge.total == DownloadPolicy.MAX_BYTES); check(huge.progressPermille() == 1000);
        System.out.println("DownloadRecord: " + count + " tests passed");
    }
}
