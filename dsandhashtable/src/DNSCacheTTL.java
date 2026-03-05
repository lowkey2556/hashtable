import java.util.concurrent.ThreadLocalRandom;

public class DNSCacheTTL {

    private static final class DNSEntry {
        final String domain;
        final String ip;
        final long createdAtMs;
        final long expiresAtMs;
        final int ttlSeconds;

        DNSEntry(String domain, String ip, long nowMs, int ttlSeconds) {
            this.domain = domain;
            this.ip = ip;
            this.createdAtMs = nowMs;
            this.ttlSeconds = ttlSeconds;
            this.expiresAtMs = nowMs + ttlSeconds * 1000L;
        }

        boolean expired(long nowMs) {
            return nowMs >= expiresAtMs;
        }
    }

    private static final class CacheNode {
        final String key;
        DNSEntry value;
        CacheNode prev;
        CacheNode next;

        CacheNode(String key, DNSEntry value) {
            this.key = key;
            this.value = value;
        }
    }

    private static final class HashNode {
        final String key;
        CacheNode cacheNode;
        HashNode next;

        HashNode(String key, CacheNode cacheNode, HashNode next) {
            this.key = key;
            this.cacheNode = cacheNode;
            this.next = next;
        }
    }

    private final HashNode[] table;
    private final int capacity;
    private int size;

    private CacheNode lruHead;
    private CacheNode lruTail;

    private long hits;
    private long misses;
    private long expired;
    private long totalLookupNanos;
    private long lookups;

    private final long cleanupIntervalMs;
    private final Thread cleaner;
    private volatile boolean running = true;

    public DNSCacheTTL(int capacity, int bucketCount, long cleanupIntervalMs) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity");
        if (bucketCount <= 0) throw new IllegalArgumentException("bucketCount");
        this.capacity = capacity;
        this.table = new HashNode[bucketCount];
        this.cleanupIntervalMs = Math.max(50, cleanupIntervalMs);
        this.cleaner = new Thread(this::cleanupLoop, "dns-cache-cleaner");
        this.cleaner.setDaemon(true);
        this.cleaner.start();
    }

    public String resolve(String domain) {
        long start = System.nanoTime();
        DNSEntry result;
        String tag;

        long nowMs = System.currentTimeMillis();

        synchronized (this) {
            CacheNode node = getNode(domain);
            if (node == null) {
                misses++;
                tag = "Cache MISS";
                result = null;
            } else {
                DNSEntry e = node.value;
                if (e.expired(nowMs)) {
                    expired++;
                    tag = "Cache EXPIRED";
                    removeKey(domain);
                    result = null;
                } else {
                    hits++;
                    tag = "Cache HIT";
                    moveToFront(node);
                    result = e;
                }
            }
        }

        if (result == null) {
            UpstreamAnswer ans = queryUpstream(domain);
            nowMs = System.currentTimeMillis();
            DNSEntry e = new DNSEntry(domain, ans.ip, nowMs, ans.ttlSeconds);

            synchronized (this) {
                putNode(domain, e);
            }
            result = e;
            long end = System.nanoTime();
            synchronized (this) {
                totalLookupNanos += (end - start);
                lookups++;
            }
            return "resolve(\"" + domain + "\") \u2192 " + tag + " \u2192 Query upstream \u2192 " + result.ip + " (TTL: " + result.ttlSeconds + "s)";
        } else {
            long end = System.nanoTime();
            double ms = (end - start) / 1_000_000.0;
            synchronized (this) {
                totalLookupNanos += (end - start);
                lookups++;
            }
            return "resolve(\"" + domain + "\") \u2192 " + tag + " \u2192 " + result.ip + " (retrieved in " + String.format("%.3f", ms) + "ms)";
        }
    }

    public synchronized String getCacheStats() {
        long h = hits;
        long m = misses;
        long e = expired;
        long t = lookups;
        double hitRate = (h + m + e) == 0 ? 0.0 : (h * 100.0) / (h + m + e);
        double avgMs = t == 0 ? 0.0 : (totalLookupNanos / 1_000_000.0) / t;
        return "getCacheStats() \u2192 Hit Rate: " + String.format("%.1f", hitRate) + "%, Avg Lookup Time: " + String.format("%.3f", avgMs) + "ms, Hits: " + h + ", Misses: " + m + ", Expired: " + e + ", Size: " + size + "/" + capacity;
    }

    public void shutdown() {
        running = false;
        cleaner.interrupt();
    }

    private int idx(String key) {
        int h = key.hashCode();
        h ^= (h >>> 16);
        if (h == Integer.MIN_VALUE) h = 0;
        return (h & 0x7fffffff) % table.length;
    }

    private CacheNode getNode(String key) {
        int i = idx(key);
        HashNode n = table[i];
        while (n != null) {
            if (n.key.equals(key)) return n.cacheNode;
            n = n.next;
        }
        return null;
    }

    private void putNode(String key, DNSEntry value) {
        CacheNode existing = getNode(key);
        if (existing != null) {
            existing.value = value;
            moveToFront(existing);
            return;
        }

        if (size >= capacity) {
            evictLRU();
        }

        CacheNode cn = new CacheNode(key, value);
        addToFront(cn);

        int i = idx(key);
        table[i] = new HashNode(key, cn, table[i]);
        size++;
    }

    private void removeKey(String key) {
        int i = idx(key);
        HashNode prev = null;
        HashNode cur = table[i];

        while (cur != null) {
            if (cur.key.equals(key)) {
                CacheNode cn = cur.cacheNode;
                unlink(cn);

                if (prev == null) table[i] = cur.next;
                else prev.next = cur.next;

                size--;
                return;
            }
            prev = cur;
            cur = cur.next;
        }
    }

    private void evictLRU() {
        CacheNode t = lruTail;
        if (t == null) return;
        removeKey(t.key);
    }

    private void addToFront(CacheNode n) {
        n.prev = null;
        n.next = lruHead;
        if (lruHead != null) lruHead.prev = n;
        lruHead = n;
        if (lruTail == null) lruTail = n;
    }

    private void moveToFront(CacheNode n) {
        if (n == lruHead) return;
        unlink(n);
        addToFront(n);
    }

    private void unlink(CacheNode n) {
        CacheNode p = n.prev;
        CacheNode q = n.next;

        if (p != null) p.next = q;
        else lruHead = q;

        if (q != null) q.prev = p;
        else lruTail = p;

        n.prev = null;
        n.next = null;
    }

    private void cleanupLoop() {
        while (running) {
            try {
                Thread.sleep(cleanupIntervalMs);
            } catch (InterruptedException ignored) {
            }
            long nowMs = System.currentTimeMillis();
            synchronized (this) {
                for (int i = 0; i < table.length; i++) {
                    HashNode prev = null;
                    HashNode cur = table[i];
                    while (cur != null) {
                        CacheNode cn = cur.cacheNode;
                        DNSEntry e = cn.value;
                        if (e.expired(nowMs)) {
                            unlink(cn);
                            if (prev == null) table[i] = cur.next;
                            else prev.next = cur.next;
                            size--;
                            cur = (prev == null) ? table[i] : prev.next;
                        } else {
                            prev = cur;
                            cur = cur.next;
                        }
                    }
                }
            }
        }
    }

    private static final class UpstreamAnswer {
        final String ip;
        final int ttlSeconds;

        UpstreamAnswer(String ip, int ttlSeconds) {
            this.ip = ip;
            this.ttlSeconds = ttlSeconds;
        }
    }

    private UpstreamAnswer queryUpstream(String domain) {
        int ttl = domain.equalsIgnoreCase("google.com") ? 300 : 60;
        String ip = fakeIPv4(domain);
        return new UpstreamAnswer(ip, ttl);
    }

    private static String fakeIPv4(String domain) {
        int h = domain.hashCode();
        int a = (h >>> 24) & 0xff;
        int b = (h >>> 16) & 0xff;
        int c = (h >>> 8) & 0xff;
        int d = h & 0xff;
        a = (a % 223 + 1);
        b = (b + ThreadLocalRandom.current().nextInt(0, 3)) & 0xff;
        c = (c + ThreadLocalRandom.current().nextInt(0, 3)) & 0xff;
        d = (d + ThreadLocalRandom.current().nextInt(0, 3)) & 0xff;
        return a + "." + b + "." + c + "." + d;
    }

    public static void main(String[] args) throws Exception {
        DNSCacheTTL cache = new DNSCacheTTL(3, 16, 200);

        System.out.println(cache.resolve("google.com"));
        System.out.println(cache.resolve("google.com"));
        System.out.println(cache.resolve("openai.com"));
        System.out.println(cache.resolve("example.com"));
        System.out.println(cache.resolve("another.com"));
        System.out.println(cache.getCacheStats());

        Thread.sleep(3100);

        System.out.println(cache.resolve("google.com"));
        System.out.println(cache.getCacheStats());

        cache.shutdown();
    }
}