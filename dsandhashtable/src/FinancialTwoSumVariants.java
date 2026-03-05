import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class FinancialTwoSumVariants {

    public static final class Transaction {
        public final long id;
        public final long amount;
        public final String merchant;
        public final String account;
        public final long timeMs;

        public Transaction(long id, long amount, String merchant, String account, long timeMs) {
            this.id = id;
            this.amount = amount;
            this.merchant = merchant;
            this.account = account;
            this.timeMs = timeMs;
        }

        @Override
        public String toString() {
            return "{id:" + id + ", amount:" + amount + ", merchant:\"" + merchant + "\", account:\"" + account + "\"}";
        }
    }

    public static final class Pair {
        public final Transaction a;
        public final Transaction b;

        public Pair(Transaction a, Transaction b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public String toString() {
            return "(" + a.id + ", " + b.id + ")";
        }
    }

    public static final class DuplicateGroup {
        public final long amount;
        public final String merchant;
        public final String[] accounts;

        public DuplicateGroup(long amount, String merchant, String[] accounts) {
            this.amount = amount;
            this.merchant = merchant;
            this.accounts = accounts;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("{amount:").append(amount).append(", merchant:\"").append(merchant).append("\", accounts:[");
            for (int i = 0; i < accounts.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(accounts[i]);
            }
            sb.append("]}");
            return sb.toString();
        }
    }

    public static List<Pair> findTwoSum(List<Transaction> txns, long target) {
        HashMap<Long, Transaction> seen = new HashMap<>();
        ArrayList<Pair> out = new ArrayList<>();

        for (Transaction t : txns) {
            long need = target - t.amount;
            Transaction hit = seen.get(need);
            if (hit != null && hit.id != t.id) {
                out.add(new Pair(hit, t));
            }
            Transaction existing = seen.get(t.amount);
            if (existing == null) seen.put(t.amount, t);
        }

        return out;
    }

    public static List<Pair> findTwoSumWithinWindow(List<Transaction> txns, long target, long windowMs) {
        HashMap<Long, Transaction> active = new HashMap<>();
        ArrayList<Pair> out = new ArrayList<>();

        int left = 0;

        for (int right = 0; right < txns.size(); right++) {
            Transaction cur = txns.get(right);

            while (left < right && cur.timeMs - txns.get(left).timeMs > windowMs) {
                Transaction old = txns.get(left);
                Transaction v = active.get(old.amount);
                if (v != null && v.id == old.id) active.remove(old.amount);
                left++;
            }

            long need = target - cur.amount;
            Transaction hit = active.get(need);
            if (hit != null && hit.id != cur.id) out.add(new Pair(hit, cur));

            if (!active.containsKey(cur.amount)) active.put(cur.amount, cur);
        }

        return out;
    }

    public static List<List<Transaction>> findKSum(List<Transaction> txns, int k, long target) {
        HashMap<String, List<List<Transaction>>> memo = new HashMap<>();
        return kSum(txns, 0, k, target, memo);
    }

    private static List<List<Transaction>> kSum(List<Transaction> txns, int start, int k, long target,
                                                HashMap<String, List<List<Transaction>>> memo) {
        String key = start + "|" + k + "|" + target;
        List<List<Transaction>> cached = memo.get(key);
        if (cached != null) return cached;

        ArrayList<List<Transaction>> out = new ArrayList<>();

        if (k == 2) {
            HashMap<Long, Transaction> seen = new HashMap<>();
            for (int i = start; i < txns.size(); i++) {
                Transaction t = txns.get(i);
                long need = target - t.amount;
                Transaction hit = seen.get(need);
                if (hit != null && hit.id != t.id) {
                    ArrayList<Transaction> list = new ArrayList<>();
                    list.add(hit);
                    list.add(t);
                    out.add(list);
                }
                if (!seen.containsKey(t.amount)) seen.put(t.amount, t);
            }
            memo.put(key, out);
            return out;
        }

        for (int i = start; i < txns.size(); i++) {
            Transaction pick = txns.get(i);
            List<List<Transaction>> sub = kSum(txns, i + 1, k - 1, target - pick.amount, memo);
            for (List<Transaction> s : sub) {
                ArrayList<Transaction> list = new ArrayList<>(k);
                list.add(pick);
                list.addAll(s);
                out.add(list);
            }
        }

        memo.put(key, out);
        return out;
    }

    public static List<DuplicateGroup> detectDuplicates(List<Transaction> txns) {
        HashMap<String, HashSet<String>> keyToAccounts = new HashMap<>();

        for (Transaction t : txns) {
            String key = t.amount + "|" + norm(t.merchant);
            HashSet<String> set = keyToAccounts.get(key);
            if (set == null) {
                set = new HashSet<>();
                keyToAccounts.put(key, set);
            }
            if (t.account != null) set.add(t.account);
        }

        ArrayList<DuplicateGroup> out = new ArrayList<>();

        for (String key : keyToAccounts.keySet()) {
            HashSet<String> set = keyToAccounts.get(key);
            if (set == null || set.size() < 2) continue;

            int bar = key.indexOf('|');
            long amt = parseLong(key.substring(0, bar));
            String merch = key.substring(bar + 1);

            String[] accounts = new String[set.size()];
            int i = 0;
            for (String a : set) accounts[i++] = a;

            out.add(new DuplicateGroup(amt, merch, accounts));
        }

        return out;
    }

    private static String norm(String s) {
        if (s == null) return "";
        return s.trim();
    }

    private static long parseLong(String s) {
        long x = 0;
        boolean neg = false;
        int i = 0;
        if (s.length() > 0 && s.charAt(0) == '-') {
            neg = true;
            i = 1;
        }
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') break;
            x = x * 10 + (c - '0');
        }
        return neg ? -x : x;
    }

    private static String formatPairs(List<Pair> pairs) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < pairs.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(pairs.get(i).toString());
        }
        sb.append("]");
        return sb.toString();
    }

    private static String formatK(List<List<Transaction>> lists) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < lists.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("(");
            List<Transaction> l = lists.get(i);
            for (int j = 0; j < l.size(); j++) {
                if (j > 0) sb.append(", ");
                sb.append(l.get(j).id);
            }
            sb.append(")");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String formatDup(List<DuplicateGroup> dups) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < dups.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(dups.get(i).toString());
        }
        sb.append("]");
        return sb.toString();
    }

    public static void main(String[] args) {
        long base = System.currentTimeMillis();

        ArrayList<Transaction> transactions = new ArrayList<>();
        transactions.add(new Transaction(1, 500, "Store A", "acc1", base + 0));
        transactions.add(new Transaction(2, 300, "Store B", "acc2", base + 15 * 60_000L));
        transactions.add(new Transaction(3, 200, "Store C", "acc3", base + 30 * 60_000L));
        transactions.add(new Transaction(4, 500, "Store A", "acc2", base + 40 * 60_000L));

        List<Pair> two = findTwoSum(transactions, 500);
        System.out.println("findTwoSum(target=500) \u2192 " + formatPairs(two));

        List<Pair> win = findTwoSumWithinWindow(transactions, 500, 60 * 60_000L);
        System.out.println("findTwoSumWithinWindow(target=500, 1h) \u2192 " + formatPairs(win));

        List<List<Transaction>> ksum = findKSum(transactions, 3, 1000);
        System.out.println("findKSum(k=3, target=1000) \u2192 " + formatK(ksum));

        List<DuplicateGroup> dups = detectDuplicates(transactions);
        System.out.println("detectDuplicates() \u2192 " + formatDup(dups));
    }
}