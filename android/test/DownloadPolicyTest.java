package top.fnaio.direct;

public final class DownloadPolicyTest {
    private static int count;
    private static void check(boolean value) { count++; if (!value) throw new AssertionError("case " + count); }
    public static void main(String[] args) {
        AccessPolicy nas = new AccessPolicy("https://nas.example", "dsh");
        AccessRoute fnid = new AccessRoute(nas, "https://direct.example:1025/dsh", false);
        AccessRoute direct = new AccessRoute(nas, "https://direct.example:1025/dsh", true);
        check(DownloadPolicy.serviceUrl(fnid, fnid.entry() + "files/a.pdf?download=1"));
        check(DownloadPolicy.serviceUrl(direct, direct.entry()));
        check(DownloadPolicy.serviceUrl(direct, direct.entry() + "/files/a.pdf"));
        check(!DownloadPolicy.serviceUrl(fnid, nas.home()));
        check(!DownloadPolicy.serviceUrl(fnid, nas.health()));
        check(!DownloadPolicy.serviceUrl(fnid, "https://nas.example/app/aio/s/dsh-other/a.pdf"));
        check(!DownloadPolicy.serviceUrl(direct, "https://direct.example/dsh/file"));
        check(!DownloadPolicy.serviceUrl(direct, fnid.entry() + "file"));
        check(!DownloadPolicy.serviceUrl(fnid, "http://nas.example/app/aio/s/dsh/file"));
        check(!DownloadPolicy.serviceUrl(fnid, "https://secret@nas.example/app/aio/s/dsh/file"));
        check(!DownloadPolicy.serviceUrl(fnid, fnid.entry() + "../api/file"));
        check(!DownloadPolicy.serviceUrl(fnid, fnid.entry() + "%2e%2e/file"));
        check(!DownloadPolicy.serviceUrl(fnid, fnid.entry() + "a%2ffile"));
        check(!DownloadPolicy.serviceUrl(fnid, fnid.entry() + "a%5cfile"));
        check(!DownloadPolicy.serviceUrl(null, fnid.entry()));
        check(DownloadPolicy.localUrl(fnid, fnid.entry(), "blob:https://nas.example/id"));
        check(!DownloadPolicy.localUrl(fnid, fnid.entry(), "blob:https://other.example/id"));
        check(!DownloadPolicy.localUrl(fnid, nas.home(), "blob:https://nas.example/id"));
        check(!DownloadPolicy.localUrl(fnid, fnid.entry(), "blob:null/id"));
        check(DownloadPolicy.localUrl(fnid, fnid.entry(), "data:text/plain;base64,SGk="));
        check(!DownloadPolicy.localUrl(fnid, fnid.entry(), "file:///private/file"));
        check(!DownloadPolicy.localUrl(fnid, fnid.entry(), "data:" + "a".repeat(8 * 1024 * 1024)));
        check(DownloadPolicy.filename("https://nas.example/a.pdf?token=secret", null).equals("a.pdf"));
        check(DownloadPolicy.filename("https://nas.example/download", "attachment; filename=report.pdf").equals("report.pdf"));
        check(DownloadPolicy.filename("https://nas.example/download", "attachment; filename=\"报告.pdf\"").equals("报告.pdf"));
        check(DownloadPolicy.filename("https://nas.example/download", "attachment; filename*=UTF-8''%E6%8A%A5%E5%91%8A.pdf").equals("报告.pdf"));
        check(DownloadPolicy.filename("https://nas.example/download", "attachment; filename*=UTF-8''a+b.txt").equals("a+b.txt"));
        check(DownloadPolicy.safeName("../../file.txt").indexOf('/') == -1);
        check(DownloadPolicy.safeName("x\\y\n.txt").equals("x_y_.txt"));
        check(DownloadPolicy.safeName(" .. ").equals("下载文件.bin"));
        check(DownloadPolicy.safeName("a".repeat(200) + ".xlsx").length() == 120);
        check(DownloadPolicy.safeName("a".repeat(200) + ".xlsx").endsWith(".xlsx"));
        check(DownloadPolicy.mime("application/pdf; charset=UTF-8").equals("application/pdf"));
        check(DownloadPolicy.mime("text/plain\r\nBad: header").equals("application/octet-stream"));
        check(DownloadPolicy.mime(null).equals("application/octet-stream"));
        check(DownloadPolicy.MAX_BYTES == 268435456);
        check(DownloadPolicy.CHUNK_BYTES == 65536);
        System.out.println("DownloadPolicy: " + count + " tests passed");
    }
}
