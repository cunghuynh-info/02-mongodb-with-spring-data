package vn.infodation.mongodb.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.ObjectMapper;
import vn.infodation.mongodb.security.domain.RefreshToken;
import vn.infodation.mongodb.security.repository.RefreshTokenRepository;
import vn.infodation.mongodb.support.AbstractMongoIntegrationTest;

/** Phase 5 - rotation, reuse detection, and the gap between a TTL index and enforcement. */
@AutoConfigureMockMvc
class RefreshTokenIT extends AbstractMongoIntegrationTest {

    private static final String PASSWORD = "not-a-real-password";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    RefreshTokenRepository refreshTokens;

    private String email;

    @BeforeEach
    void setUp() {
        email = "refresh-" + UUID.randomUUID() + "@lab.local";
    }

    @Test
    void rotationKillsTheTokenItReplaces() throws Exception {
        String first = (String) register().get("refreshToken");

        Map<String, Object> rotated = body(refresh(first).andExpect(status().isOk()));
        String second = (String) rotated.get("refreshToken");

        assertThat(second).isNotEqualTo(first);
        assertThat((String) rotated.get("accessToken")).isNotBlank();

        // The replaced row is kept, not deleted - that is what makes reuse visible later.
        RefreshToken replaced = refreshTokens.findById(first).orElseThrow();
        assertThat(replaced.getRevokedAt()).isNotNull();
        assertThat(replaced.getReplacedBy()).isEqualTo(second);

        refresh(first).andExpect(status().isUnauthorized());
    }

    /**
     * Phase 5.4 - a token presented after it was rotated means a copy of it exists. There is no
     * way to tell which of the two holders is the legitimate one, so both lose.
     */
    @Test
    void replayingARotatedTokenRevokesTheWholeChain() throws Exception {
        String first = (String) register().get("refreshToken");
        String second = (String) body(refresh(first).andExpect(status().isOk())).get("refreshToken");

        // Someone replays the stolen copy.
        refresh(first).andExpect(status().isUnauthorized());

        // ...and the legitimate holder is logged out too.
        refresh(second).andExpect(status().isUnauthorized());

        assertThat(refreshTokens.findByUserEmail(email))
                .isNotEmpty()
                .allSatisfy(token -> assertThat(token.getRevokedAt()).isNotNull());
    }

    /**
     * Phase 5.2 - the TTL index is cleanup, not enforcement.
     * <p>
     * This row is expired and still present, which is exactly the state Mongo leaves it in for up
     * to a minute after it dies. If {@code TokenService} trusted the index to have removed it,
     * that minute would be a free extension on every expiry.
     */
    @Test
    void anExpiredTokenIsRefusedWhileTheReaperHasNotRunYet() throws Exception {
        register();

        Instant now = Instant.now();
        RefreshToken expired = refreshTokens.insert(RefreshToken.builder()
                .id("expired-" + UUID.randomUUID())
                .userEmail(email)
                .issuedAt(now.minus(30, ChronoUnit.DAYS))
                .expiresAt(now.minus(1, ChronoUnit.HOURS))
                .build());

        assertThat(refreshTokens.findById(expired.getId())).isPresent();

        refresh(expired.getId()).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheRefreshTokenAndNothingElse() throws Exception {
        Map<String, Object> tokens = register();
        String refreshToken = (String) tokens.get("refreshToken");

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + tokens.get("accessToken"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(refreshToken)))
                .andExpect(status().isNoContent());

        refresh(refreshToken).andExpect(status().isUnauthorized());

        // Phase 5.5, stated plainly: the access token still works. Logging out of a stateless
        // API cannot take back a signature that has already been handed over.
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + tokens.get("accessToken")))
                .andExpect(status().isOk());
    }

    @Test
    void anUnknownRefreshTokenIsRefused() throws Exception {
        refresh("nothing-like-a-real-token").andExpect(status().isUnauthorized());
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken":"%s"}""".formatted(refreshToken)));
    }

    private Map<String, Object> register() throws Exception {
        return body(mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Refresh","email":"%s","password":"%s"}"""
                                .formatted(email, PASSWORD)))
                .andExpect(status().isCreated()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResultActions actions) throws Exception {
        return objectMapper.readValue(actions.andReturn().getResponse().getContentAsString(), Map.class);
    }
}
