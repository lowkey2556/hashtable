import java.util.HashMap;

public class ParkingLotOpenAddressing {

    private static final class SpotEntry {
        String plate;
        long entryMs;
        long totalParkedMs;
        int state;
    }

    private static final int EMPTY = 0;
    private static final int OCCUPIED = 1;
    private static final int DELETED = 2;

    private final SpotEntry[] table;
    private final int capacity;

    private int occupiedCount;

    private long totalProbes;
    private long parkOps;

    private long[] parkedMsByHour = new long[24];

    private final HashMap<String, Long> lifetimeMsByPlate = new HashMap<>();

    private final double feePerHour;
    private final double minFee;

    public ParkingLotOpenAddressing(int capacity, double feePerHour, double minFee) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
        this.table = new SpotEntry[capacity];
        for (int i = 0; i < capacity; i++) {
            SpotEntry e = new SpotEntry();
            e.state = EMPTY;
            table[i] = e;
        }
        this.feePerHour = feePerHour;
        this.minFee = minFee;
    }

    public String parkVehicle(String plate) {
        if (plate == null) return "parkVehicle(null) \u2192 Invalid";
        String p = normalize(plate);
        if (p.isEmpty()) return "parkVehicle(\"" + plate + "\") \u2192 Invalid";

        long now = System.currentTimeMillis();

        int existing = findIndex(p);
        if (existing >= 0) return "parkVehicle(\"" + plate + "\") \u2192 Already parked at spot #" + existing;

        if (occupiedCount >= capacity) return "parkVehicle(\"" + plate + "\") \u2192 Lot Full";

        int start = preferredSpot(p);
        int probes = 0;

        int best = -1;
        int bestDist = Integer.MAX_VALUE;
        int bestProbes = 0;

        int firstDeleted = -1;
        int firstDeletedProbes = 0;

        for (int i = 0; i < capacity; i++) {
            int idx = (start + i) % capacity;
            SpotEntry e = table[idx];

            if (e.state == OCCUPIED) {
                probes++;
                continue;
            }

            if (e.state == DELETED && firstDeleted < 0) {
                firstDeleted = idx;
                firstDeletedProbes = probes;
            }

            int dist = circularDistance(start, idx);
            if (dist < bestDist) {
                bestDist = dist;
                best = idx;
                bestProbes = probes;
                if (bestDist == 0) break;
            }

            if (e.state == EMPTY) break;
            probes++;
        }

        int chosen = best;
        int chosenProbes = bestProbes;

        if (chosen < 0 && firstDeleted >= 0) {
            chosen = firstDeleted;
            chosenProbes = firstDeletedProbes;
        }

        if (chosen < 0) return "parkVehicle(\"" + plate + "\") \u2192 Lot Full";

        SpotEntry slot = table[chosen];
        slot.plate = p;
        slot.entryMs = now;
        slot.state = OCCUPIED;

        occupiedCount++;

        totalProbes += chosenProbes;
        parkOps++;

        return "parkVehicle(\"" + plate + "\") \u2192 Assigned spot #" + chosen + " (" + chosenProbes + " probes)";
    }

    public String exitVehicle(String plate) {
        if (plate == null) return "exitVehicle(null) \u2192 Invalid";
        String p = normalize(plate);
        if (p.isEmpty()) return "exitVehicle(\"" + plate + "\") \u2192 Invalid";

        int idx = findIndex(p);
        if (idx < 0) return "exitVehicle(\"" + plate + "\") \u2192 Not found";

        long now = System.currentTimeMillis();

        SpotEntry e = table[idx];
        long duration = Math.max(0L, now - e.entryMs);

        e.totalParkedMs += duration;
        e.state = DELETED;
        e.plate = null;
        e.entryMs = 0L;

        occupiedCount--;

        addToPeakBuckets(now, duration);

        Long life = lifetimeMsByPlate.get(p);
        lifetimeMsByPlate.put(p, (life == null ? 0L : life) + duration);

        double fee = fee(duration);

        return "exitVehicle(\"" + plate + "\") \u2192 Spot #" + idx + " freed, Duration: " + formatDuration(duration) + ", Fee: $" + String.format("%.2f", fee);
    }

    public String findNearestAvailableSpot(String plate) {
        if (plate == null) return "findNearestAvailableSpot(null) \u2192 Invalid";
        String p = normalize(plate);
        if (p.isEmpty()) return "findNearestAvailableSpot(\"" + plate + "\") \u2192 Invalid";
        if (occupiedCount >= capacity) return "findNearestAvailableSpot(\"" + plate + "\") \u2192 Lot Full";

        int start = preferredSpot(p);

        int best = -1;
        int bestDist = Integer.MAX_VALUE;

        for (int i = 0; i < capacity; i++) {
            int idx = (start + i) % capacity;
            SpotEntry e = table[idx];
            if (e.state != OCCUPIED) {
                int dist = circularDistance(start, idx);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = idx;
                    if (bestDist == 0) break;
                }
                if (e.state == EMPTY) break;
            }
        }

        return best < 0 ? "findNearestAvailableSpot(\"" + plate + "\") \u2192 Lot Full" : "findNearestAvailableSpot(\"" + plate + "\") \u2192 Spot #" + best;
    }

    public String getStatistics() {
        double occupancy = capacity == 0 ? 0.0 : (occupiedCount * 100.0) / capacity;
        double avgProbes = parkOps == 0 ? 0.0 : (totalProbes * 1.0) / parkOps;
        int peakHour = peakHour();

        return "getStatistics() \u2192 Occupancy: " + String.format("%.0f", occupancy) + "%, Avg Probes: " + String.format("%.2f", avgProbes) + ", Peak Hour: " + hourLabel(peakHour);
    }

    private int preferredSpot(String plate) {
        int h = plateHash(plate);
        int idx = h % capacity;
        if (idx < 0) idx += capacity;
        return idx;
    }

    private int findIndex(String plate) {
        int start = preferredSpot(plate);

        for (int i = 0; i < capacity; i++) {
            int idx = (start + i) % capacity;
            SpotEntry e = table[idx];
            if (e.state == EMPTY) return -1;
            if (e.state == OCCUPIED && plate.equals(e.plate)) return idx;
        }
        return -1;
    }

    private int plateHash(String s) {
        int h = 0;
        for (int i = 0; i < s.length(); i++) {
            h = 31 * h + s.charAt(i);
        }
        h ^= (h >>> 16);
        return h & 0x7fffffff;
    }

    private static String normalize(String s) {
        String x = s.trim().toUpperCase().replaceAll("\\s+", "");
        return x;
    }

    private static int circularDistance(int start, int idx) {
        return idx >= start ? (idx - start) : (idx + Integer.MAX_VALUE);
    }

    private double fee(long durationMs) {
        double hours = durationMs / 3600_000.0;
        double v = hours * feePerHour;
        if (v < minFee) v = minFee;
        return v;
    }

    private static String formatDuration(long ms) {
        long totalMinutes = ms / 60_000L;
        long h = totalMinutes / 60L;
        long m = totalMinutes % 60L;
        return h + "h " + m + "m";
    }

    private void addToPeakBuckets(long nowMs, long durationMs) {
        int h = hourOfDay(nowMs);
        parkedMsByHour[h] += durationMs;
    }

    private static int hourOfDay(long nowMs) {
        long dayMs = 24L * 3600_000L;
        long x = nowMs % dayMs;
        if (x < 0) x += dayMs;
        return (int) (x / 3600_000L);
    }

    private int peakHour() {
        int best = 0;
        long bestMs = parkedMsByHour[0];
        for (int i = 1; i < 24; i++) {
            if (parkedMsByHour[i] > bestMs) {
                bestMs = parkedMsByHour[i];
                best = i;
            }
        }
        return best;
    }

    private static String hourLabel(int h) {
        int a = h % 24;
        int b = (h + 1) % 24;
        return a + "-" + b;
    }

    public static void main(String[] args) throws Exception {
        ParkingLotOpenAddressing lot = new ParkingLotOpenAddressing(500, 5.50, 2.00);

        System.out.println(lot.parkVehicle("ABC-1234"));
        System.out.println(lot.parkVehicle("ABC-1235"));
        System.out.println(lot.parkVehicle("XYZ-9999"));

        Thread.sleep(100);

        System.out.println(lot.exitVehicle("ABC-1234"));
        System.out.println(lot.getStatistics());
    }
}