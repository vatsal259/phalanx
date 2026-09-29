package phalanx;

import org.json.JSONArray;
import org.json.JSONObject;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public final class Broker {
  private static final Object BRIDGE = new Object();
  private static Socket bridgeSocket;
  private static BufferedReader bridgeReader;
  private static PrintWriter bridgeWriter;
  private static LoginStore.Login saved;

  public static void start() throws Exception {
    if (GraphicsEnvironment.isHeadless()) {
      System.err.println("The broker needs a desktop session for the Allow dialog.");
      return;
    }
    saved = LoginStore.load();
    installNativeHost();
    acceptExtension();
    HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), Config.HTTP_PORT), 0);
    server.createContext("/", Broker::handle);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    server.setExecutor(pool);
    server.start();
    System.err.println("Broker ready at http://localhost:" + Config.HTTP_PORT + "/login.html");
    System.err.println("In another terminal, from this folder: java -jar target/phalanx.jar agent");
    Thread.currentThread().join();
  }

  private static void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String method = exchange.getRequestMethod();
    try {
      if ("GET".equals(method) && "/login.html".equals(path)) {
        byte[] page = classpath("login.html");
        send(exchange, 200, "text/html; charset=utf-8", page);
      } else if ("POST".equals(method) && "/login".equals(path)) {
        acceptPostedLogin(exchange);
      } else if ("POST".equals(method) && "/signin".equals(path)) {
        signIn(exchange);
      } else {
        send(exchange, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8));
      }
    } catch (Exception exception) {
      exception.printStackTrace();
      if (!exchange.getResponseHeaders().isEmpty() || exchange.getResponseCode() != -1) {
        return;
      }
      status(exchange, "denied");
    }
  }

  private static void signIn(HttpExchange exchange) throws Exception {
    JSONObject request = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    String problem = TokenCheck.problem(request.optString("token"), request.optString("site"), saved.site());
    if (problem != null) {
      System.err.println(problem);
      status(exchange, "denied");
      return;
    }
    if (!approved(request.optString("reason"))) {
      System.err.println("user denied");
      status(exchange, "denied");
      return;
    }
    String filled = tellExtensionToFill();
    if (!"success".equals(filled)) {
      System.err.println(filled);
    }
    status(exchange, filled);
  }

  private static boolean approved(String reason) throws Exception {
    int[] choice = {-1};
    SwingUtilities.invokeAndWait(() -> {
      JFrame frame = new JFrame("Phalanx");
      frame.setAlwaysOnTop(true);
      frame.setUndecorated(true);
      frame.setLocationRelativeTo(null);
      frame.setVisible(true);
      Object[] options = {"Allow", "Deny"};
      choice[0] = JOptionPane.showOptionDialog(
          frame,
          "An agent wants to sign in.\n\nSite: " + saved.site() + "\nReason: " + reason + "\nUsername: " + saved.username(),
          "Phalanx",
          JOptionPane.DEFAULT_OPTION,
          JOptionPane.QUESTION_MESSAGE,
          null,
          options,
          options[1]);
      frame.dispose();
    });
    return choice[0] == 0;
  }

  private static String tellExtensionToFill() throws IOException {
    JSONObject fill = new JSONObject();
    fill.put("type", "fill");
    fill.put("username", saved.username());
    fill.put("password", saved.password());
    fill.put("site", saved.site());
    synchronized (BRIDGE) {
      long deadline = System.currentTimeMillis() + 15_000;
      while (bridgeWriter == null && System.currentTimeMillis() < deadline) {
        try {
          BRIDGE.wait(Math.max(1, deadline - System.currentTimeMillis()));
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          return "fill_failed";
        }
      }
      if (bridgeWriter == null) {
        System.err.println("extension is not connected");
        return "fill_failed";
      }
      bridgeSocket.setSoTimeout(20_000);
      bridgeWriter.println(fill.toString());
      String line;
      try {
        line = bridgeReader.readLine();
      } catch (IOException exception) {
        System.err.println("extension did not answer");
        return "fill_failed";
      }
      if (line == null) {
        return "fill_failed";
      }
      String result = new JSONObject(line).optString("status", "fill_failed");
      if ("success".equals(result) || "url_mismatch".equals(result) || "fill_failed".equals(result)) {
        return result;
      }
      return "fill_failed";
    }
  }

  private static void acceptPostedLogin(HttpExchange exchange) throws IOException {
    Map<String, String> fields = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    boolean usernameMatches = saved.username().equals(fields.get("username"));
    boolean passwordMatches = MessageDigest.isEqual(
        saved.password().getBytes(StandardCharsets.UTF_8),
        fields.getOrDefault("password", "").getBytes(StandardCharsets.UTF_8));
    String heading = usernameMatches && passwordMatches
        ? "Signed in as " + escape(saved.username())
        : "Sign-in failed";
    String html = "<!DOCTYPE html><html><body><h1>" + heading + "</h1></body></html>";
    send(exchange, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
  }

  private static void acceptExtension() throws IOException {
    ServerSocket server = new ServerSocket();
    server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), Config.HOST_PORT));
    Thread acceptor = new Thread(() -> {
      while (!server.isClosed()) {
        try {
          Socket socket = server.accept();
          socket.setTcpNoDelay(true);
          BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
          PrintWriter writer = new PrintWriter(new java.io.OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
          synchronized (BRIDGE) {
            if (bridgeSocket != null) {
              bridgeSocket.close();
            }
            bridgeSocket = socket;
            bridgeReader = reader;
            bridgeWriter = writer;
            BRIDGE.notifyAll();
          }
          System.err.println("Extension connected.");
        } catch (IOException exception) {
          if (!server.isClosed()) {
            exception.printStackTrace();
          }
        }
      }
    }, "extension-accept");
    acceptor.setDaemon(true);
    acceptor.start();
  }

  private static void installNativeHost() throws IOException {
    Path project = Path.of("").toAbsolutePath();
    Path jar = project.resolve("target/phalanx.jar");
    Path source = codeSource();
    if (source != null && source.toString().endsWith(".jar")) {
      jar = source;
    }
    // Chrome cannot execute a host that lives in Documents. Keep the script next to its manifest.
    Path script = Path.of(System.getProperty("user.home"), "Library/Application Support/Phalanx/phalanx-host.sh");
    Files.createDirectories(script.getParent());
    String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    Files.writeString(script, "#!/bin/sh\nexec " + shellQuote(javaBin) + " -jar " + shellQuote(jar.toString()) + " host 2>>/tmp/phalanx-host.log\n");
    script.toFile().setExecutable(true);

    JSONObject manifest = new JSONObject();
    manifest.put("name", "com.phalanx.broker");
    manifest.put("description", "Phalanx sign-in broker");
    manifest.put("path", script.toString());
    manifest.put("type", "stdio");
    manifest.put("allowed_origins", new JSONArray().put("chrome-extension://" + Config.EXTENSION_ID + "/"));
    String json = manifest.toString(2);
    Path home = Path.of(System.getProperty("user.home"));
    for (String directory : new String[] {
        "Library/Application Support/Chromium/NativeMessagingHosts",
        "Library/Application Support/Google/Chrome/NativeMessagingHosts",
        "Library/Application Support/Google/Chrome for Testing/NativeMessagingHosts"
    }) {
      Path folder = home.resolve(directory);
      Files.createDirectories(folder);
      Files.writeString(folder.resolve("com.phalanx.broker.json"), json);
    }
  }

  private static Path codeSource() {
    try {
      return Path.of(Broker.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (Exception exception) {
      return null;
    }
  }

  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  private static Map<String, String> form(String body) {
    Map<String, String> fields = new HashMap<>();
    if (body.isBlank()) {
      return fields;
    }
    for (String pair : body.split("&")) {
      int split = pair.indexOf('=');
      if (split < 0) {
        continue;
      }
      fields.put(urlDecode(pair.substring(0, split)), urlDecode(pair.substring(split + 1)));
    }
    return fields;
  }

  private static String urlDecode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  private static String escape(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static byte[] classpath(String name) throws IOException {
    try (InputStream input = Broker.class.getClassLoader().getResourceAsStream(name)) {
      if (input == null) {
        throw new IOException("Missing resource " + name);
      }
      return input.readAllBytes();
    }
  }

  private static void status(HttpExchange exchange, String status) throws IOException {
    send(exchange, 200, "application/json; charset=utf-8",
        ("{\"status\":\"" + status + "\"}").getBytes(StandardCharsets.UTF_8));
  }

  private static void send(HttpExchange exchange, int code, String type, byte[] body) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", type);
    exchange.sendResponseHeaders(code, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  private Broker() {}
}
