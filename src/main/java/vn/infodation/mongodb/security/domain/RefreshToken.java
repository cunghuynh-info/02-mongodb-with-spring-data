package vn.infodation.mongodb.security.domain;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Phase 5.1 - an opaque refresh token, stored rather than signed.
 * <p>
 * The {@code _id} <em>is</em> the token: 256 bits of {@code SecureRandom}, base64url. Nothing is
 * signed because nothing needs to be - the server looks it up anyway, so a signature would only
 * add a way to be wrong. That is the opposite of the access token, which is signed precisely so
 * it does not need a lookup.
 * <p>
 * A rotated token is kept with {@code revokedAt} and {@code replacedBy} set rather than deleted,
 * which is what makes the reuse detection in Phase 5.4 possible: a token presented after it was
 * rotated is evidence of a copy, and the only way to see that is to still have the row.
 * <p>
 * No {@code @CreatedBy}: these are written during {@code POST /api/auth/login}, which is an
 * anonymous request - the {@code AuditorAware} has nothing to report at that point. The owner is
 * set explicitly from the authentication the login just produced.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = RefreshToken.COLLECTION)
public class RefreshToken {

    public static final String COLLECTION = "lab_refresh_tokens";

    @Id
    private String id;

    private String userEmail;
    private Instant issuedAt;

    /** The TTL index in {@code IndexConfig} watches this field. */
    private Instant expiresAt;

    private Instant revokedAt;
    private String replacedBy;

    private String userAgent;
    private String ip;

    public boolean isLive(Instant now) {
        return revokedAt == null && expiresAt != null && expiresAt.isAfter(now);
    }
}
