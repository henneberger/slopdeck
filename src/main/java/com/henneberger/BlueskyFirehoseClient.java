package com.henneberger;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnicodeString;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.henneberger.ActiveUsersTracker.EventType;
import com.henneberger.ActiveUsersTracker.LikeEvent;
import com.henneberger.BlueskyDownloader.ClassifyResult;
import com.henneberger.JsonRecord.Block;
import com.henneberger.JsonRecord.Embed.Image;
import com.henneberger.SimpleWebServer.PostData;
import com.henneberger.SimpleWebServer.PostType;
import io.ipfs.cid.Cid;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.SneakyThrows;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.*;
import java.io.ByteArrayInputStream;

public class BlueskyFirehoseClient extends WebSocketClient {

  public static final String FIREHOSE_URI = "wss://bsky.network/xrpc/com.atproto.sync.subscribeRepos?cursor=962450003";

  private static final Random random = new Random();
  private static final BlockingQueue<Process> processQueue = new LinkedBlockingQueue<>();
  private static final int THREAD_POOL_SIZE = 7;
  private static final ExecutorService threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
  public static final String FLASK_SERVER_URL = "http://localhost:6000";

  private static final ObjectMapper mapper = new ObjectMapper();

  // Initialize a shared HttpClient
  public static final HttpClient httpClient = HttpClient.newHttpClient();

  public static final ActiveUsersTracker topLikes = new ActiveUsersTracker(EventType.LIKE);
  public static final ActiveUsersTracker topBeingFollowed = new ActiveUsersTracker(EventType.BEING_FOLLOWED);
  public static final ActiveUsersTracker topFollows = new ActiveUsersTracker(EventType.FOLLOWING);

  @SneakyThrows
  public BlueskyFirehoseClient() {
    super(toUri(FIREHOSE_URI));
    topLikes.start();
    Thread.sleep(100);
    topBeingFollowed.start();
    Thread.sleep(100);
    topFollows.start();
    // Start a thread to constantly process download requests
    Thread worker = new Thread(() -> {
      while (true) {
        try {
          // Take a download request from the queue
          Process request = processQueue.take();

          // Submit the download task to the thread pool
          threadPool.submit(() -> process(request.bytes));
        } catch (InterruptedException e) {
          System.err.println("Worker thread interrupted");
          Thread.currentThread().interrupt();
          break;
        }
      }
    });
    worker.start();
  }

  private static URI toUri(String firehoseUri) {
    try {
      return new URI(firehoseUri);
    } catch (Exception e) {
      return null;
    }
  }

  @Override
  public void onOpen(ServerHandshake handshakedata) {
    System.out.println("Connected to the Bluesky firehose.");
  }

  @Override
  public void onMessage(String s) {

  }

  static class Process {

    byte[] bytes;

    public Process(byte[] bytes) {
      this.bytes = bytes;
    }
  }

  @Override
  public void onMessage(ByteBuffer bytes) {
    byte[] byteArray = new byte[bytes.remaining()];
    bytes.get(byteArray);
    processQueue.add(new Process(byteArray));
  }

  public void process(byte[] byteArray) {
    try {
      CborDecoder cborDecoder = new CborDecoder(new ByteArrayInputStream(byteArray));
      List<DataItem> decode = cborDecoder.decode();
      String repo = getRepo(decode.get(1));

      Object items = convertCborToJava(decode.get(1), repo);

      String s = mapper.writeValueAsString(items);
//      System.out.println(s);

      JsonRecord jsonRecord = mapper.readValue(s, JsonRecord.class);

      if (jsonRecord.getBlocks() == null) {
        return;
      }
      if (jsonRecord.getOps().isEmpty()) {
        return;
      }

      for (Block block : jsonRecord.getBlocks()) {

        String path = jsonRecord.getOps().get(0).getPath();
        String uniquePath = path.substring(path.indexOf("/") + 1);
        String op = jsonRecord.getOps().get(0).getPath()
            .substring(0, jsonRecord.getOps().get(0).getPath().indexOf("/"));
        if (op.equals("app.bsky.feed.like")) {
          topLikes.likeEvents.add(new LikeEvent(jsonRecord.getRepo(),
              System.currentTimeMillis()));
          return;
        }
        if (op.equals("app.bsky.graph.follow")) {
          if (block.getSubject() instanceof String) {
            topBeingFollowed.likeEvents.add(new LikeEvent((String)block.getSubject(),
                System.currentTimeMillis()));
          }
          if (block.getDid() != null)  {
            topFollows.likeEvents.add(new LikeEvent((String)jsonRecord.getRepo(),
                System.currentTimeMillis()));
          }

        }

        if (!op.equalsIgnoreCase("app.bsky.feed.post")) {
          continue;
        }

        if (block.getEmbed() != null && block.getEmbed().getImages() != null && block.getReply() == null) {
          for (Image image : block.getEmbed().getImages()) {
            if (!image.getImage().getMimeType().equalsIgnoreCase("image/jpeg")){
              continue;
            }
            if (!jsonRecord.getOps().get(0).getAction().equals("create")) {
              continue;
            }


            CompletableFuture<ClassifyResult> doubleCompletableFuture = BlueskyDownloader.enqueueDownload(
                image.getImage().getRef(),
                jsonRecord.getRepo(), block.getText(), uniquePath, jsonRecord.getRepo());
            doubleCompletableFuture.thenAccept((x) -> {
              SimpleWebServer.postQueue.add(
                  new PostData(
                      PostType.IMAGE,
                      block.getText(),
                      x.getVector(),
                      String.format("https://bsky.app/profile/%s/post/%s",
                          jsonRecord.getRepo(), uniquePath),
                      String.format("https://cdn.bsky.app/img/feed_thumbnail/plain/%s/%s@jpeg",
                          jsonRecord.getRepo(), image.getImage().getRef()),
                      block.getCreatedAt(),
                      x.nsfw
                  )
              );
            });
            break;
          }
        } else if (random.nextBoolean() &&block.getText() != null && block.getText().length() > 30 && block.getReply() == null) {
          CompletableFuture<Double[]> doubleCompletableFuture = encodeLatentText(block.getText());
          doubleCompletableFuture.thenAccept((x) -> {
            SimpleWebServer.postQueue.add(
                new PostData(
                    PostType.TEXT,
                    block.getText(),
                    x,
                    String.format("https://bsky.app/profile/%s/post/%s",
                        jsonRecord.getRepo(), uniquePath),
                    null,
                    block.getCreatedAt(),
                    false
                )
            );
          });
        }
      }

    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private CompletableFuture<Double[]> encodeLatentText(String text) {
    return CompletableFuture.supplyAsync(() -> encodeLatentText(httpClient, text), threadPool);
  }

  private static Double[] encodeLatentText(HttpClient client, String text) {
    String flaskUrl = "http://127.0.0.1:6001/embed_text";

    try {
      String payload = mapper.writeValueAsString(Map.of("input_text", text));

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
        return ((List<List<Double>>) mapper.readValue(responseBody, Map.class)
            .get("latent_vector")).get(0).toArray(new Double[0]);

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
    return new Double[0];
  }

  private String getRepo(DataItem dataItem) {
    co.nstant.in.cbor.model.Map valueItem = (co.nstant.in.cbor.model.Map) dataItem;
    DataItem thisRepo = null;
    for (DataItem i : valueItem.getKeys()) {
      if (i instanceof UnicodeString && ((UnicodeString) i).getString().equalsIgnoreCase("repo")) {
        thisRepo = valueItem.get(i);
        break;
      }
    }

    UnicodeString unicodeString = (UnicodeString) thisRepo;

    if (unicodeString == null) {
      return null;
    }
    return unicodeString.getString();
  }

  @Override
  public void onClose(int code, String reason, boolean remote) {
    System.out.println("Disconnected from the firehose. Reason: " + reason);

    // Delay before reconnecting to prevent immediate reconnection loops
    Thread reconnectThread = new Thread(() -> {
      try {
        Thread.sleep(5000); // 5-second delay
        reconnect();
      } catch (InterruptedException e) {
        System.err.println("Reconnection attempt interrupted: " + e.getMessage());
        Thread.currentThread().interrupt();
      }
    });
    reconnectThread.start();
  }

  @Override
  public void onError(Exception ex) {
    System.err.println("WebSocket error: " + ex.getMessage());
    ex.printStackTrace();
  }

  public static void main(String[] args) {
    try {
      BlueskyFirehoseClient client = new BlueskyFirehoseClient();
      client.connectBlocking();
    } catch (Exception e) {
      System.err.println("Failed to connect to the Bluesky firehose: " + e.getMessage());
      e.printStackTrace();
    }
  }

  private Object convertCborToJava(DataItem item, String repo) {
    if (item == null) {
      return null;
    }
    switch (item.getMajorType()) {
      case UNSIGNED_INTEGER:
      case NEGATIVE_INTEGER:
        return ((co.nstant.in.cbor.model.Number) item).getValue();
      case BYTE_STRING:

        byte[] bytes = ((ByteString) item).getBytes();
        // Return a hex representation or handle as needed
        return null;
      case UNICODE_STRING:
        return ((co.nstant.in.cbor.model.UnicodeString) item).getString();
      case ARRAY:
        List<Object> list = new ArrayList<>();
        for (DataItem dataItem : ((Array) item).getDataItems()) {
          list.add(convertCborToJava(dataItem, repo));
        }
        return list;
      case MAP:
        Map<String, Object> map = new HashMap<>();
        co.nstant.in.cbor.model.Map cborMap = (co.nstant.in.cbor.model.Map) item;
        for (DataItem keyItem : cborMap.getKeys()) {
          String key = ((co.nstant.in.cbor.model.UnicodeString) keyItem).getString();
          Object value;
          if (key.equalsIgnoreCase("ref")) {
            DataItem valueItem = cborMap.get(keyItem);
            byte[] blocks = ((ByteString) valueItem).getBytes();
            Cid cid = decodeCid(blocks);
            value = cid.toString();
//            BlueskyDownloader.enqueueDownload(cid.toString(), repo, "");

          } else if (key.equalsIgnoreCase("cid") && cborMap.get(keyItem) instanceof ByteString) {
            DataItem valueItem = cborMap.get(keyItem);
            byte[] blocks = ((ByteString) valueItem).getBytes();
            Cid cid = decodeCid(blocks);
            value = cid.toString();
          } else if (key.equalsIgnoreCase("blocks")) {
            DataItem valueItem = cborMap.get(keyItem);
            if (valueItem instanceof Array) {
              value = convertCborToJava(valueItem, repo);
            } else {
              byte[] blocks = ((ByteString) valueItem).getBytes();
              value = decodeByteString(blocks, repo);
            }
          } else if (key.equalsIgnoreCase("external")) {
            co.nstant.in.cbor.model.Map valueItem = (co.nstant.in.cbor.model.Map) cborMap.get(
                keyItem);
            DataItem thisUri = null;
            for (DataItem i : valueItem.getKeys()) {
              if (i instanceof UnicodeString && ((UnicodeString) i).getString()
                  .equalsIgnoreCase("uri")) {
                thisUri = valueItem.get(i);
                break;
              }
            }

            UnicodeString unicodeString = (UnicodeString) thisUri;
            value = convertCborToJava(valueItem, repo);
          } else {
            DataItem valueItem = cborMap.get(keyItem);
            value = convertCborToJava(valueItem, repo);
          }
          map.put(key, value);
        }
        return map;
      case SPECIAL:
        if (item == SimpleValue.NULL) {
          return null;
        }
        if (item == SimpleValue.TRUE) {
          return true;
        }
        if (item == SimpleValue.FALSE) {
          return false;
        }
        break;
      default:
        break;
    }
    return null;
  }

  private Cid decodeCid(byte[] blocks) {
    Integer offset = readLEB128(blocks, 0).getValue();
    final int CID_LENGTH = 46;
    byte[] cidBytes = Arrays.copyOfRange(blocks, offset, offset + CID_LENGTH);
    byte[] cidLengthByte = new byte[1];
    cidLengthByte[0] = cidBytes[3];
    int cidLength = readLEB128(cidLengthByte, 0).getKey() + 4;
    byte[] cidBytes2 = Arrays.copyOfRange(cidBytes, 0, cidLength);
    byte[] cidBytes3 = Arrays.copyOfRange(blocks, offset, offset + CID_LENGTH);
    Cid cid = Cid.cast(cidBytes2);

    return cid;
  }

  private Object decodeByteString(byte[] blocks, String repo) {
    java.util.Map.Entry<Integer, Integer> headerLengthEntry = readLEB128(blocks, 0);

    int offset = headerLengthEntry.getValue();
    int headerLength = headerLengthEntry.getKey();

    byte[] headerBytes = Arrays.copyOfRange(blocks, offset, offset + headerLength);
    List<Object> collect = new ArrayList<>();

    offset += headerLength;
    while (offset < blocks.length) {
      java.util.Map.Entry<Integer, Integer> nextDataLengthPair = readLEB128(blocks, offset);
      offset += nextDataLengthPair.getValue();
      int dataLength = nextDataLengthPair.getKey();
      final int CID_LENGTH = 46;
      byte[] cidBytes = Arrays.copyOfRange(blocks, offset, offset + CID_LENGTH);
      byte[] cidLengthByte = new byte[1];
      cidLengthByte[0] = cidBytes[3];
      int cidLength = readLEB128(cidLengthByte, 0).getKey() + 4;
      Cid cid = Cid.cast(cidBytes);

      if (cid.codec != Cid.Codec.DagCbor) {
        continue;
      }

      offset += cidLength;
      byte[] dataBytes = Arrays.copyOfRange(blocks, offset, offset + dataLength - cidLength);

      offset += dataLength - cidLength;

      CborDecoder dataDecoder = new CborDecoder(new ByteArrayInputStream(dataBytes));
      try {
        List data = dataDecoder.decode().stream()
            .map(i -> convertCborToJava(i, repo))
            .collect(Collectors.toList());
        collect.addAll(data);
      } catch (CborException e) {
        throw new RuntimeException(e);
      }
    }

    return collect;
  }

  java.util.Map.Entry<Integer, Integer> readLEB128(byte[] bytes, int offset) {
    int length = 0;
    int extraOffset;
    long shift = 0;
    for (extraOffset = 0; extraOffset < bytes.length; extraOffset++) {
      int totalOffset = extraOffset + offset;
      byte b = bytes[totalOffset];

      // LEB128 stores 7 bits per byte, so we need to mask out the high bit
      length |= (long) (b & 0x7F) << shift;

      // If the MSB is not set, the number has ended
      if ((b & 0x80) == 0) {
        break;
      }

      // Otherwise, move to the next 7 bits
      shift += 7;
    }

    return new AbstractMap.SimpleEntry<>(length, extraOffset + 1);
  }
}

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
class JsonRecord {

  private List<Object> blobs;
  private boolean rebase;
  private String rev;
  private List<Operation> ops;
  private List<Block> blocks;
  private String prev;
  private String repo;
  private String commit;
  private String time;
  private boolean tooBig;
  private long seq;
  private String since;
  private String handle; // Added support for "handle"

  @JsonAnySetter
  private Map<String, Object> additionalFields = new HashMap<>();

  @Data
  public static class Operation {

    private String path;
    private String action;
    private String cid;
  }

  @Data
  public static class Block {

    @JsonIgnore
    private List<Element> e;
    private String l;
    private String sig;
    private String rev;
    private String data;
    private String prev;
    private Integer version;
    private String did;
    private String createdAt;
    private List<String> langs;
    private String text;
    private Embed embed;
    private Reply reply;

    @JsonDeserialize(using = SubjectDeserializer.class)
    private Object subject;

    @JsonProperty("$type")
    private String type;

    @JsonAnySetter
    private Map<String, Object> additionalFields = new HashMap<>();

    @Data
    public static class Element {

      @JsonIgnore
      private int p;
      @JsonIgnore
      private String t;
      @JsonIgnore
      private String v;
      @JsonIgnore
      private String k;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Subject {

      private String uri;
      private String cid;

      @JsonProperty("$type")
      private String type;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Reply {

      private Parent parent;
      private Root root;

      @Data
      @JsonIgnoreProperties(ignoreUnknown = true)
      public static class Parent {

        private String uri;
        private String cid;
      }

      @Data
      @JsonIgnoreProperties(ignoreUnknown = true)
      public static class Root {

        private String uri;
        private String cid;
      }
    }
  }

  @Data
  public static class Embed {

    private External external;
    private List<Image> images;
    private Record record;

    @JsonProperty("$type")
    private String type;

    @JsonAnySetter
    private Map<String, Object> additionalFields = new HashMap<>();

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class External {

      private Thumb thumb;
      private String description;
      private String title;
      private String uri;

      @JsonProperty("$type")
      private String type;

      @Data
      public static class Thumb {

        private String ref;
        private long size;
        private String mimeType;

        @JsonProperty("$type")
        private String type;
      }
    }

    @Data
    public static class Image {

      private ImageDetail image;
      private String alt; // Added support for "alt"

      @JsonAnySetter
      private Map<String, Object> additionalFields = new HashMap<>();

      @Data
      public static class ImageDetail {

        private String ref;
        private long size;
        private String mimeType;

        @JsonProperty("$type")
        private String type;
      }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Record {

      private String uri;
      private String cid;

      @JsonProperty("$type")
      private String type;
    }

  }
}
