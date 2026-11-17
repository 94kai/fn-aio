package top.fnaio.direct;

/** Wire checks independent of Android. Never silently fall back to the whole journal. */
public final class VoiceReplyPolicy {
    private VoiceReplyPolicy() {}
    public static long cursor(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("服务未返回有效游标，请更新或检查 DSH 接口");
        Number number = (Number) value;
        long seq = number.longValue();
        if (seq < -1 || seq > 9_007_199_254_740_991L || number.doubleValue() != (double) seq)
            throw new IllegalArgumentException("服务返回的游标无效");
        return seq;
    }
    public static boolean eligible(String type, long seq, long afterSeq) {
        return "assistant/message".equals(type) && seq > afterSeq;
    }
    public static boolean ready(boolean running, int queued, int pendingInput) {
        return !running && queued == 0 && pendingInput == 0;
    }
}
