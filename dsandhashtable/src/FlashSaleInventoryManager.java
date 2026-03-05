import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class FlashSaleInventoryManager {

    private final Map<String, AtomicInteger> stockByProduct =
            Collections.synchronizedMap(new HashMap<>());

    private final Map<String, LinkedHashMap<Long, Integer>> waitListByProduct =
            Collections.synchronizedMap(new HashMap<>());

    private final Map<String, AtomicInteger> waitSeqByProduct =
            Collections.synchronizedMap(new HashMap<>());

    public void addProduct(String productId, int initialStock) {
        stockByProduct.put(productId, new AtomicInteger(Math.max(0, initialStock)));
        waitListByProduct.put(productId, new LinkedHashMap<>());
        waitSeqByProduct.put(productId, new AtomicInteger(0));
    }

    public String checkStock(String productId) {
        AtomicInteger stock = stockByProduct.get(productId);
        if (stock == null) return "Product not found";
        int available = stock.get();
        return "checkStock(\"" + productId + "\") \u2192 " + available + " units available";
    }

    public String purchaseItem(String productId, long userId) {
        AtomicInteger stock = stockByProduct.get(productId);
        if (stock == null) return "Product not found";

        while (true) {
            int cur = stock.get();
            if (cur <= 0) break;
            if (stock.compareAndSet(cur, cur - 1)) {
                return "purchaseItem(\"" + productId + "\", userId=" + userId + ") \u2192 Success, " + (cur - 1) + " units remaining";
            }
        }

        LinkedHashMap<Long, Integer> waitList = waitListByProduct.get(productId);
        AtomicInteger seq = waitSeqByProduct.get(productId);

        if (waitList == null || seq == null) return "Product not found";

        int pos;
        synchronized (waitList) {
            Integer existing = waitList.get(userId);
            if (existing != null) {
                pos = existing;
            } else {
                pos = seq.incrementAndGet();
                waitList.put(userId, pos);
            }
        }

        return "purchaseItem(\"" + productId + "\", userId=" + userId + ") \u2192 Added to waiting list, position #" + pos;
    }

    public static void main(String[] args) {
        FlashSaleInventoryManager m = new FlashSaleInventoryManager();
        m.addProduct("IPHONE15_256GB", 100);

        System.out.println(m.checkStock("IPHONE15_256GB"));
        System.out.println(m.purchaseItem("IPHONE15_256GB", 12345));
        System.out.println(m.purchaseItem("IPHONE15_256GB", 67890));

        for (int i = 0; i < 98; i++) m.purchaseItem("IPHONE15_256GB", 20000 + i);

        System.out.println(m.purchaseItem("IPHONE15_256GB", 99999));
    }
}