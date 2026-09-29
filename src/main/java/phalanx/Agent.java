package phalanx;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Stands in for an agent. It can ask Phalanx to sign in.
 * It never reads login.properties and never receives the website password.
 */
public final class Agent {
  public static void start() throws Exception {
    String token = keycloakToken();
    Path extension = Path.of("extension").toAbsolutePath();
    Path profile = Path.of("chrome-profile").toAbsolutePath();
    copyNativeHostManifest(profile);
    try (Playwright playwright = Playwright.create()) {
      BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
          .setHeadless(false)
          .setIgnoreDefaultArgs(java.util.List.of("--disable-extensions", "--enable-automation"))
          .setArgs(java.util.List.of(
              "--disable-extensions-except=" + extension,
              "--load-extension=" + extension,
              "--disable-features=DisableLoadExtensionCommandLineSwitch"));
      try (BrowserContext browser = playwright.chromium().launchPersistentContext(profile, options)) {
        Page page = browser.pages().isEmpty() ? browser.newPage() : browser.pages().get(0);
        page.navigate(Config.SITE);
        String status = signIn(token);
        System.out.println("status: " + status);
        System.out.flush();
        if ("success".equals(status)) {
          page.getByText("Signed in").waitFor();
          System.out.println(page.locator("h1").textContent());
          System.out.flush();
        }
        System.out.println("Press Enter to close the browser.");
        System.out.flush();
        System.in.read();
      }
    }
  }

  private static String signIn(String token) throws Exception {
    JSONObject body = new JSONObject();
    body.put("site", Config.SITE);
    body.put("reason", "sign in to the local page");
    body.put("token", token);
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + Config.HTTP_PORT + "/signin"))
        .timeout(Duration.ofSeconds(90))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        .build();
    HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    return new JSONObject(response.body()).getString("status");
  }

  private static void copyNativeHostManifest(Path profile) throws IOException {
    Path manifest = Path.of(System.getProperty("user.home"),
        "Library/Application Support/Chromium/NativeMessagingHosts/com.phalanx.broker.json");
    if (!Files.isRegularFile(manifest)) {
      return;
    }
    Path destination = profile.resolve("NativeMessagingHosts");
    Files.createDirectories(destination);
    Files.copy(manifest, destination.resolve(manifest.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
  }

  /** This password belongs to the Keycloak user named agent. It is not the website password. */
  private static String keycloakToken() throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(Config.TOKEN))
        .timeout(Duration.ofSeconds(10))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(
            "grant_type=password&client_id=phalanx-agent&username=agent&password=agent"))
        .build();
    HttpResponse<String> response;
    try {
      response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException exception) {
      throw new IllegalStateException("Keycloak is not running. Start it with: docker compose -f keycloak/docker-compose.yml up", exception);
    }
    if (response.statusCode() != 200) {
      throw new IllegalStateException("Keycloak refused the agent login (" + response.statusCode() + ").");
    }
    return new JSONObject(response.body()).getString("access_token");
  }

  private Agent() {}
}
