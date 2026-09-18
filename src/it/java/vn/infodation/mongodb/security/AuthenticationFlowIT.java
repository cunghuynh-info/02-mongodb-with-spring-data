package vn.infodation.mongodb.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.ObjectMapper;
import vn.infodation.mongodb.mflix.domain.Comment;
import vn.infodation.mongodb.support.AbstractMongoIntegrationTest;
import vn.infodation.mongodb.support.SampleFixtures;

/**
 * Phase 8.3 - the whole flow over HTTP, with real tokens.
 * <p>
 * The slice test in {@code SecurityMatrixTest} injects an authentication and never mints or
 * verifies anything. This one goes through {@code NimbusJwtEncoder}, the {@code Authorization}
 * header, {@code NimbusJwtDecoder} and the authorities converter, which is where a mismatch
 * between what login writes and what the resource server reads would actually show up.
 */
@AutoConfigureMockMvc
class AuthenticationFlowIT extends AbstractMongoIntegrationTest {

    private static final String PASSWORD = "not-a-real-password";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    MongoTemplate mongoTemplate;

    private String email;

    @BeforeEach
    void setUp() {
        SampleFixtures.reset(mongoTemplate);
        // A fresh address per run rather than wiping users: the seeded lab accounts and the
        // sample rows are shared with every other test in the suite.
        email = "flow-" + UUID.randomUUID() + "@lab.local";
    }

    @Test
    void registersLogsInAndCarriesTheTokenBack() throws Exception {
        Map<String, Object> registered = register();

        assertThat(registered.get("tokenType")).isEqualTo("Bearer");
        assertThat(registered.get("roles")).isEqualTo(List.of("USER"));
        assertThat((String) registered.get("accessToken")).isNotBlank();

        Map<String, Object> login = body(mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, PASSWORD)))
                .andExpect(status().isOk()));

        // The token round-trips through the real decoder and comes back as this user.
        Map<String, Object> me = body(mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + login.get("accessToken")))
                .andExpect(status().isOk()));

        assertThat(me.get("email")).isEqualTo(email);
        assertThat(me.get("roles")).isEqualTo(List.of("USER"));
    }

    @Test
    void rejectsTheWrongPasswordWithoutSayingWhy() throws Exception {
        register();

        String wrongPassword = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"wrong"}""".formatted(email)))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String unknownEmail = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nobody-%s@lab.local","password":"%s"}"""
                                .formatted(UUID.randomUUID(), PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // Same status and same message: the login endpoint is not a user enumerator.
        assertThat(message(wrongPassword)).isEqualTo(message(unknownEmail));
        assertThat(message(wrongPassword)).isEqualTo("invalid email or password");
    }

    @Test
    void theUniqueIndexDecidesTheSecondRegistration() throws Exception {
        register();

        // Not a pre-check in the controller - this 409 comes from users_email_unique.
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Flow again","email":"%s","password":"%s"}"""
                                .formatted(email, PASSWORD)))
                .andExpect(status().isConflict());
    }

    @Test
    void anUnauthenticatedCallIsRefusedInTheAppsOwnErrorShape() throws Exception {
        String body = mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> error = read(body);
        assertThat(error).containsKeys("timestamp", "status", "error", "message");
        assertThat(error.get("status")).isEqualTo(401);
    }

    @Test
    void aGarbledTokenIsRefused() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Phase 4.2 - the author is the caller, not whatever the body claims. Before this change the
     * request carried {@code name} and {@code email}, so anyone could sign a comment as anyone.
     */
    @Test
    void theCommentAuthorComesFromTheTokenNotTheBody() throws Exception {
        Map<String, Object> tokens = register();

        mockMvc.perform(post("/api/movies/{id}/comments", SampleFixtures.GODFATHER.toHexString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.get("accessToken"))
                        .contentType(MediaType.APPLICATION_JSON)
                        // No email field exists on this request any more, and passing one has no
                        // effect: unknown properties are not bound to the record.
                        .content("""
                                {"text":"Posted through the API","email":"someone.else@lab.local"}"""))
                .andExpect(status().isCreated());

        Comment stored = mongoTemplate.findAll(Comment.class).stream()
                .filter(comment -> "Posted through the API".equals(comment.getText()))
                .findFirst()
                .orElseThrow();

        assertThat(stored.getEmail()).isEqualTo(email);
        // Phase 7.2 - written by the auditing callback, not by CommentService.
        assertThat(stored.getCreatedBy()).isEqualTo(email);
    }

    @Test
    void anOrdinaryUserCannotReachTheAdminRoutes() throws Exception {
        Map<String, Object> tokens = register();
        String bearer = "Bearer " + tokens.get("accessToken");

        mockMvc.perform(get("/api/indexes/movies").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/movies/explain").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isForbidden());
        // ...while the public half of the same controller is fine.
        mockMvc.perform(get("/api/movies/{id}", SampleFixtures.GODFATHER.toHexString()))
                .andExpect(status().isOk());
    }

    private Map<String, Object> register() throws Exception {
        return body(mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Flow","email":"%s","password":"%s"}"""
                                .formatted(email, PASSWORD)))
                .andExpect(status().isCreated()));
    }

    private Map<String, Object> body(ResultActions actions) throws Exception {
        return read(actions.andReturn().getResponse().getContentAsString());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String json) {
        return objectMapper.readValue(json, Map.class);
    }

    private String message(String json) {
        return String.valueOf(read(json).get("message"));
    }
}
