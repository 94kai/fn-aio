package top.fnaio.direct;

public final class AccessPolicyTest {
    private static int count;
    private static void check(boolean value) { count++; if (!value) throw new AssertionError("case " + count); }
    private static void reject(String origin, String service) {
        try { new AccessPolicy(origin, service); throw new AssertionError("accepted invalid endpoint"); }
        catch (IllegalArgumentException expected) { count++; }
    }
    public static void main(String[] args) {
        AccessPolicy policy = new AccessPolicy("https://nas.example/", "dsh");
        check(policy.origin.equals("https://nas.example"));
        check(policy.target().equals("https://nas.example/app/aio/s/dsh/"));
        check(policy.health().equals("https://nas.example/app/aio/api/health"));
        check(policy.sameOrigin("https://NAS.example:443/login"));
        check(!policy.sameOrigin("https://nas.example.attacker.test/"));
        check(!policy.sameOrigin("https://nas.example:444/"));
        check(!policy.sameOrigin("http://nas.example/"));
        check(!policy.sameOrigin("https://user:password@nas.example/"));
        check(!policy.sameOrigin("javascript:alert(1)"));
        check(policy.servicePage(policy.target()));
        check(!policy.servicePage("https://nas.example/app/aio/s/dsh-other/"));
        check(AccessPolicy.invalidGatewayBody(" \nINVALID TOKEN\n"));
        check(!AccessPolicy.invalidGatewayBody("some page mentioning invalid token"));
        check(!AccessPolicy.invalidGatewayBody(null));
        reject("http://nas.example", "dsh");
        reject("https://name:secret@nas.example", "dsh");
        reject("https://nas.example/app/aio/", "dsh");
        reject("https://nas.example/?token=secret", "dsh");
        reject("https://nas.example/#secret", "dsh");
        reject("https://nas.example:65536", "dsh");
        reject("https://nas.example:0", "dsh");
        reject("https://nas.example", "../admin");
        reject("https://nas.example", "dsh/other");
        check(new AccessPolicy("https://nas.example:8888", "notes-1").sameOrigin("https://nas.example:8888/login"));
        System.out.println("AccessPolicy: " + count + " tests passed");
    }
}
