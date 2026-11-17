package top.fnaio.direct;

public final class VoiceReplyPolicyTest {
    private static int count;
    public static void main(String[] args) {
        check(VoiceReplyPolicy.cursor(-1) == -1); // legitimate empty session
        check(VoiceReplyPolicy.cursor(123L) == 123);
        check(VoiceReplyPolicy.cursor(123.0) == 123);
        reject(null); reject("123"); reject(-2); reject(1.5);
        reject(Double.NaN); reject(Double.POSITIVE_INFINITY); reject(Long.MAX_VALUE);
        check(!VoiceReplyPolicy.eligible("assistant/message", 99, 100)); // old reply
        check(!VoiceReplyPolicy.eligible("assistant/message", 100, 100)); // exclusive cursor
        check(VoiceReplyPolicy.eligible("assistant/message", 101, 100));
        check(!VoiceReplyPolicy.eligible("user/message", 101, 100));
        check(!VoiceReplyPolicy.eligible("assistant/attempt", 101, 100));
        check(VoiceReplyPolicy.ready(false, 0, 0));
        check(!VoiceReplyPolicy.ready(true, 0, 0));
        check(!VoiceReplyPolicy.ready(false, 1, 0));
        check(!VoiceReplyPolicy.ready(false, 0, 1));
        System.out.println(count + " tests passed");
    }
    private static void check(boolean value) { count++; if (!value) throw new AssertionError("check " + count); }
    private static void reject(Object value) {
        try { VoiceReplyPolicy.cursor(value); throw new AssertionError("invalid cursor accepted"); }
        catch (IllegalArgumentException expected) { count++; }
    }
}
