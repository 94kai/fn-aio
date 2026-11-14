package top.fnaio.direct;

public final class QuickEndpointTest {
    public static void main(String[] args) {
        expect("https://example.test/dsh", "https://example.test/dsh/");
        expect("https://192.168.1.2:8443/app/aio/s/dsh/", "https://192.168.1.2:8443/app/aio/s/dsh/");
        expect("https://example.test", "https://example.test/");
        reject("http://192.168.1.2/dsh/");
        reject("https://name:password@example.test/");
        reject("https://example.test/?token=secret");
        reject("https://example.test/#fragment");
        reject("https://example.test/a/../dsh/");
        reject("https://example.test/a/%2e%2e/dsh/");
        reject("https://example.test//dsh/");
        reject("https://example.test:0/");
        reject("https://example.test:65536/");
        reject("https://example.test\\@evil.test/");
        System.out.println("13 tests passed");
    }
    private static void expect(String input,String expected) {
        if (!expected.equals(QuickEndpoint.normalize(input))) throw new AssertionError("normalization failed");
    }
    private static void reject(String input) {
        try { QuickEndpoint.normalize(input); throw new AssertionError("unsafe URL accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}
