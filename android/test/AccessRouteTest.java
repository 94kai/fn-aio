package top.fnaio.direct;

public final class AccessRouteTest {
    private static int count;
    private static void check(boolean value) { count++; if (!value) throw new AssertionError("case " + count); }
    private static void reject(String url) {
        try { new AccessRoute(new AccessPolicy("https://nas.example", "dsh"), url, true); throw new AssertionError("accepted invalid route"); }
        catch (IllegalArgumentException expected) { count++; }
    }
    public static void main(String[] args) {
        AccessPolicy nas = new AccessPolicy("https://nas.example", "dsh");
        String directUrl = "https://direct.example:1025/dsh";
        AccessRoute fnid = new AccessRoute(nas, directUrl, false);
        AccessRoute direct = new AccessRoute(nas, directUrl, true);
        check(fnid.requiresNasLogin());
        check(!direct.requiresNasLogin());
        check(fnid.entry().equals(nas.target()));
        check(direct.entry().equals(directUrl));
        check(fnid.allows("https://nas.example/login"));
        check(!direct.allows("https://nas.example/login"));
        check(direct.allows(directUrl + "/session/1"));
        check(!fnid.allows(directUrl));
        check(!direct.allows("https://direct.example/dsh"));
        check(!direct.allows("https://direct.example:1026/dsh"));
        check(!direct.allows("https://direct.example.attacker.test:1025/dsh"));
        check(direct.navigation(directUrl + "/session") == AccessRoute.Navigation.IN_APP);
        check(fnid.navigation("https://other.example/page") == AccessRoute.Navigation.EXTERNAL);
        check(fnid.navigation(directUrl) == AccessRoute.Navigation.EXTERNAL);
        check(fnid.navigation("http://other.example/page") == AccessRoute.Navigation.EXTERNAL);
        check(fnid.navigation("https://secret@other.example/page") == AccessRoute.Navigation.BLOCKED);
        check(fnid.navigation("javascript:alert(1)") == AccessRoute.Navigation.BLOCKED);
        check(fnid.navigation("file:///etc/passwd") == AccessRoute.Navigation.BLOCKED);
        check(fnid.navigation("intent://something") == AccessRoute.Navigation.BLOCKED);
        check(fnid.navigation("https://other.example:99999") == AccessRoute.Navigation.BLOCKED);
        check(fnid.navigation("https://") == AccessRoute.Navigation.BLOCKED);
        check(AccessRoute.backAction(true, true) == AccessRoute.BackAction.CLOSE_MENU);
        check(AccessRoute.backAction(true, false) == AccessRoute.BackAction.CLOSE_MENU);
        check(AccessRoute.backAction(false, true) == AccessRoute.BackAction.GO_BACK);
        check(AccessRoute.backAction(false, false) == AccessRoute.BackAction.CONFIRM_EXIT);
        reject("");
        reject("http://direct.example/dsh");
        reject("https://name:secret@direct.example/dsh");
        reject("https://direct.example/dsh?token=secret");
        reject("https://direct.example/dsh#token");
        reject("https://direct.example:0/dsh");
        reject("https://direct.example:65536/dsh");
        reject("https://direct.example/a/../dsh");
        check(new AccessRoute(nas, "", false).requiresNasLogin());
        check(FloatingPosition.ballX(-10, 400, 48) == 0);
        check(FloatingPosition.ballX(999, 400, 48) == 352);
        check(FloatingPosition.ballY(999, 800, 48, 0) == 752);
        check(FloatingPosition.ballY(999, 800, 48, 110) == 642);
        check(FloatingPosition.ballY(-1, 800, 48, 110) == 0);
        check(FloatingPosition.panelX(352, 400, 48, 200) == 200);
        check(FloatingPosition.panelX(0, 400, 48, 200) == 0);
        check(FloatingPosition.ballX(10, 20, 48) == 0);
        check(FloatingPosition.fraction(176, 352) == 0.5f);
        check(FloatingPosition.fraction(100, 0) == 0);
        check(FloatingPosition.fraction(-1, 200) == 0);
        check(FloatingPosition.fraction(1000, 200) == 1);
        System.out.println("AccessRoute/FloatingPosition: " + count + " tests passed");
    }
}
