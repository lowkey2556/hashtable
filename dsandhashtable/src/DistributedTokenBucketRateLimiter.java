import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class DistributedTokenBucketRateLimiter {

    private static final class TokenBucket {
        final long maxTokens;
        final long refillPerSecond;
        final AtomicLong tokens;
        final AtomicLong lastRefillMs;

        TokenBucket(long maxTokens, long refillPerSecond, long nowMs) {
            this.maxTokens = maxTokens;
            this.refillPerSecond = refillPerSecond;
            this.tokens = new AtomicLong(maxTokens);
            this.lastRefillMs = new AtomicLong(nowMs);
        }

        long refill(long nowMs) {
            while (true) {
                long last = lastRefillMs.get();
                if (nowMs <= last) return tokens.get();
                long deltaMs = nowMs - last;
                long add = (deltaMs * refillPerSecond) / 1000L;
                if (add <= 0) return tokens.get();
                long curTokens = tokens.get();
                long nextTokens = curTokens + add;
                if (nextTokens > maxTokens) nextTokens = maxTokens;
                if (lastRefillMs.compareAndSet(last, nowMs)) {
                    tokens.set(nextTokens);
                    return nextTokens;
                }
            }
        }

        Decision tryConsume(long nowMs, long n) {
            refill(nowMs);
            while (true) {
                long cur = tokens.get();
                if (cur < n) {
                    long deficit = n - cur;
                    long waitMs = (deficit * 1000L + refillPerSecond - 1) / refillPerSecond;
                    return Decision.denied(cur, waitMs / 1000L);
                }
                if (tokens.compareAndSet(cur, cur - n)) {
                    return Decision.allowed(cur - n);
                }
            }
        }
    }

    public static final class Decision {
        public final boolean allowed;
        public final long remaining;
        public final long retryAfterSeconds;
        public final String message;

        private Decision(boolean allowed, long remaining, long retryAfterSeconds, String message) {
            this.allowed = allowed;
            this.remaining = remaining;
            this.retryAfterSeconds = retryAfterSeconds;
            this.message = message;
        }

        static Decision allowed(long remaining) {
            return new Decision(true, remaining, 0, "Allowed (" + remaining + " requests remaining)");
        }

        static Decision denied(long remaining, long retryAfterSeconds) {
            return new Decision(false, remaining, retryAfterSeconds, "Denied (" + remaining + " requests remaining, retry after " + retryAfterSeconds + "s)");
        }

        @Override
        public String toString() {
            return message;
        }
    }

    public static final class Status {
        public final String clientId;
        public final long used;
        public final long limit;
        public final long resetEpochSeconds;

        public Status(String clientId, long used, long limit, long resetEpochSeconds) {
            this.clientId = clientId;
            this.used = used;
            this.limit = limit;
            this.resetEpochSeconds = resetEpochSeconds;
        }

        @Override
        public String toString() {
            return "{used: " + used + ", limit: " + limit + ", reset: " + resetEpochSeconds + "}";
        }
    }

    private final Map<String, TokenBucket> buckets =
            Collections.synchronizedMap(new HashMap<>());

    private final long limitPerHour;
    private final long refillPerSecond;
    private final long windowMs;

    public DistributedTokenBucketRateLimiter(long limitPerHour) {
        if (limitPerHour <= 0) throw new IllegalArgumentException("limitPerHour");
        this.limitPerHour = limitPerHour;
        this.windowMs = 3600_000L;
        this.refillPerSecond = Math.max(1, limitPerHour / 3600L);
    }

    public Decision checkRateLimit(String clientId) {
        return checkRateLimit(clientId, 1);
    }

    public Decision checkRateLimit(String clientId, long cost) {
        if (clientId == null || clientId.isEmpty()) return Decision.denied(0, 3600);
        if (cost <= 0) cost = 1;

        long nowMs = System.currentTimeMillis();
        TokenBucket b = getOrCreate(clientId, nowMs);
        return b.tryConsume(nowMs, cost);
    }

    public Status getRateLimitStatus(String clientId) {
        long nowMs = System.currentTimeMillis();
        TokenBucket b = getOrCreate(clientId, nowMs);
        b.refill(nowMs);

        long remaining = b.tokens.get();
        long used = b.maxTokens - remaining;

        long resetMs = ((nowMs / windowMs) + 1) * windowMs;
        long resetEpochSeconds = resetMs / 1000L;

        return new Status(clientId, used, b.maxTokens, resetEpochSeconds);
    }

    private TokenBucket getOrCreate(String clientId, long nowMs) {
        TokenBucket b = buckets.get(clientId);
        if (b != null) return b;
        synchronized (buckets) {
            b = buckets.get(clientId);
            if (b == null) {
                b = new TokenBucket(limitPerHour, refillPerSecond, nowMs);
                buckets.put(clientId, b);
            }
            return b;
        }
    }

    public static void main(String[] args) {
        DistributedTokenBucketRateLimiter rl = new DistributedTokenBucketRateLimiter(1000);

        System.out.println("checkRateLimit(clientId=\"abc123\") \u2192 " + rl.checkRateLimit("abc123"));
        System.out.println("checkRateLimit(clientId=\"abc123\") \u2192 " + rl.checkRateLimit("abc123"));

        for (int i = 0; i < 998; i++) rl.checkRateLimit("abc123");

        System.out.println("checkRateLimit(clientId=\"abc123\") \u2192 " + rl.checkRateLimit("abc123"));
        System.out.println("getRateLimitStatus(\"abc123\") \u2192 " + rl.getRateLimitStatus("abc123"));
    }
}