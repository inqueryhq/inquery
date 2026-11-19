package ai.inquery.server.web.start.config.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * JWT Token utility class
 */
@Slf4j
@Component
public class JwtUtils {

    /**
     * Profiles that run only on a developer machine, where a fixed local secret is
     * acceptable. Every other profile must supply its own secret via JWT_SECRET.
     */
    private static final Set<String> LOCAL_ONLY_PROFILES = Set.of("dev", "desktop", "test");

    /**
     * Secrets that have shipped in this repository at some point. They are public, so
     * they are never acceptable outside a local-only profile.
     */
    private static final List<String> PUBLISHED_SECRETS = Arrays.asList(
            "inquery-default-secret-key-please-change-in-production",
            "dev-inquery-jwt-secret-change-me",
            "desktop-inquery-jwt-secret-local-only",
            "change-me-to-a-random-secret-key");

    @Value("${inquery.jwt.secret:}")
    private String secretKey;

    @Autowired
    private Environment environment;

    @Value("${inquery.jwt.expiration:2592000}")
    private long expirationSeconds; // Default 30 days

    @Value("${inquery.jwt.issuer:inquery}")
    private String issuer;

    /**
     * Refuse to start when a deployment would sign tokens with a blank or publicly
     * known secret. Anyone holding that secret can mint a token for any user id.
     */
    @PostConstruct
    void validateSecretKey() {
        boolean localOnly = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(LOCAL_ONLY_PROFILES::contains);
        if (localOnly) {
            if (secretKey == null || secretKey.isBlank()) {
                throw new IllegalStateException(
                        "inquery.jwt.secret is not set. Local profiles define one in their "
                        + "application-<profile>.yml; set JWT_SECRET to override it.");
            }
            return;
        }

        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET is not set. Generate one (openssl rand -base64 48) and pass it "
                    + "as the JWT_SECRET environment variable. Inquery will not sign tokens with "
                    + "an empty key.");
        }
        if (PUBLISHED_SECRETS.contains(secretKey)) {
            throw new IllegalStateException(
                    "JWT_SECRET is set to a value published in the Inquery repository, so it is "
                    + "public. Generate your own (openssl rand -base64 48) and set JWT_SECRET to it.");
        }
    }

    /**
     * Generate JWT token for user
     */
    public String generateToken(Long userId) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expirationSeconds * 1000);

        return JWT.create()
                .withIssuer(issuer)
                .withSubject(String.valueOf(userId))
                .withIssuedAt(now)
                .withExpiresAt(expiryDate)
                .sign(Algorithm.HMAC256(secretKey));
    }

    /**
     * Validate token and extract user ID
     * @return user ID if valid, null if invalid
     */
    public Long validateTokenAndGetUserId(String token) {
        try {
            Algorithm algorithm = Algorithm.HMAC256(secretKey);
            JWTVerifier verifier = JWT.require(algorithm)
                    .withIssuer(issuer)
                    .build();
            
            DecodedJWT jwt = verifier.verify(token);
            String subject = jwt.getSubject();
            
            return Long.parseLong(subject);
        } catch (JWTVerificationException | NumberFormatException e) {
            log.debug("Invalid JWT token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Check if token is valid
     */
    public boolean isTokenValid(String token) {
        return validateTokenAndGetUserId(token) != null;
    }
}
