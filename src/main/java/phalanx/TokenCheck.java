package phalanx;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Checks the Keycloak token before any dialog.
 * A passing token still does not release the website password.
 */
public final class TokenCheck {
  private static final JWKSource<SecurityContext> KEYS = keys();

  /** @return null when the request may continue, otherwise a short reason */
  public static String problem(String token, String requestedSite, String savedSite) {
    try {
      JWTClaimsSet claims = claims(token);
      if (!Config.ISSUER.equals(claims.getIssuer())) {
        return "issuer does not match";
      }
      Date expires = claims.getExpirationTime();
      if (expires == null || !expires.after(new Date())) {
        return "token is expired";
      }
      List<String> audience = claims.getAudience();
      if (audience == null || !audience.contains(Config.AUDIENCE)) {
        return "audience does not match";
      }
      if (!hasAgentRole(claims)) {
        return "role is not agent";
      }
      if (!savedSite.equals(requestedSite)) {
        return "site does not match the saved login";
      }
      return null;
    } catch (Exception exception) {
      String detail = exception.getMessage();
      if (detail == null || detail.isBlank()) {
        return "token was rejected";
      }
      return "token was rejected: " + detail;
    }
  }

  private static JWTClaimsSet claims(String token) throws Exception {
    ConfigurableJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
    JWSKeySelector<SecurityContext> selector = new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, KEYS);
    processor.setJWSKeySelector(selector);
    return processor.process(token, null);
  }

  private static boolean hasAgentRole(JWTClaimsSet claims) throws Exception {
    if (includesAgent(claims.getClaim("role"))) {
      return true;
    }
    Map<String, Object> realmAccess = claims.getJSONObjectClaim("realm_access");
    if (realmAccess == null) {
      return false;
    }
    return includesAgent(realmAccess.get("roles"));
  }

  private static boolean includesAgent(Object roles) {
    if (roles instanceof String role) {
      return Config.ROLE.equals(role);
    }
    return roles instanceof List<?> list && list.stream().anyMatch(Config.ROLE::equals);
  }

  private static JWKSource<SecurityContext> keys() {
    try {
      return JWKSourceBuilder.create(URI.create(Config.JWKS).toURL()).retrying(true).build();
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private TokenCheck() {}
}
