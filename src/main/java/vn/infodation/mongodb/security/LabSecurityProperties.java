package vn.infodation.mongodb.security;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/** Everything under {@code lab.security} in {@code application.yaml}. */
@Data
@ConfigurationProperties("lab.security")
public class LabSecurityProperties {

    /** The {@code iss} claim, and what the decoder insists on seeing. */
    private String issuer = "mongodb-lab";

    /** Short, because nothing can revoke an access token before it expires (Phase 5.5). */
    private Duration accessTokenTtl = Duration.ofMinutes(15);

    /** Long, because rotation (Phase 5.3) is what limits the damage, not the lifetime. */
    private Duration refreshTokenTtl = Duration.ofDays(14);

    /** Empty means no cross-origin request is allowed - the safe default for a lab. */
    private List<String> allowedOrigins = new ArrayList<>();

    private final Jwt jwt = new Jwt();

    private final Seed seed = new Seed();

    @Data
    public static class Jwt {

        /**
         * Base64 PKCS#8 private key. When absent a keypair is generated at startup, which is
         * fine until devtools restarts the context and silently invalidates every token already
         * issued - including the one sitting in {@code http/requests.http}. Set it for any
         * session where that matters; never commit the value.
         */
        private String privateKey;
    }

    @Data
    public static class Seed {

        /** Off by default. {@code LabUserSeeder} additionally refuses outside dev and test. */
        private boolean enabled = false;

        /** The plaintext for every seeded account. The sample users' plaintexts are unknown. */
        private String password = "lab-password";
    }
}
