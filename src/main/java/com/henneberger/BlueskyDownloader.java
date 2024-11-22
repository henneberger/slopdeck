package com.henneberger;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.SneakyThrows;

public class BlueskyDownloader {

    private static final int INITIAL_PERMITS_PER_SECOND = 20; // Derived from 3000/300
    private static final RateLimiter rateLimiter = new RateLimiter(INITIAL_PERMITS_PER_SECOND);
    private static final int THREAD_POOL_SIZE = 8; // Control parallelism
    private static final int DOWNLOAD_DELAY_SECONDS = 10;
    private static final AtomicBoolean wait = new AtomicBoolean(false);

    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
        THREAD_POOL_SIZE);
    private static final ExecutorService downloadExecutor = Executors.newFixedThreadPool(
        THREAD_POOL_SIZE);
    private static final ConcurrentLinkedQueue<DownloadRequest> queue = new ConcurrentLinkedQueue<>();

    /**
     * Enqueues a download task with a 5-second delay. This method is non-blocking.
     */
    public static CompletableFuture<ClassifyResult> enqueueDownload(String cid, String did, String token,
        String path, String repo) {
        DownloadRequest request = new DownloadRequest(cid, did, token, path, repo);
        CompletableFuture<ClassifyResult> similarityFuture = new CompletableFuture<>();

        if (queue.size() > 200 || wait.get()) {
            similarityFuture.completeExceptionally(new IllegalStateException("Queue is full or rate-limited."));
            return similarityFuture;
        }
        // Add to the queue
        queue.add(request);

        // Schedule the download after the required delay

        // Schedule the download after the required delay
        scheduler.schedule(() -> {
            downloadExecutor.submit(() -> {
                try {
                    // Acquire a permit before proceeding
//                    rateLimiter.acquire();

                    if (wait.get()) {
                        System.out.println("waiting");
                        Thread.sleep(5000);
                    }
                    ClassifyResult similarity = download(request);
                    similarityFuture.complete(similarity);
                } catch (Exception e) {
                    similarityFuture.completeExceptionally(e);
                    System.err.println("Download task failed for: " + request);
                    e.printStackTrace();
                } finally {
                    // Remove from the queue after processing
                    queue.remove(request);
                }
            });
        }, DOWNLOAD_DELAY_SECONDS, TimeUnit.SECONDS);
//        System.out.println("Download request added. Current queue size: " + queue.size());

        return similarityFuture;
    }

    /**
     * Handles the actual download logic.
     */
    public static ClassifyResult download(DownloadRequest request) {
        String apiUrl =
            "https://bsky.social/xrpc/com.atproto.sync.getBlob?did=" + request.did + "&cid="
                + request.cid;

        try {
            // Create the HttpClient with redirect policy
            HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();

            // Build the HTTP request with authorization header if token is provided
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(new URI(apiUrl))
                .GET();

            HttpRequest httpRequest = requestBuilder.build();

            // Send the request and handle the response
            HttpResponse<byte[]> response = client.send(httpRequest,
                HttpResponse.BodyHandlers.ofByteArray());

            // Parse rate limit headers
            parseAndUpdateRateLimit(response);

            if (response.statusCode() == 200) {
                // Save the downloaded content to a file
                File outputFile = new File("images/" +
                    request.path + "$" + request.repo + ".jpeg");
                try {
                    outputFile.getParentFile().mkdirs(); // Ensure the directory exists
                    try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                        fos.write(response.body());
                    }
                    return getSimilarity(client, outputFile);
                } finally {
                    boolean delete = outputFile.delete();
                }
//                System.out.println("Content downloaded successfully: " + outputFile.getAbsolutePath());
            } else {
                System.out.println("https://bsky.app/profile/" + request.repo + "/post/" + request.path
                );
                System.err.println(
                    "Failed to download content. HTTP Response Code: " + response.statusCode());
                System.err.println("Response Body: " + new String(response.body()));
                System.err.println("Text: " + request.token);
                if (response.statusCode() == 429) { // Too Many Requests
                    handleRateLimitExceeded(response);
                }
            }
        } catch (Exception e) {
            System.err.println("Error downloading content for request: " + request);
            e.printStackTrace();

        }
        return ClassifyResult.NONE;
    }
    /**
     * Parses rate limit headers from the response and updates the RateLimiter accordingly.
     */
    private static void parseAndUpdateRateLimit(HttpResponse<?> response) {
        String limitStr = response.headers().firstValue("ratelimit-limit").orElse("3000");
        String remainingStr = response.headers().firstValue("ratelimit-remaining").orElse("3000");
        String resetStr = response.headers().firstValue("ratelimit-reset").orElse("");

        try {
            int limit = Integer.parseInt(limitStr);
            int remaining = Integer.parseInt(remainingStr);
            long resetEpoch = resetStr.isEmpty() ? 0 : Long.parseLong(resetStr);

            long currentEpoch = Instant.now().getEpochSecond();
            long windowSeconds = resetEpoch > currentEpoch ? resetEpoch - currentEpoch : 300;

            if (windowSeconds > 0) {
                int permitsPerSecond = Math.max(1, remaining / (int) windowSeconds);
                rateLimiter.updateRate(permitsPerSecond + 50);
            }
        } catch (NumberFormatException e) {
            System.err.println("Invalid rate limit headers: " + e.getMessage());
        }
    }

    /**
     * Handles rate limit exceeded by pausing operations until reset time.
     */
    private static void handleRateLimitExceeded(HttpResponse<?> response) {
        String resetStr = response.headers().firstValue("ratelimit-reset").orElse("");
        if (!resetStr.isEmpty()) {
            try {
                long resetEpoch = Long.parseLong(resetStr);
                long currentEpoch = Instant.now().getEpochSecond();
                long waitSeconds = resetEpoch - currentEpoch;
                if (waitSeconds > 0) {
                    System.out.println("Rate limit exceeded. Pausing for " + waitSeconds + " seconds.");
                    wait.set(true);
                    Executors.newSingleThreadScheduledExecutor().schedule(() -> {
                        wait.set(false);
                        System.out.println("Resuming downloads after rate limit reset.");
                    }, waitSeconds, TimeUnit.SECONDS);
                }
            } catch (NumberFormatException e) {
                System.err.println("Invalid ratelimit-reset value: " + resetStr);
            }
        }
    }
    /**
     * Calls the Flask `/embed` endpoint to check similarity.
     */
    private static ClassifyResult getSimilarity(HttpClient client, File imageFile) {
        String flaskUrl = "http://127.0.0.1:6000/embed_image_vector";
        try {
            String payload = String.format(
                "{\"image_path\": \"%s\"}",
                imageFile.getAbsolutePath()
            );

            HttpRequest flaskRequest = HttpRequest.newBuilder()
                .uri(new URI(flaskUrl))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .header("Content-Type", "application/json")
                .build();

            HttpResponse<String> flaskResponse = client.send(flaskRequest,
                HttpResponse.BodyHandlers.ofString());

            if (flaskResponse.statusCode() == 200) {
                // Parse JSON response
                String responseBody = flaskResponse.body();
                ClassifyResult similarity = extractSimilarity(responseBody);
                return similarity;
            } else {
                System.err.println("Error calling Flask endpoint. HTTP Response Code: "
                    + flaskResponse.statusCode());
                System.err.println("Response Body: " + flaskResponse.body());
            }
        } catch (Exception e) {
            System.err.println(
                "Error calling Flask endpoint for similarity check: " + e.getMessage());
            e.printStackTrace();
        }
        return ClassifyResult.NONE;
    }

    @AllArgsConstructor
    @Getter
    public static class ClassifyResult {

        public static final ClassifyResult NONE = new ClassifyResult(new Double[0], false);
        Double[] vector;
        boolean nsfw;
    }
   static final ObjectMapper mapper = new ObjectMapper();
    /**
     * Extracts the similarity score from the Flask endpoint's JSON response.
     */
    @SneakyThrows
    private static ClassifyResult extractSimilarity(String responseBody) {
        Map map = mapper.readValue(responseBody, Map.class);
        return new ClassifyResult( ((List<List<Double>>) map
            .get("latent_vector")).get(0).toArray(new Double[0]),
            (boolean)map.get("nsfw"));
    }

    /**
     * Represents a single download request.
     */
    private static class DownloadRequest {

        final String cid;
        final String did;
        final String token;
        private final String path;
        private final String repo;

        public DownloadRequest(String cid, String did, String token, String path, String repo) {
            this.cid = cid;
            this.did = did;
            this.token = token;
            this.path = path;
            this.repo = repo;
        }

        @Override
        public String toString() {
            return "DownloadRequest{cid='" + cid + "', did='" + did + "', token='" + token + "'}";
        }
    }
}

/**
 * Custom RateLimiter that manages the rate of requests based on permits per second.
 */
class RateLimiter {
    private int permitsPerSecond;
    private int availablePermits;
    private final ScheduledExecutorService scheduler;
    private final Object lock = new Object();

    public RateLimiter(int permitsPerSecond) {
        this.permitsPerSecond = permitsPerSecond;
        this.availablePermits = permitsPerSecond;
        this.scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(this::refill, 1, 1, TimeUnit.SECONDS);
    }

    private void refill() {
        synchronized (lock) {
            availablePermits = Math.min(permitsPerSecond, availablePermits + permitsPerSecond);
            lock.notifyAll();
        }
    }

    public void acquire() throws InterruptedException {
        synchronized (lock) {
            while (availablePermits <= 0) {
                lock.wait();
            }
            availablePermits--;
        }
    }

    public void updateRate(int newPermitsPerSecond) {
        synchronized (lock) {
            this.permitsPerSecond = newPermitsPerSecond;
            if (availablePermits > permitsPerSecond) {
                availablePermits = permitsPerSecond;
            }
            lock.notifyAll();
        }
    }
}
