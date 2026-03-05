import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
public class UserNameChecker {
    private final HashMap<String, Integer> usernameToUserId = new HashMap<>();
    private final HashMap<String, Integer> attemptFreq = new HashMap<>();
    public synchronized boolean register(String username, int userId) {
        if (usernameToUserId.containsKey(username)) return false; // taken
        usernameToUserId.put(username, userId);
        return true;
    }
    public synchronized boolean checkAvailability(String username) {
        attemptFreq.put(username, attemptFreq.getOrDefault(username, 0) + 1);
        return !usernameToUserId.containsKey(username);
    }
    public synchronized List<String> suggestAlternatives(String username) {
        List<String> suggestions = new ArrayList<>();
        if (!usernameToUserId.containsKey(username)) {
            suggestions.add(username);
            return suggestions;
        }
        for (int i = 1; suggestions.size() < 3 && i <= 50; i++) {
            String candidate = username + i;
            if (!usernameToUserId.containsKey(candidate)) {
                suggestions.add(candidate);
            }
        }

        if (suggestions.size() < 3) {
            String dotVersion = username.replace('_', '.');
            if (!usernameToUserId.containsKey(dotVersion) && !suggestions.contains(dotVersion)) {
                suggestions.add(dotVersion);
            }
        }

        if (suggestions.size() < 3) {
            String removed = username.replace("_", "");
            if (!usernameToUserId.containsKey(removed) && !suggestions.contains(removed)) {
                suggestions.add(removed);
            }
        }

        int i = 51;
        while (suggestions.size() < 3) {
            String candidate = username + i;
            if (!usernameToUserId.containsKey(candidate)) {
                suggestions.add(candidate);
            }
            i++;
        }

        return suggestions;
    }

    public synchronized String getMostAttempted() {
        String mostAttempted = null;
        int max = 0;

        for (Map.Entry<String, Integer> entry : attemptFreq.entrySet()) {
            if (entry.getValue() > max) {
                max = entry.getValue();
                mostAttempted = entry.getKey();
            }
        }
        return mostAttempted;
    }

    public static void main(String[] args) {
        UserNameChecker checker = new UserNameChecker();
        checker.register("john_doe", 101);
        checker.register("admin", 1);
        checker.register("john_doe1", 102); // taken to show suggestion logic

        System.out.println("checkAvailability(\"john_doe\") -> " + checker.checkAvailability("john_doe")); // false
        System.out.println("checkAvailability(\"jane_smith\") -> " + checker.checkAvailability("jane_smith")); // true

        System.out.println("suggestAlternatives(\"john_doe\") -> " + checker.suggestAlternatives("john_doe"));

        for (int i = 0; i < 10543; i++) checker.checkAvailability("admin");
        System.out.println("getMostAttempted() -> " + checker.getMostAttempted());
    }
}