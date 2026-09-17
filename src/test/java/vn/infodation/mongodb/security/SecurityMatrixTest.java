package vn.infodation.mongodb.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import vn.infodation.mongodb.analytics.service.BulkWriteService;
import vn.infodation.mongodb.analytics.service.RetryingTransferService;
import vn.infodation.mongodb.analytics.service.TransferService;
import vn.infodation.mongodb.cdc.AccountChangeListener;
import vn.infodation.mongodb.cdc.ResumeTokenStore;
import vn.infodation.mongodb.mflix.service.CommentDeletionService;
import vn.infodation.mongodb.mflix.service.CommentService;
import vn.infodation.mongodb.mflix.service.IndexInspectionService;
import vn.infodation.mongodb.mflix.service.MovieAggregationService;
import vn.infodation.mongodb.mflix.service.MoviePagingService;
import vn.infodation.mongodb.mflix.service.MovieService;
import vn.infodation.mongodb.mflix.service.MyCommentsService;
import vn.infodation.mongodb.search.SearchIndexService;
import vn.infodation.mongodb.search.SearchService;
import vn.infodation.mongodb.security.repository.UserAccountRepository;
import vn.infodation.mongodb.security.service.TokenService;
import vn.infodation.mongodb.supplies.service.SalesAggregationService;
import vn.infodation.mongodb.security.web.JsonErrorWriter;
import vn.infodation.mongodb.security.web.RestAccessDeniedHandler;
import vn.infodation.mongodb.security.web.RestAuthenticationEntryPoint;

/**
 * Phase 8.1 - the whole authorization matrix, with no Docker and no database.
 * <p>
 * Every service is a mock, because none of them is under test: what is under test is which
 * requests reach a controller at all. That is also why the "allowed" assertion is "not 401 and
 * not 403" rather than "200" - a mocked service answering null, or a validation error on the
 * {@code {}} body, is a perfectly good sign that authorization let the request through, and
 * pinning an exact success status here would make this test fail for reasons that have nothing
 * to do with security.
 */
@WebMvcTest
@Import({SecurityConfig.class, JwtConfig.class, MethodSecurityConfig.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class, JsonErrorWriter.class})
class SecurityMatrixTest {

    /** Any syntactically valid ObjectId; nothing reads it. */
    private static final String ID = "000000000000000000000001";

    private static final Integer ALLOWED = null;
    private static final Integer UNAUTHORIZED = 401;
    private static final Integer FORBIDDEN = 403;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private CommentService commentService;
    @MockitoBean private CommentDeletionService commentDeletionService;
    @MockitoBean private MyCommentsService myCommentsService;
    @MockitoBean private MovieService movieService;
    @MockitoBean private MoviePagingService moviePagingService;
    @MockitoBean private MovieAggregationService movieAggregationService;
    @MockitoBean private IndexInspectionService indexInspectionService;
    @MockitoBean private SearchService searchService;
    @MockitoBean private SearchIndexService searchIndexService;
    @MockitoBean private BulkWriteService bulkWriteService;
    @MockitoBean private TransferService transferService;
    @MockitoBean private RetryingTransferService retryingTransferService;
    @MockitoBean private SalesAggregationService salesAggregationService;
    @MockitoBean private AccountChangeListener accountChangeListener;
    @MockitoBean private ResumeTokenStore resumeTokenStore;
    @MockitoBean private UserAccountRepository userAccountRepository;
    @MockitoBean private TokenService tokenService;
    @MockitoBean private UserDetailsService userDetailsService;

    static Stream<Arguments> matrix() {
        return Stream.of(
                //                                                       anon          USER        ANALYST     ADMIN
                route(HttpMethod.POST, "/api/auth/login", ALLOWED, ALLOWED, ALLOWED, ALLOWED),
                route(HttpMethod.POST, "/api/auth/register", ALLOWED, ALLOWED, ALLOWED, ALLOWED),
                route(HttpMethod.POST, "/api/auth/refresh", ALLOWED, ALLOWED, ALLOWED, ALLOWED),
                route(HttpMethod.GET, "/api/auth/me", UNAUTHORIZED, ALLOWED, ALLOWED, ALLOWED),

                // The public catalogue.
                route(HttpMethod.GET, "/api/movies/" + ID, ALLOWED, ALLOWED, ALLOWED, ALLOWED),
                route(HttpMethod.GET, "/api/movies", ALLOWED, ALLOWED, ALLOWED, ALLOWED),
                route(HttpMethod.GET, "/api/stats/genres", ALLOWED, ALLOWED, ALLOWED, ALLOWED),
                route(HttpMethod.GET, "/api/search/movies", ALLOWED, ALLOWED, ALLOWED, ALLOWED),

                // Phase 3.4 - the three that have to be matched before GET /api/movies/**.
                // If the ordering in SecurityConfig is ever changed, these are what notice.
                route(HttpMethod.GET, "/api/movies/explain", UNAUTHORIZED, FORBIDDEN, FORBIDDEN, ALLOWED),
                route(HttpMethod.GET, "/api/movies/keyset/explain", UNAUTHORIZED, FORBIDDEN, FORBIDDEN, ALLOWED),
                route(HttpMethod.GET, "/api/movies/paging-benchmark", UNAUTHORIZED, FORBIDDEN, FORBIDDEN, ALLOWED),

                // Comments: writing needs an identity, reading does not.
                route(HttpMethod.GET, "/api/movies/" + ID + "/comments", ALLOWED, ALLOWED, ALLOWED, ALLOWED),
                route(HttpMethod.POST, "/api/movies/" + ID + "/comments", UNAUTHORIZED, ALLOWED, ALLOWED, ALLOWED),
                route(HttpMethod.DELETE, "/api/movies/" + ID + "/comments/" + ID, UNAUTHORIZED, ALLOWED, ALLOWED, ALLOWED),
                route(HttpMethod.GET, "/api/comments/mine", UNAUTHORIZED, ALLOWED, ALLOWED, ALLOWED),

                // Catalogue mutation.
                route(HttpMethod.POST, "/api/movies/" + ID + "/votes", UNAUTHORIZED, FORBIDDEN, FORBIDDEN, ALLOWED),
                route(HttpMethod.POST, "/api/movies/" + ID + "/genres", UNAUTHORIZED, FORBIDDEN, FORBIDDEN, ALLOWED),
                route(HttpMethod.DELETE, "/api/movies/" + ID + "/genres", UNAUTHORIZED, FORBIDDEN, FORBIDDEN, ALLOWED),

                // Analytics: an analyst reads, only an admin writes.
                route(HttpMethod.GET, "/api/analytics/balances", UNAUTHORIZED, FORBIDDEN, ALLOWED, ALLOWED),
                route(HttpMethod.GET, "/api/analytics/transfers", UNAUTHORIZED, FORBIDDEN, ALLOWED, ALLOWED),
                route(HttpMethod.POST, "/api/analytics/transfer", UNAUTHORIZED, FORBIDDEN, FORBIDDEN, ALLOWED),
                // A GET that writes 20,000 documents. Covered by the namespace catch-all, not by
                // a method rule - which is exactly why the catch-all is there.
                route(HttpMethod.GET, "/api/analytics/bulk/benchmark", UNAUTHORIZED, FORBIDDEN, FORBIDDEN, ALLOWED),

                // Operations.
                route(HttpMethod.POST, "/api/cdc/start", UNAUTHORIZED, FORBIDDEN, FORBIDDEN, ALLOWED),
                route(HttpMethod.GET, "/api/cdc/status", UNAUTHORIZED, FORBIDDEN, FORBIDDEN, ALLOWED),
                route(HttpMethod.GET, "/api/indexes/movies", UNAUTHORIZED, FORBIDDEN, FORBIDDEN, ALLOWED),
                route(HttpMethod.GET, "/api/search/index", UNAUTHORIZED, FORBIDDEN, FORBIDDEN, ALLOWED),
                route(HttpMethod.POST, "/api/search/index", UNAUTHORIZED, FORBIDDEN, FORBIDDEN, ALLOWED),

                // The API docs are public - the Authorize button is how you get a token, so it
                // cannot itself need one. They describe the API without widening it.
                route(HttpMethod.GET, "/v3/api-docs", ALLOWED, ALLOWED, ALLOWED, ALLOWED),
                route(HttpMethod.GET, "/v3/api-docs/swagger-config", ALLOWED, ALLOWED, ALLOWED, ALLOWED),

                // Default deny: a path nobody mapped is refused before anyone discovers it is 404.
                route(HttpMethod.GET, "/api/not-a-real-endpoint", UNAUTHORIZED, FORBIDDEN, FORBIDDEN, FORBIDDEN));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("matrix")
    void enforcesTheRouteMatrix(HttpMethod method, String path,
                                Integer anonymous, Integer user, Integer analyst, Integer admin) throws Exception {
        assertStatus(method, path, null, anonymous);
        assertStatus(method, path, Roles.USER, user);
        assertStatus(method, path, Roles.ANALYST, analyst);
        assertStatus(method, path, Roles.ADMIN, admin);
    }

    /** Phase 3.5 - the two statuses the filter chain writes itself carry the app's error shape. */
    @Test
    void deniedRequestsCarryTheStandardErrorBody() throws Exception {
        String anonymous = perform(HttpMethod.GET, "/api/indexes/movies", null)
                .getResponse().getContentAsString();

        assertThat(anonymous)
                .contains("\"status\":401")
                .contains("\"error\":\"Unauthorized\"")
                .contains("\"timestamp\"")
                .contains("\"message\"");

        String forbidden = perform(HttpMethod.GET, "/api/indexes/movies", Roles.USER)
                .getResponse().getContentAsString();

        assertThat(forbidden)
                .contains("\"status\":403")
                .contains("\"error\":\"Forbidden\"");
    }

    /**
     * An anonymous caller gets 401 and an authenticated one gets 403, and neither is a choice
     * made here: {@code ExceptionTranslationFilter} asks the trust resolver and routes to the
     * entry point or the denied handler accordingly.
     */
    @Test
    void anonymousIsUnauthorizedAndWrongRoleIsForbidden() throws Exception {
        assertThat(perform(HttpMethod.POST, "/api/cdc/start", null).getResponse().getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(perform(HttpMethod.POST, "/api/cdc/start", Roles.ANALYST).getResponse().getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    private void assertStatus(HttpMethod method, String path, String role, Integer expected) throws Exception {
        int status = perform(method, path, role).getResponse().getStatus();
        String who = role == null ? "anonymous" : role;

        if (expected == null) {
            assertThat(status)
                    .as("%s %s as %s should not be denied", method, path, who)
                    .isNotIn(HttpStatus.UNAUTHORIZED.value(), HttpStatus.FORBIDDEN.value());
        } else {
            assertThat(status)
                    .as("%s %s as %s", method, path, who)
                    .isEqualTo(expected);
        }
    }

    private MvcResult perform(HttpMethod method, String path, String role) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, path)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}");

        if (role != null) {
            request = request.with(SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(jwt -> jwt.subject("someone@lab.local").claim("roles", List.of(role)))
                    .authorities(new SimpleGrantedAuthority("ROLE_" + role)));
        }
        return mockMvc.perform(request).andReturn();
    }

    private static Arguments route(HttpMethod method, String path,
                                   Integer anonymous, Integer user, Integer analyst, Integer admin) {
        return Arguments.of(method, path, anonymous, user, analyst, admin);
    }
}
