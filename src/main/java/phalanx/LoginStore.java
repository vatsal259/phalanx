package phalanx;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Website login. Only the broker reads this file. */
public final class LoginStore {
  public record Login(String username, String password, String site) {}

  public static Login load() throws IOException {
    Path path = Path.of("login.properties").toAbsolutePath();
    if (!Files.isRegularFile(path)) {
      throw new IOException("Missing " + path + ". This file is the website login. The agent does not read it.");
    }
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(path)) {
      properties.load(reader);
    }
    return new Login(required(properties, "username"), required(properties, "password"), required(properties, "site"));
  }

  private static String required(Properties properties, String name) throws IOException {
    String value = properties.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IOException("login.properties is missing " + name);
    }
    return value.trim();
  }

  private LoginStore() {}
}
