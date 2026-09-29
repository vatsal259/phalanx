package phalanx;

public final class Config {
  public static final int HTTP_PORT = 4711;
  public static final int HOST_PORT = 4712;
  public static final String SITE = "http://localhost:4711/login.html";
  public static final String ISSUER = "http://localhost:8180/realms/phalanx";
  public static final String JWKS = ISSUER + "/protocol/openid-connect/certs";
  public static final String TOKEN = ISSUER + "/protocol/openid-connect/token";
  public static final String AUDIENCE = "phalanx-broker";
  public static final String ROLE = "agent";
  public static final String EXTENSION_ID = "dikjbkljobngmdhcneegecgipdeobbli";

  private Config() {}
}
