import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class AutocompleteSystem {

    private static final class Suggestion {
        final String query;
        final long freq;

        Suggestion(String query, long freq) {
            this.query = query;
            this.freq = freq;
        }
    }

    private final HashMap<String, Long> freq = new HashMap<>();
    private final HashMap<String, Suggestion[]> prefixCache = new HashMap<>();
    private final HashMap<String, String> vocab = new HashMap<>();

    private final int topK;
    private final int cachePrefixLimit;

    public AutocompleteSystem(int topK, int cachePrefixLimit) {
        if (topK <= 0) throw new IllegalArgumentException("topK");
        if (cachePrefixLimit <= 0) throw new IllegalArgumentException("cachePrefixLimit");
        this.topK = topK;
        this.cachePrefixLimit = cachePrefixLimit;
    }

    public void addQuery(String query, long initialFrequency) {
        if (query == null) return;
        String q = normalize(query);
        if (q.isEmpty()) return;

        Long cur = freq.get(q);
        long next = (cur == null ? 0L : cur) + Math.max(0L, initialFrequency);
        if (next == 0L && cur == null) return;

        freq.put(q, next);
        vocab.put(q, q);

        if (q.length() <= cachePrefixLimit) {
            for (int i = 1; i <= q.length(); i++) prefixCache.remove(q.substring(0, i));
        }
    }

    public long updateFrequency(String query) {
        return updateFrequency(query, 1);
    }

    public long updateFrequency(String query, long delta) {
        if (query == null) return 0L;
        String q = normalize(query);
        if (q.isEmpty()) return 0L;

        Long cur = freq.get(q);
        long next = (cur == null ? 0L : cur) + (delta <= 0 ? 1 : delta);
        freq.put(q, next);
        vocab.put(q, q);

        int lim = Math.min(cachePrefixLimit, q.length());
        for (int i = 1; i <= lim; i++) prefixCache.remove(q.substring(0, i));

        return next;
    }

    public String search(String prefix) {
        long start = System.nanoTime();
        if (prefix == null) return format(prefix, new Suggestion[0], start);

        String p = normalize(prefix);
        if (p.isEmpty()) return format(prefix, new Suggestion[0], start);

        Suggestion[] cached = prefixCache.get(p);
        if (cached != null) return format(p, cached, start);

        Suggestion[] top = computeTopKForPrefix(p);

        if (p.length() <= cachePrefixLimit) prefixCache.put(p, top);

        return format(p, top, start);
    }

    public String searchWithTypos(String input) {
        long start = System.nanoTime();
        if (input == null) return format(input, new Suggestion[0], start);

        String p = normalize(input);
        if (p.isEmpty()) return format(input, new Suggestion[0], start);

        Suggestion[] exact = prefixCache.get(p);
        if (exact == null) exact = computeTopKForPrefix(p);
        if (p.length() <= cachePrefixLimit) prefixCache.put(p, exact);

        if (exact.length > 0 && exact[0] != null) return format(p, exact, start);

        String corrected = bestCorrection(p);
        if (corrected == null) return format(p, exact, start);

        Suggestion[] corr = prefixCache.get(corrected);
        if (corr == null) corr = computeTopKForPrefix(corrected);
        if (corrected.length() <= cachePrefixLimit) prefixCache.put(corrected, corr);

        return format(p + " (did you mean \"" + corrected + "\"?)", corr, start);
    }

    public String benchmarkPrefixSearch(String prefix, int runs) {
        if (runs <= 0) runs = 1;
        long t1 = System.nanoTime();
        for (int i = 0; i < runs; i++) computeTopKForPrefix(normalize(prefix));
        long t2 = System.nanoTime();
        double avgMs = (t2 - t1) / 1_000_000.0 / runs;
        return "benchmark(\"" + prefix + "\") \u2192 avg: " + String.format("%.3f", avgMs) + "ms over " + runs + " runs";
    }

    private Suggestion[] computeTopKForPrefix(String p) {
        Suggestion[] top = new Suggestion[topK];

        for (Map.Entry<String, Long> e : freq.entrySet()) {
            String q = e.getKey();
            if (!startsWith(q, p)) continue;
            long f = e.getValue();
            insertTop(top, new Suggestion(q, f));
        }

        return compact(top);
    }

    private static void insertTop(Suggestion[] top, Suggestion s) {
        int pos = -1;
        for (int i = 0; i < top.length; i++) {
            if (top[i] == null) {
                pos = i;
                break;
            }
            if (s.freq > top[i].freq) {
                pos = i;
                break;
            }
            if (s.freq == top[i].freq && s.query.compareTo(top[i].query) < 0) {
                pos = i;
                break;
            }
        }
        if (pos < 0) return;
        for (int j = top.length - 1; j > pos; j--) top[j] = top[j - 1];
        top[pos] = s;
    }

    private static Suggestion[] compact(Suggestion[] arr) {
        int n = 0;
        for (Suggestion s : arr) if (s != null) n++;
        Suggestion[] out = new Suggestion[n];
        int i = 0;
        for (Suggestion s : arr) if (s != null) out[i++] = s;
        return out;
    }

    private String bestCorrection(String p) {
        if (p.length() < 2) return null;

        HashSet<String> candidates = new HashSet<>();

        for (int i = 0; i < p.length(); i++) {
            String del = p.substring(0, i) + p.substring(i + 1);
            if (!del.isEmpty()) candidates.add(del);
        }

        for (int i = 0; i < p.length() - 1; i++) {
            String swap = p.substring(0, i) + p.charAt(i + 1) + p.charAt(i) + p.substring(i + 2);
            candidates.add(swap);
        }

        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c < 'a' || c > 'z') continue;
            for (char r = 'a'; r <= 'z'; r++) {
                if (r == c) continue;
                String rep = p.substring(0, i) + r + p.substring(i + 1);
                candidates.add(rep);
            }
        }

        String best = null;
        long bestFreq = -1;

        for (String cand : candidates) {
            Suggestion[] top = computeTopKForPrefix(cand);
            if (top.length == 0) continue;
            Suggestion s = top[0];
            if (s == null) continue;
            if (s.freq > bestFreq) {
                bestFreq = s.freq;
                best = cand;
            }
        }

        return best;
    }

    private static boolean startsWith(String s, String p) {
        if (p.length() > s.length()) return false;
        for (int i = 0; i < p.length(); i++) if (s.charAt(i) != p.charAt(i)) return false;
        return true;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        String x = s.toLowerCase().trim().replaceAll("\\s+", " ");
        return x;
    }

    private static String format(String prefix, Suggestion[] arr, long startNanos) {
        long end = System.nanoTime();
        double ms = (end - startNanos) / 1_000_000.0;

        StringBuilder sb = new StringBuilder();
        sb.append("search(\"").append(prefix).append("\") \u2192\n");

        if (arr == null || arr.length == 0) {
            sb.append("No suggestions (").append(String.format("%.3f", ms)).append("ms)");
            return sb.toString();
        }

        for (int i = 0; i < arr.length; i++) {
            Suggestion s = arr[i];
            sb.append(i + 1).append(". \"").append(s.query).append("\" (").append(s.freq).append(" searches)\n");
        }

        sb.append("retrieved in ").append(String.format("%.3f", ms)).append("ms");
        return sb.toString();
    }

    public static void main(String[] args) {
        AutocompleteSystem a = new AutocompleteSystem(10, 6);

        a.addQuery("java tutorial", 1234567);
        a.addQuery("javascript", 987654);
        a.addQuery("java download", 456789);
        a.addQuery("java 21 features", 1);
        a.addQuery("java time complexity", 120000);

        System.out.println(a.search("jav"));
        System.out.println(a.updateFrequency("java 21 features"));
        System.out.println(a.updateFrequency("java 21 features"));
        System.out.println(a.searchWithTypos("jva"));
        System.out.println(a.benchmarkPrefixSearch("jav", 50));
    }
}