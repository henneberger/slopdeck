package com.henneberger;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Getter;

public class ActiveUsersTracker {
    EventType type;
    List<Result> current = List.of();

    public ActiveUsersTracker(EventType type) {
        this.type = type;
    }

    // Simulated like events: a queue of tuples (userId, timestamp)
    public final Queue<LikeEvent> likeEvents = new ConcurrentLinkedQueue<>();

    // Sliding window to hold events within the last 10 minutes
    public final Deque<LikeEvent> slidingWindow = new ArrayDeque<>();

    // Time window duration (10 minutes in milliseconds)
    public static final long TIME_WINDOW = 5 * 60 * 1000;

    // Frequency of computation (30 seconds in milliseconds)
    public static final long COMPUTE_FREQUENCY = 10 * 1000;

//    public static void main(String[] args) {
//        ActiveUsersTracker tracker = new ActiveUsersTracker();
//        tracker.start();
//    }

    public void start() {
        // Simulate like events (in a real scenario, these come from a stream)
//        ScheduledExecutorService eventGenerator = Executors.newSingleThreadScheduledExecutor();
//        eventGenerator.scheduleAtFixedRate(this::generateLikeEvent, 0, 30, TimeUnit.MILLISECONDS);

        // Compute active users every 30 seconds
        ScheduledExecutorService computationService = Executors.newSingleThreadScheduledExecutor();
        computationService.scheduleAtFixedRate(this::computeActiveUsers, 0, COMPUTE_FREQUENCY, TimeUnit.MILLISECONDS);
    }

    // Compute the most active users in the last 10 minutes
    private List<String> computeActiveUsers() {
        try {
            long currentTime = System.currentTimeMillis();

            if (likeEvents.isEmpty()) {
                return null;
            }

            // Remove events outside the sliding window
            while (!slidingWindow.isEmpty() && slidingWindow.peekFirst().timestamp < currentTime - TIME_WINDOW) {
                slidingWindow.pollFirst();
            }

            // Add new events to the sliding window
            int addedEvents = 0;
            while (!likeEvents.isEmpty()) {
                LikeEvent event = likeEvents.poll();
                if (event.timestamp >= currentTime - TIME_WINDOW) {
                    slidingWindow.addLast(event);
                    addedEvents++;
                }
            }

            // Count events per user in the sliding window
            Map<String, Long> userLikeCounts = slidingWindow.stream()
                .collect(Collectors.groupingBy(event -> event.userId, Collectors.counting()));

            // Find the most active users
            List<Map.Entry<String, Long>> topUsers = userLikeCounts.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(3) // Get the top 3 users
                .collect(Collectors.toList());

            // Output results
            current = printSingle(topUsers);
        } catch (Exception e) {
            System.err.println("Error in computeActiveUsers for type " + type + ": " + e.getMessage());
            e.printStackTrace();
        }
        return List.of();
    }

    public List<Result> printSingle(List<Entry<String, Long>> topUsers) {
//        System.out.println("Top " + type + " users in the last " + (TIME_WINDOW / 60000) + " minutes:");

        return topUsers.stream().map(entry ->
            new Result("https://bsky.app/profile/"+entry.getKey(), entry.getValue()))
            .collect(Collectors.toList());
    }

    @AllArgsConstructor
    @Getter
    // Like event class
    static class LikeEvent {
        String userId;
        long timestamp;
    }

    @AllArgsConstructor
    @Getter
    static class Result {
        String href;
        Long count;
    }
    static enum EventType {
        LIKE, BEING_FOLLOWED, FOLLOWING
    }
}
