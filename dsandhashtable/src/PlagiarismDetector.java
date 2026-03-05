import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PlagiarismDetector {

    private static final class DocStats {
        int matches;
        int totalA;
        int totalB;
        double similarity;
    }

    private final int n;
    private final Map<String, Set<String>> ngramToDocs = new HashMap<>();
    private final Map<String, Integer> docNgramCount = new HashMap<>();

    public PlagiarismDetector(int n) {
        if (n <= 0) throw new IllegalArgumentException("n");
        this.n = n;
    }

    public void addDocument(String docId, String text) {
        String[] words = tokenize(text);
        if (words.length < n) {
            docNgramCount.put(docId, 0);
            return;
        }

        int total = 0;
        HashSet<String> seen = new HashSet<>();

        for (int i = 0; i + n <= words.length; i++) {
            String ng = buildNgram(words, i, n);
            if (seen.add(ng)) {
                Set<String> docs = ngramToDocs.get(ng);
                if (docs == null) {
                    docs = new HashSet<>();
                    ngramToDocs.put(ng, docs);
                }
                docs.add(docId);
                total++;
            }
        }

        docNgramCount.put(docId, total);
    }

    public String analyzeDocument(String docId, String text) {
        String[] words = tokenize(text);
        if (words.length < n) {
            return "analyzeDocument(\"" + docId + "\")\n\u2192 Extracted 0 n-grams\n\u2192 No matches";
        }

        HashSet<String> ngramsA = new HashSet<>();
        for (int i = 0; i + n <= words.length; i++) ngramsA.add(buildNgram(words, i, n));

        int totalA = ngramsA.size();

        HashMap<String, Integer> matchesByDoc = new HashMap<>();

        for (String ng : ngramsA) {
            Set<String> docs = ngramToDocs.get(ng);
            if (docs == null) continue;
            for (String other : docs) {
                if (other.equals(docId)) continue;
                Integer c = matchesByDoc.get(other);
                matchesByDoc.put(other, c == null ? 1 : c + 1);
            }
        }

        String bestDoc = null;
        DocStats best = null;

        for (Map.Entry<String, Integer> e : matchesByDoc.entrySet()) {
            String other = e.getKey();
            int matches = e.getValue();
            int totalB = getDocCount(other);
            double sim = similarityPercent(matches, totalA, totalB);
            if (best == null || sim > best.similarity) {
                best = new DocStats();
                best.matches = matches;
                best.totalA = totalA;
                best.totalB = totalB;
                best.similarity = sim;
                bestDoc = other;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("analyzeDocument(\"").append(docId).append("\")\n");
        sb.append("\u2192 Extracted ").append(totalA).append(" n-grams\n");

        if (best == null) {
            sb.append("\u2192 No matches");
            return sb.toString();
        }

        sb.append("\u2192 Found ").append(best.matches).append(" matching n-grams with \"").append(bestDoc).append("\"\n");
        sb.append("\u2192 Similarity: ").append(String.format("%.1f", best.similarity)).append("%");
        return sb.toString();
    }

    public String analyzeTopK(String docId, String text, int k) {
        if (k <= 0) throw new IllegalArgumentException("k");

        String[] words = tokenize(text);
        if (words.length < n) {
            return "analyzeDocument(\"" + docId + "\")\n\u2192 Extracted 0 n-grams\n\u2192 No matches";
        }

        HashSet<String> ngramsA = new HashSet<>();
        for (int i = 0; i + n <= words.length; i++) ngramsA.add(buildNgram(words, i, n));

        int totalA = ngramsA.size();

        HashMap<String, Integer> matchesByDoc = new HashMap<>();
        for (String ng : ngramsA) {
            Set<String> docs = ngramToDocs.get(ng);
            if (docs == null) continue;
            for (String other : docs) {
                if (other.equals(docId)) continue;
                Integer c = matchesByDoc.get(other);
                matchesByDoc.put(other, c == null ? 1 : c + 1);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("analyzeDocument(\"").append(docId).append("\")\n");
        sb.append("\u2192 Extracted ").append(totalA).append(" n-grams\n");

        if (matchesByDoc.isEmpty()) {
            sb.append("\u2192 No matches");
            return sb.toString();
        }

        String[] topDocs = new String[Math.min(k, matchesByDoc.size())];
        double[] topSim = new double[topDocs.length];
        int[] topMatches = new int[topDocs.length];

        for (Map.Entry<String, Integer> e : matchesByDoc.entrySet()) {
            String other = e.getKey();
            int matches = e.getValue();
            int totalB = getDocCount(other);
            double sim = similarityPercent(matches, totalA, totalB);

            int pos = -1;
            for (int i = 0; i < topDocs.length; i++) {
                if (topDocs[i] == null || sim > topSim[i]) {
                    pos = i;
                    break;
                }
            }

            if (pos >= 0) {
                for (int j = topDocs.length - 1; j > pos; j--) {
                    topDocs[j] = topDocs[j - 1];
                    topSim[j] = topSim[j - 1];
                    topMatches[j] = topMatches[j - 1];
                }
                topDocs[pos] = other;
                topSim[pos] = sim;
                topMatches[pos] = matches;
            }
        }

        for (int i = 0; i < topDocs.length; i++) {
            if (topDocs[i] == null) break;
            sb.append("\u2192 Found ").append(topMatches[i]).append(" matching n-grams with \"").append(topDocs[i]).append("\"\n");
            sb.append("\u2192 Similarity: ").append(String.format("%.1f", topSim[i])).append("%");
            if (i + 1 < topDocs.length && topDocs[i + 1] != null) sb.append("\n");
        }

        return sb.toString();
    }

    public String benchmarkHashVsLinear(String docId, String text) {
        String[] words = tokenize(text);
        if (words.length < n) return "benchmark(\"" + docId + "\") \u2192 Not enough words";

        HashSet<String> ngramsA = new HashSet<>();
        for (int i = 0; i + n <= words.length; i++) ngramsA.add(buildNgram(words, i, n));
        int totalA = ngramsA.size();

        long t1 = System.nanoTime();
        HashMap<String, Integer> matchesByDoc = new HashMap<>();
        for (String ng : ngramsA) {
            Set<String> docs = ngramToDocs.get(ng);
            if (docs == null) continue;
            for (String other : docs) {
                if (other.equals(docId)) continue;
                Integer c = matchesByDoc.get(other);
                matchesByDoc.put(other, c == null ? 1 : c + 1);
            }
        }
        long t2 = System.nanoTime();

        long t3 = System.nanoTime();
        HashMap<String, Integer> matchesByDocLinear = new HashMap<>();
        for (String other : docNgramCount.keySet()) {
            if (other.equals(docId)) continue;
            int matches = 0;
            for (String ng : ngramsA) {
                Set<String> docs = ngramToDocs.get(ng);
                if (docs != null && docs.contains(other)) matches++;
            }
            if (matches > 0) matchesByDocLinear.put(other, matches);
        }
        long t4 = System.nanoTime();

        double hashMs = (t2 - t1) / 1_000_000.0;
        double linearMs = (t4 - t3) / 1_000_000.0;

        return "benchmark(\"" + docId + "\") \u2192 n-grams: " + totalA +
                ", hash-based: " + String.format("%.3f", hashMs) + "ms, linear: " + String.format("%.3f", linearMs) + "ms";
    }

    private int getDocCount(String docId) {
        Integer c = docNgramCount.get(docId);
        return c == null ? 0 : c;
    }

    private double similarityPercent(int matches, int totalA, int totalB) {
        int denom = Math.min(totalA, totalB);
        if (denom <= 0) return 0.0;
        return (matches * 100.0) / denom;
    }

    private static String[] tokenize(String text) {
        String cleaned = text == null ? "" : text.toLowerCase().replaceAll("[^a-z0-9\\s]+", " ");
        cleaned = cleaned.trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) return new String[0];
        return cleaned.split(" ");
    }

    private static String buildNgram(String[] words, int start, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append(words[start + i]);
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        PlagiarismDetector d = new PlagiarismDetector(5);

        String essay089 = "the quick brown fox jumps over the lazy dog in the big park every day with joy";
        String essay092 = "the quick brown fox jumps over the lazy dog in the big park every day with joy and the quick brown fox jumps again";
        String essay123 = "students write essays and the quick brown fox jumps over the lazy dog in the big park every day for practice and learning";

        d.addDocument("essay_089.txt", essay089);
        d.addDocument("essay_092.txt", essay092);

        System.out.println(d.analyzeTopK("essay_123.txt", essay123, 2));
        System.out.println(d.benchmarkHashVsLinear("essay_123.txt", essay123));
    }
}