import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class RealTimeAnalyticsDashboard {

    public static final class PageViewEvent {
        public final String url;
        public final String userId;
        public final String source;

        public PageViewEvent(String url, String userId, String source) {
            this.url = url;
            this.userId = userId;
            this.source = source;
        }
    }

    private final HashMap<String, Long> pageViews = new HashMap<>();
    private final HashMap<String, HashSet<String>> uniqueVisitors = new HashMap<>();
    private final HashMap<String, Long> sourceCounts = new HashMap<>();

    private final int topN;
    private final long dashboardIntervalMs;

    private volatile String lastDashboard = "";

    private final Thread refresher;
    private volatile boolean running = true;

    public RealTimeAnalyticsDashboard(int topN, long dashboardIntervalMs) {
        if (topN <= 0) throw new IllegalArgumentException("topN");
        this.topN = topN;
        this.dashboardIntervalMs = Math.max(1000, dashboardIntervalMs);
        this.refresher = new Thread(this::refreshLoop, "dashboard-refresher");
        this.refresher.setDaemon(true);
        this.refresher.start();
    }

    public void shutdown() {
        running = false;
        refresher.interrupt();
    }

    public void processEvent(PageViewEvent e) {
        if (e == null || e.url == null || e.userId == null) return;

        synchronized (this) {
            Long pv = pageViews.get(e.url);
            pageViews.put(e.url, pv == null ? 1L : pv + 1L);

            HashSet<String> set = uniqueVisitors.get(e.url);
            if (set == null) {
                set = new HashSet<>();
                uniqueVisitors.put(e.url, set);
            }
            set.add(e.userId);

            String src = e.source == null ? "other" : e.source.toLowerCase();
            Long sc = sourceCounts.get(src);
            sourceCounts.put(src, sc == null ? 1L : sc + 1L);
        }
    }

    public String getDashboard() {
        return lastDashboard;
    }

    private void refreshLoop() {
        while (running) {
            try {
                Thread.sleep(dashboardIntervalMs);
            } catch (InterruptedException ignored) {
            }
            String snapshot = buildDashboard();
            lastDashboard = snapshot;
        }
    }

    private String buildDashboard() {
        HashMap<String, Long> pv;
        HashMap<String, Integer> uv;
        HashMap<String, Long> sc;

        synchronized (this) {
            pv = new HashMap<>(pageViews.size() * 2 + 1);
            uv = new HashMap<>(uniqueVisitors.size() * 2 + 1);
            sc = new HashMap<>(sourceCounts.size() * 2 + 1);

            for (Map.Entry<String, Long> e : pageViews.entrySet()) pv.put(e.getKey(), e.getValue());
            for (Map.Entry<String, HashSet<String>> e : uniqueVisitors.entrySet()) uv.put(e.getKey(), e.getValue().size());
            for (Map.Entry<String, Long> e : sourceCounts.entrySet()) sc.put(e.getKey(), e.getValue());
        }

        TopEntry[] top = topPages(pv, uv, topN);

        long totalSrc = 0;
        for (long v : sc.values()) totalSrc += v;

        StringBuilder sb = new StringBuilder();
        sb.append("Top Pages:\n");
        for (int i = 0; i < top.length; i++) {
            if (top[i] == null) break;
            sb.append(i + 1).append(". ").append(top[i].url).append(" - ").append(top[i].views).append(" views (").append(top[i].unique).append(" unique)\n");
        }

        sb.append("\nTraffic Sources:\n");
        if (totalSrc == 0) {
            sb.append("No data");
            return sb.toString();
        }

        String[] keys = new String[sc.size()];
        long[] vals = new long[sc.size()];
        int idx = 0;
        for (Map.Entry<String, Long> e : sc.entrySet()) {
            keys[idx] = e.getKey();
            vals[idx] = e.getValue();
            idx++;
        }

        sortByValueDesc(keys, vals);

        for (int i = 0; i < keys.length; i++) {
            double pct = (vals[i] * 100.0) / totalSrc;
            sb.append(cap(keys[i])).append(": ").append(String.format("%.0f", pct)).append("%");
            if (i + 1 < keys.length) sb.append(", ");
        }

        return sb.toString();
    }

    private static final class TopEntry {
        final String url;
        final long views;
        final int unique;

        TopEntry(String url, long views, int unique) {
            this.url = url;
            this.views = views;
            this.unique = unique;
        }
    }

    private static TopEntry[] topPages(HashMap<String, Long> pv, HashMap<String, Integer> uv, int k) {
        TopEntry[] top = new TopEntry[k];

        for (Map.Entry<String, Long> e : pv.entrySet()) {
            String url = e.getKey();
            long views = e.getValue();
            int unique = uv.get(url) == null ? 0 : uv.get(url);

            int pos = -1;
            for (int i = 0; i < k; i++) {
                if (top[i] == null || views > top[i].views) {
                    pos = i;
                    break;
                }
            }

            if (pos >= 0) {
                for (int j = k - 1; j > pos; j--) top[j] = top[j - 1];
                top[pos] = new TopEntry(url, views, unique);
            }
        }

        return top;
    }

    private static void sortByValueDesc(String[] keys, long[] vals) {
        for (int i = 0; i < vals.length; i++) {
            int best = i;
            for (int j = i + 1; j < vals.length; j++) {
                if (vals[j] > vals[best]) best = j;
            }
            if (best != i) {
                long tv = vals[i];
                vals[i] = vals[best];
                vals[best] = tv;

                String tk = keys[i];
                keys[i] = keys[best];
                keys[best] = tk;
            }
        }
    }

    private static String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        if (s.length() == 1) return s.toUpperCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static void main(String[] args) throws Exception {
        RealTimeAnalyticsDashboard d = new RealTimeAnalyticsDashboard(10, 5000);

        d.processEvent(new PageViewEvent("/article/breaking-news", "user_123", "google"));
        d.processEvent(new PageViewEvent("/article/breaking-news", "user_456", "facebook"));
        d.processEvent(new PageViewEvent("/sports/championship", "user_789", "direct"));
        d.processEvent(new PageViewEvent("/sports/championship", "user_789", "direct"));
        d.processEvent(new PageViewEvent("/sports/championship", "user_222", "google"));

        Thread.sleep(5200);
        System.out.println(d.getDashboard());

        d.shutdown();
    }
}