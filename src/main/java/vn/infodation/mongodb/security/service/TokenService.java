package vn.infodation.mongodb.security.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import vn.infodation.mongodb.security.LabSecurityProperties;
import vn.infodation.mongodb.security.domain.RefreshToken;
import vn.infodation.mongodb.security.repository.RefreshTokenRepository;

/**
 * Phase 2.3 and Phase 5 - mints access tokens, and issues, rotates and revokes refresh tokens.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    /**
     * Named {@code roles}, not {@code authorities}, and holding unprefixed values. The prefix is
     * added by the converter in {@code JwtConfig}, so it appears in exactly one place.
     */
    public static final String ROLES_CLAIM = "roles";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JwtEncoder jwtEncoder;
    private final MongoTemplate mongoTemplate;
    private final RefreshTokenRepository refreshTokens;
    private final LabSecurityProperties properties;

    /** The signed half. Nothing can withdraw this before {@code exp} - see {@link #revoke}. */
    public AccessToken mintAccessToken(String email, Collection<String> roles) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getAccessTokenTtl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuer())
                .subject(email)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(ROLES_CLAIM, List.copyOf(roles))
                .build();

        String value = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        return new AccessToken(value, expiresAt, properties.getAccessTokenTtl().toSeconds());
    }

    /** Phase 5.1 - the stored half. */
    public RefreshToken issueRefreshToken(String email, String userAgent, String ip) {
        Instant now = Instant.now();
        return refreshTokens.insert(RefreshToken.builder()
                .id(randomToken())
                .userEmail(email)
                .issuedAt(now)
                .expiresAt(now.plus(properties.getRefreshTokenTtl()))
                .userAgent(userAgent)
                .ip(ip)
                .build());
    }

    /**
     * Phase 5.3 and 5.4 - rotation with reuse detection.
     * <p>
     * The claim is a single {@code findAndModify} filtered on {@code revokedAt == null}, so two
     * concurrent refreshes race inside the server and exactly one of them matches. Doing it as
     * read-then-update would let both pass the read and both issue a token.
     * <p>
     * A miss therefore means one of two things, and the difference matters: no such token (a
     * guess, a typo, an already-reaped row) or a token that was <em>already</em> rotated. The
     * second means a copy of it exists somewhere, so the whole chain for that user is revoked.
     */
    public RefreshToken rotate(String presented, String userAgent, String ip) {
        Instant now = Instant.now();
        String replacement = randomToken();

        RefreshToken claimed = mongoTemplate.findAndModify(
                Query.query(Criteria.where("_id").is(presented).and("revokedAt").is(null)),
                new Update().set("revokedAt", now).set("replacedBy", replacement),
                FindAndModifyOptions.options().returnNew(false),
                RefreshToken.class);

        if (claimed == null) {
            refreshTokens.findById(presented).ifPresent(reused -> {
                log.warn("refresh token {} replayed after rotation - revoking every token for {}",
                        abbreviate(presented), reused.getUserEmail());
                revokeAllFor(reused.getUserEmail());
            });
            throw new BadCredentialsException("refresh token is not valid");
        }

        // Phase 5.2 - the TTL index is cleanup, not enforcement: Mongo's reaper runs about once a
        // minute, so an expired row is still readable for up to ~60s after it dies. The check
        // has to happen here or that minute is a free extension.
        if (claimed.getExpiresAt() == null || !claimed.getExpiresAt().isAfter(now)) {
            throw new BadCredentialsException("refresh token has expired");
        }

        return refreshTokens.insert(RefreshToken.builder()
                .id(replacement)
                .userEmail(claimed.getUserEmail())
                .issuedAt(now)
                .expiresAt(now.plus(properties.getRefreshTokenTtl()))
                .userAgent(userAgent)
                .ip(ip)
                .build());
    }

    /** Phase 5.5 - logout. The access token stays usable until it expires; nothing here changes that. */
    public boolean revoke(String presented) {
        return mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(presented).and("revokedAt").is(null)),
                new Update().set("revokedAt", Instant.now()),
                RefreshToken.class).getModifiedCount() > 0;
    }

    public long revokeAllFor(String email) {
        return mongoTemplate.updateMulti(
                Query.query(Criteria.where("userEmail").is(email).and("revokedAt").is(null)),
                new Update().set("revokedAt", Instant.now()),
                RefreshToken.class).getModifiedCount();
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String abbreviate(String token) {
        return token == null || token.length() < 8 ? "?" : token.substring(0, 8) + "...";
    }

    public record AccessToken(String value, Instant expiresAt, long expiresInSeconds) {
    }
}
