import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public class MultiLevelVideoCache {

    public static final class VideoData {
        public final String videoId;
        public final String payload;
        public final long version;

        public VideoData(String videoId, String payload, long version) {
            this.videoId = videoId;
            this.payload = payload;
            this.version = version;
        }

        @Override
        public String toString() {
            return "{videoId:\"" + videoId + "\", version:" + version + "}";
        }
    }

    private static final class LRUMap<K, V> extends LinkedHashMap<K, V> {
        private final int cap;

        LRUMap(int cap) {
            super(cap * 2 + 1, 0.75f, true);
            this.cap = cap;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > cap;
        }
    }

    private final LRUMap<String, VideoData> l1;
    private final LRUMap<String, String> l2;
    private final HashMap<String, VideoData> l3;

    private final HashMap<String, Integer> accessCount = new HashMap<>();
    private final int promoteThreshold;

    private long l1Hits;
    private long l1Miss;
    private long l2Hits;
    private long l2Miss;
    private long l3Hits;

    private long l1TimeNs;
    private long l2TimeNs;
    private long l3TimeNs;

    private long reqs;

    private final long l1LatencyNs;
    private final long l2LatencyNs;
    private final long l3LatencyNs;

    public MultiLevelVideoCache(int l1Cap, int l2Cap, int promoteThreshold,
                               double l1Ms, double l2Ms, double l3Ms) {
        this.l1 = new LRUMap<>(l1Cap);
        this.l2 = new LRUMap<>(l2Cap);
        this.l3 = new HashMap<>();
        this.promoteThreshold = Math.max(1, promoteThreshold);
        this.l1LatencyNs = (long) (l1Ms * 1_000_000.0);
        this.l2LatencyNs = (long) (l2Ms * 1_000_000.0);
        this.l3LatencyNs = (long) (l3Ms * 1_000_000.0);
    }

    public void seedDatabase(VideoData v) {
        if (v == null || v.videoId == null) return;
        synchronized (this) {
            l3.put(v.videoId, v);
        }
    }

    public String getVideo(String videoId) {
        long start = System.nanoTime();
        if (videoId == null || videoId.isEmpty()) return "getVideo(null) \u2192 Invalid";

        VideoData v;

        long t1 = System.nanoTime();
        synchronized (this) {
            v = l1.get(videoId);
        }
        long t2 = System.nanoTime();
        l1TimeNs += (t2 - t1) + l1LatencyNs;

        if (v != null) {
            l1Hits++;
            reqs++;
            return "getVideo(\"" + videoId + "\")\n\u2192 L1 Cache HIT (" + fmtMs((t2 - t1) + l1LatencyNs) + ")\n\u2192 Total: " + fmtMs((System.nanoTime() - start) + l1LatencyNs);
        }

        l1Miss++;

        String path;

        long t3 = System.nanoTime();
        synchronized (this) {
            path = l2.get(videoId);
        }
        long t4 = System.nanoTime();
        l2TimeNs += (t4 - t3) + l2LatencyNs;

        if (path != null) {
            l2Hits++;
            v = readFromSSD(videoId, path);
            int c = incAccess(videoId);

            boolean promoted = c >= promoteThreshold;

            synchronized (this) {
                if (promoted) l1.put(videoId, v);
            }

            reqs++;
            return "getVideo(\"" + videoId + "\")\n\u2192 L1 Cache MISS (" + fmtMs((t2 - t1) + l1LatencyNs) + ")\n\u2192 L2 Cache HIT (" + fmtMs((t4 - t3) + l2LatencyNs) + ")\n\u2192 " + (promoted ? "Promoted to L1" : "Access count: " + c) + "\n\u2192 Total: " + fmtMs((System.nanoTime() - start) + l1LatencyNs + l2LatencyNs);
        }

        l2Miss++;

        long t5 = System.nanoTime();
        synchronized (this) {
            v = l3.get(videoId);
        }
        long t6 = System.nanoTime();
        l3TimeNs += (t6 - t5) + l3LatencyNs;

        if (v == null) {
            reqs++;
            return "getVideo(\"" + videoId + "\")\n\u2192 L1 Cache MISS\n\u2192 L2 Cache MISS\n\u2192 L3 Database MISS\n\u2192 Total: " + fmtMs((System.nanoTime() - start) + l3LatencyNs);
        }

        l3Hits++;

        synchronized (this) {
            l2.put(videoId, ssdPath(videoId));
        }

        int c = incAccess(videoId);

        reqs++;
        return "getVideo(\"" + videoId + "\")\n\u2192 L1 Cache MISS (" + fmtMs((t2 - t1) + l1LatencyNs) + ")\n\u2192 L2 Cache MISS (" + fmtMs((t4 - t3) + l2LatencyNs) + ")\n\u2192 L3 Database HIT (" + fmtMs((t6 - t5) + l3LatencyNs) + ")\n\u2192 Added to L2 (access count: " + c + ")\n\u2192 Total: " + fmtMs((System.nanoTime() - start) + l1LatencyNs + l2LatencyNs + l3LatencyNs);
    }

    public void invalidate(String videoId) {
        if (videoId == null) return;
        synchronized (this) {
            l1.remove(videoId);
            l2.remove(videoId);
            accessCount.remove(videoId);
        }
    }

    public void updateContent(VideoData v) {
        if (v == null || v.videoId == null) return;
        synchronized (this) {
            l3.put(v.videoId, v);
            l1.remove(v.videoId);
            l2.remove(v.videoId);
            accessCount.remove(v.videoId);
        }
    }

    public String getStatistics() {
        long total = reqs == 0 ? 1 : reqs;

        double l1HitRate = (l1Hits * 100.0) / total;
        double l2HitRate = (l2Hits * 100.0) / total;
        double l3HitRate = (l3Hits * 100.0) / total;

        double l1Avg = reqs == 0 ? 0.0 : (l1TimeNs / 1_000_000.0) / total;
        double l2Avg = reqs == 0 ? 0.0 : (l2TimeNs / 1_000_000.0) / total;
        double l3Avg = reqs == 0 ? 0.0 : (l3TimeNs / 1_000_000.0) / total;

        double overallHit = ((l1Hits + l2Hits + l3Hits) * 100.0) / total;

        double overallAvg = reqs == 0 ? 0.0 : ((l1TimeNs + l2TimeNs + l3TimeNs) / 1_000_000.0) / total;

        return "getStatistics() \u2192\n" +
                "L1: Hit Rate " + String.format("%.0f", l1HitRate) + "%, Avg Time: " + String.format("%.1f", l1Avg) + "ms\n" +
                "L2: Hit Rate " + String.format("%.0f", l2HitRate) + "%, Avg Time: " + String.format("%.1f", l2Avg) + "ms\n" +
                "L3: Hit Rate " + String.format("%.0f", l3HitRate) + "%, Avg Time: " + String.format("%.1f", l3Avg) + "ms\n" +
                "Overall: Hit Rate " + String.format("%.0f", overallHit) + "%, Avg Time: " + String.format("%.1f", overallAvg) + "ms";
    }

    private int incAccess(String videoId) {
        synchronized (this) {
            Integer c = accessCount.get(videoId);
            int next = (c == null ? 0 : c) + 1;
            accessCount.put(videoId, next);
            return next;
        }
    }

    private VideoData readFromSSD(String videoId, String path) {
        long now = System.nanoTime();
        VideoData v;
        synchronized (this) {
            v = l3.get(videoId);
        }
        if (v == null) v = new VideoData(videoId, "ssd:" + path, now);
        return v;
    }

    private static String ssdPath(String videoId) {
        return "/ssd/cache/" + videoId;
    }

    private static String fmtMs(long ns) {
        return String.format("%.1fms", ns / 1_000_000.0);
    }

    public static void main(String[] args) {
        MultiLevelVideoCache c = new MultiLevelVideoCache(3, 5, 2, 0.5, 5.0, 150.0);

        c.seedDatabase(new VideoData("video_123", "data123", 1));
        c.seedDatabase(new VideoData("video_999", "data999", 1));
        c.seedDatabase(new VideoData("video_777", "data777", 1));

        System.out.println(c.getVideo("video_123"));
        System.out.println(c.getVideo("video_123"));
        System.out.println(c.getVideo("video_999"));
        System.out.println(c.getStatistics());

        c.updateContent(new VideoData("video_123", "data123_v2", 2));
        System.out.println(c.getVideo("video_123"));
        System.out.println(c.getStatistics());
    }
}