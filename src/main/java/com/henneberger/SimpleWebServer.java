package com.henneberger;

import static com.henneberger.BlueskyFirehoseClient.FLASK_SERVER_URL;
import static com.henneberger.BlueskyFirehoseClient.httpClient;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.*;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.SneakyThrows;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/**
 * SimpleWebServer.java
 *
 * A simple Java 11 web server that serves HTTP requests for the frontend and handles WebSocket connections.
 * Each WebSocket connection can set its own text filter, and images are streamed to clients based on cosine similarity.
 *
 * Dependencies:
 * - Java-WebSocket library (https://github.com/TooTallNate/Java-WebSocket)
 *
 * Compilation:
 * javac -cp Java-WebSocket-1.5.3.jar SimpleWebServer.java
 *
 * Execution:
 * java -cp .:Java-WebSocket-1.5.3.jar SimpleWebServer
 *
 * Note: Replace `:` with `;` on Windows.
 */
public class SimpleWebServer {

    // Configuration
    private static final int HTTP_PORT = 80;
    private static final int WS_PORT = 8086;
    private static final double IMAGE_SIMILARITY_THRESHOLD = 0.26;
    private static final double TEXT_SIMILARITY_THRESHOLD = 0.90;
    private static final int MESSAGE_HISTORY_LIMIT = 50000; // New: Limit for message history
    private static final int MAX_MESSAGES = 50;

    private static final ObjectMapper mapper = new ObjectMapper();
    // BlockingQueue for ImageData
    public static final BlockingQueue<PostData> postQueue = new LinkedBlockingQueue<>();

    // List to hold active WebSocket clients
    private final CopyOnWriteArrayList<ClientSession> clients = new CopyOnWriteArrayList<>();

    // New: Deque to store the last 1000 messages
    private final Deque<PostData> messageHistory = new ConcurrentLinkedDeque<>();

    public static void main(String[] args) throws Exception {
        SimpleWebServer server = new SimpleWebServer();
        server.startServers();
    }

    /**
     * Starts the HTTP and WebSocket servers and initializes the image producer and consumer.
     */
    public void startServers() throws Exception {
        // Start HTTP Server
        Thread httpThread = new Thread(this::startHttpServer);
        httpThread.start();

        // Start WebSocket Server
        ImageWebSocketServer wsServer = new ImageWebSocketServer(new InetSocketAddress(WS_PORT));
        wsServer.start();

        System.out.println("WebSocket Server started on port: " + WS_PORT);

        // Start Image Consumer
        Thread consumerThread = new Thread(this::processImageQueue);
        consumerThread.start();

        // Start Image Producer (Simulate incoming images)
        Thread producerThread = new Thread(this::runProduction);
        producerThread.start();
    }

    /**
     * Starts a simple HTTP server to serve static frontend files.
     */
    private void startHttpServer() {
        try (ServerSocket serverSocket = new ServerSocket(HTTP_PORT)) {
            System.out.println("HTTP Server started on port: " + HTTP_PORT);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                Thread handler = new Thread(() -> handleHttpRequest(clientSocket));
                handler.start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Handles incoming HTTP requests and serves static files.
     *
     * @param clientSocket The client's socket.
     */
    private void handleHttpRequest(Socket clientSocket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            OutputStream out = clientSocket.getOutputStream()) {

            // Read the HTTP request
            String line = in.readLine();
            if (line == null || line.isEmpty()) {
                return;
            }

            StringTokenizer tokenizer = new StringTokenizer(line);
            String method = tokenizer.nextToken();
            String path = tokenizer.nextToken();

//            System.out.println("Received HTTP request: Method=" + method + ", Path=" + path);

            // Only handle GET requests
            if (!method.equals("GET")) {
                sendResponse(out, "501 Not Implemented", "text/plain", "Method Not Implemented");
                System.out.println("Responded with 501 Not Implemented");
                return;
            }

            // Map the path to a file
            if (path.equals("/")) {
                path = "/index.html";
            }

            // Prevent directory traversal attacks
            if (path.contains("..")) {
                sendResponse(out, "403 Forbidden", "text/plain", "Forbidden");
                System.out.println("Responded with 403 Forbidden");
                return;
            }

            // Load the requested file from the resources
            InputStream fileStream = getClass().getResourceAsStream(path);
            if (fileStream == null) {
                sendResponse(out, "404 Not Found", "text/plain", "Not Found");
                System.out.println("Responded with 404 Not Found for path: " + path);
                return;
            }

            // Determine the content type
            String contentType = "text/plain";
            if (path.endsWith(".html")) {
                contentType = "text/html";
            } else if (path.endsWith(".css")) {
                contentType = "text/css";
            } else if (path.endsWith(".js")) {
                contentType = "application/javascript";
            } else if (path.endsWith(".png")) {
                contentType = "image/png";
            } else if (path.endsWith(".jpg") || path.endsWith(".jpeg")) {
                contentType = "image/jpeg";
            }

            // Read the file content
            byte[] content = fileStream.readAllBytes();

            // Send the HTTP response
            sendResponse(out, "200 OK", contentType, content);
//            System.out.println("Served file: " + path + " with Content-Type: " + contentType);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Sends an HTTP response with the given status, content type, and content.
     *
     * @param out         The output stream.
     * @param status      The HTTP status.
     * @param contentType The content type.
     * @param content     The response content as a string.
     * @throws IOException If an I/O error occurs.
     */
    private void sendResponse(OutputStream out, String status, String contentType, String content) throws IOException {
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        sendResponse(out, status, contentType, contentBytes);
    }

    /**
     * Sends an HTTP response with the given status, content type, and binary content.
     *
     * @param out         The output stream.
     * @param status      The HTTP status.
     * @param contentType The content type.
     * @param content     The response content as bytes.
     * @throws IOException If an I/O error occurs.
     */
    private void sendResponse(OutputStream out, String status, String contentType, byte[] content) throws IOException {
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        writer.write("HTTP/1.1 " + status + "\r\n");
        writer.write("Content-Type: " + contentType + "\r\n");
        writer.write("Content-Length: " + content.length + "\r\n");
        writer.write("Connection: close\r\n");
        writer.write("\r\n");
        writer.flush();
        out.write(content);
        out.flush();
    }

    /**
     * Simulates the production of image data by periodically adding new ImageData to the queue.
     */
    @SneakyThrows
    private void runProduction() {
        BlueskyFirehoseClient.main(null);
    }

    /**
     * Processes the image queue by broadcasting image URLs to clients based on cosine similarity.
     * Also stores the message in history.
     */
    private void processImageQueue() {
        while (true) {
            try {
                PostData postData = postQueue.take();
                broadcastImage(postData);
                addToMessageHistory(postData); // New: Add to message history
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Adds a PostData to the message history, ensuring the history size does not exceed the limit.
     *
     * @param postData The PostData to add.
     */
    private void addToMessageHistory(PostData postData) {
        messageHistory.addLast(postData);
        if (messageHistory.size() > MESSAGE_HISTORY_LIMIT) {
            messageHistory.pollFirst();
        }
    }

    /**
     * Broadcasts the PostData to all clients based on their filter vectors.
     *
     * @param postData The PostData to broadcast.
     */
    private void broadcastImage(PostData postData) {
        for (ClientSession client : clients) {
            if (client.filterVector != null) {
                double similarity = cosineSimilarity(postData.latentVector, client.filterVector);

                if (postData.type == PostType.IMAGE && similarity > IMAGE_SIMILARITY_THRESHOLD) {
                    client.sendPost(postData);
                } else if (postData.type == PostType.TEXT && similarity > TEXT_SIMILARITY_THRESHOLD) {
                    client.sendPost(postData);
                }
            }
        }
    }

    /**
     * Computes the cosine similarity between two vectors.
     *
     * @param vec1 First vector.
     * @param vec2 Second vector.
     * @return Cosine similarity.
     */
    private double cosineSimilarity(Double[] vec1, Double[] vec2) {
        if (vec1.length != vec2.length) {
//            System.err.println("Cosine similarity error: Vectors must be of same length.");
            return 0.0;
        }
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for(int i=0; i<vec1.length; i++) {
            dotProduct += vec1[i] * vec2[i];
            normA += Math.pow(vec1[i], 2);
            normB += Math.pow(vec2[i], 2);
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * Represents an image with a latent vector and its URL.
     */
    @AllArgsConstructor
    @Getter
    public static class PostData {
        PostType type;
        String text;
        @JsonIgnore
        Double[] latentVector;
        String hrefUrl;
        String imageUrl;
        String createdAt;
        boolean nsfw;
    }


    public static enum PostType {
        IMAGE, TEXT
    }
    /**
     * Represents a connected WebSocket client.
     */
    private class ClientSession {
        WebSocket conn;
        Double[] filterVector;

        ClientSession(WebSocket conn) {
            this.conn = conn;
        }

        /**
         * Sends an image URL to the client in JSON format.
         */
        void sendPost(PostData postData) {
            try {
                String message = mapper.writeValueAsString(postData);
                conn.send(message);
//                System.out.println("Sent image to client " + conn.getRemoteSocketAddress() + ": " + imageUrl);
            } catch (Exception e) {
                System.err.println("Failed to send message to " + conn.getRemoteSocketAddress() + ": " + e.getMessage());
            }
        }
    }

    /**
     * WebSocket Server implementation using Java-WebSocket library.
     */
    private class ImageWebSocketServer extends WebSocketServer {

        private final EmbedService embedService = new EmbedService();

        ImageWebSocketServer(InetSocketAddress address) {
            super(address);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            System.out.println("New WebSocket connection from " + conn.getRemoteSocketAddress());
            clients.add(new ClientSession(conn));
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            System.out.println("WebSocket connection closed: " + conn.getRemoteSocketAddress() + ". Code: " + code + ", Reason: " + reason);
            clients.removeIf(client -> client.conn.equals(conn));
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            System.out.println("Received message from " + conn.getRemoteSocketAddress() + ": " + message);
            // Expecting JSON message with a "text" field
            try {
                Map<String, String> msgMap = mapper.readValue(message, Map.class);
                String text = msgMap.get("text");
                if (text != null && !text.isEmpty()) {
                    Double[] latentVector = embedService.callEmbedTextEndpoint(text);
                    // Update the client's filter vector
                    ClientSession targetClient = null;
                    for (ClientSession client : clients) {
                        if (client.conn.equals(conn)) {
                            client.filterVector = latentVector;
                            targetClient = client;
                            System.out.println("Updated filter for client " + conn.getRemoteSocketAddress() + ": " + text);
                            break;
                        }
                    }
                    // After setting the filter vector, send historical messages
                    if (targetClient != null) {
                        sendHistoricalMessagesToClient(targetClient);
                    }
                } else {
                    System.out.println("Received empty or invalid filter text from " + conn.getRemoteSocketAddress());
                }
            } catch (Exception e) {
                System.err.println("Failed to parse message from " + conn.getRemoteSocketAddress() + ": " + e.getMessage());
                conn.close(1003, "Invalid message format");
            }
        }

        @Override
        public void onMessage(WebSocket conn, ByteBuffer message) {
            // Not handling binary messages
            System.out.println("Received binary message from " + conn.getRemoteSocketAddress() + ", ignoring.");
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            System.err.println("WebSocket error from " + (conn != null ? conn.getRemoteSocketAddress() : "Unknown") + ": " + ex.getMessage());
        }

        @Override
        public void onStart() {
            System.out.println("WebSocket Server started successfully.");
            setConnectionLostTimeout(0);
            setConnectionLostTimeout(100);
        }

        private void sendHistoricalMessagesToClient(ClientSession client) {
            if (client.filterVector == null || messageHistory.isEmpty()) {
                return;
            }

            List<PostData> recentMessages = new ArrayList<>(MAX_MESSAGES);
            Iterator<PostData> descendingIterator = messageHistory.descendingIterator();
            int count = 0;

            // Coallect the most recent n messages
            while (descendingIterator.hasNext() && count < MAX_MESSAGES) {
                PostData post = descendingIterator.next();
                double similarity = cosineSimilarity(post.latentVector, client.filterVector);

                if (post.type == PostType.IMAGE && similarity > IMAGE_SIMILARITY_THRESHOLD) {
                    count++;
                    recentMessages.add(post);
                } else if (post.type == PostType.TEXT && similarity > TEXT_SIMILARITY_THRESHOLD) {
                    count++;
                    recentMessages.add(post);
                }
            }

            Collections.reverse(recentMessages);

            for (PostData post : recentMessages) {
                client.sendPost(post);
            }
        }

    }

    /**
     * Simulates calling an external embedding service to convert text to a latent vector.
     * Replace this with actual API calls as needed.
     */
    private class EmbedService {

        /**
         * Mock implementation of callEmbedTextEndpoint.
         *
         * @param text The input text.
         * @return A latent vector representing the text.
         */


        /**
         * Calls the /embed_text endpoint with the provided text.
         *
         * @param text    The text to embed.
         */
        private Double[] callEmbedTextEndpoint(String text) {
            try {
                // Construct the JSON payload
                Map<String, String> payload = new HashMap<>();
                payload.put("input_text", text);
                String jsonPayload = mapper.writeValueAsString(payload);

                // Build the HTTP request
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:6001/embed_text"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

                // Send the request and get the response
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                // Handle the response
                if (response.statusCode() == 200) {
                    // Parse the response JSON
                    Map<String, Object> responseBody = mapper.readValue(response.body(), Map.class);
                    List<List<Double>> latentVectorList = (List<List<Double>>) responseBody.get("latent_vector");
                    if (latentVectorList != null && !latentVectorList.isEmpty()) {
                        List<Double> latentVectorInner = latentVectorList.get(0);
                        return latentVectorInner.toArray(new Double[0]);
                    } else {
                        System.err.println("Invalid latent_vector format in response.");
                    }
                } else {
                    System.err.println("Failed to get text embedding. Status Code: " + response.statusCode());
                    System.err.println("Response Body: " + response.body());
                    System.err.println("Response text: " + text);
                }
            } catch (Exception e) {
                System.err.println("Error while calling /embed_text endpoint: " + e.getMessage());
                e.printStackTrace();
            }
            return new Double[0];
        }
    }
}
