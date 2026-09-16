package vn.infodation.mongodb.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder.BCryptVersion;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Phase 1.5 - the encoder has to read hashes this application did not write.
 * <p>
 * The third test is the one worth keeping: it pins the exact failure the stock encoder produces
 * against {@code sample_mflix.users}, so the next person to "simplify" the bean back to
 * {@code createDelegatingPasswordEncoder()} finds out here rather than on the first login.
 */
class PasswordEncoderTest {

    private final PasswordEncoder encoder = new SecurityConfig(null, null).passwordEncoder();

    /** What the sample data holds: raw BCrypt, no {@code {id}} prefix, the 2b variant. */
    private static String sampleStyleHash(String raw) {
        return new BCryptPasswordEncoder(BCryptVersion.$2B).encode(raw);
    }

    @Test
    void readsTheUnprefixedBcryptHashesInTheSampleData() {
        String stored = sampleStyleHash("correct horse battery staple");
        assertThat(stored).startsWith("$2b$");

        assertThat(encoder.matches("correct horse battery staple", stored)).isTrue();
        assertThat(encoder.matches("not the password", stored)).isFalse();
    }

    @Test
    void writesSelfDescribingHashes() {
        // New accounts get a prefix, so the format stays upgradeable even though the old rows
        // never will be.
        assertThat(encoder.encode("a new password")).startsWith("{bcrypt}$2");
    }

    /**
     * The message moved in Security 7.1. It used to be {@code There is no PasswordEncoder mapped
     * for the id "null"}, which is what every tutorial still quotes; it now names the fix. Same
     * exception, same cause, same 500 on every login - only the text is different, so searching
     * for the old wording turns up nothing.
     */
    @Test
    void theStockDelegatingEncoderCannotReadThem() {
        PasswordEncoder stock = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        String stored = sampleStyleHash("correct horse battery staple");

        assertThatThrownBy(() -> stock.matches("correct horse battery staple", stored))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("each password must have a password encoding prefix")
                .hasMessageContaining("set a default password encoder");
    }
}
