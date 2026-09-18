package vn.infodation.mongodb.mflix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import tools.jackson.databind.ObjectMapper;
import vn.infodation.mongodb.support.AbstractMongoIntegrationTest;
import vn.infodation.mongodb.support.SampleFixtures;

/**
 * Phase 4.4 and 4.5 - filtering in the database against filtering in the JVM.
 * <p>
 * The fixtures hold four comments: two by Ada, one each by Grace and Alan. Asking for a page of
 * four gets four rows out of Mongo either way; the difference is where the other two go.
 */
@AutoConfigureMockMvc
class MyCommentsIT extends AbstractMongoIntegrationTest {

    private static final String ADA = "ada@example.com";
    private static final int ADA_COMMENTS = 2;
    private static final int ALL_COMMENTS = 4;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MongoTemplate mongoTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        SampleFixtures.reset(mongoTemplate);
    }

    /**
     * Phase 4.4 - the {@code @Query} reads {@code authentication.name} through
     * {@code SecurityEvaluationContextExtension}. No email is passed in, and there is no
     * parameter through which one could be.
     */
    @Test
    void theQuerySeesTheAuthentication() throws Exception {
        Map<String, Object> page = body(get("/api/comments/mine").param("size", "20"), ADA);

        assertThat(items(page)).hasSize(ADA_COMMENTS);
        assertThat(items(page)).allSatisfy(item -> assertThat(item.get("email")).isEqualTo(ADA));
        assertThat(page.get("totalElements")).isEqualTo(ADA_COMMENTS);
    }

    /**
     * Phase 4.5 - {@code @PostFilter} runs after paging, so a page the server considered full
     * arrives with rows missing. Ask for four and get two, with no indication that the other two
     * were ever there.
     */
    @Test
    void postFilterSilentlyShrinksThePage() throws Exception {
        String json = mockMvc.perform(get("/api/comments/mine/post-filtered")
                        .param("size", String.valueOf(ALL_COMMENTS))
                        .with(as(ADA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<?> postFiltered = objectMapper.readValue(json, List.class);

        assertThat(postFiltered)
                .as("a page of %d came back holding only the caller's rows", ALL_COMMENTS)
                .hasSize(ADA_COMMENTS)
                .hasSizeLessThan(ALL_COMMENTS);

        // Same answer, without having read two documents it was never allowed to return.
        Map<String, Object> filteredByMongo = body(
                get("/api/comments/mine").param("size", String.valueOf(ALL_COMMENTS)), ADA);
        assertThat(items(filteredByMongo)).hasSize(ADA_COMMENTS);
    }

    @Test
    void someoneElseSeesTheirOwnComments() throws Exception {
        Map<String, Object> page = body(get("/api/comments/mine").param("size", "20"),
                "grace@example.com");

        assertThat(items(page)).hasSize(1);
        assertThat(items(page).get(0).get("email")).isEqualTo("grace@example.com");
    }

    @Test
    void anonymousGetsNothingAtAll() throws Exception {
        mockMvc.perform(get("/api/comments/mine"))
                .andExpect(status().isUnauthorized());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String email) throws Exception {
        String json = mockMvc.perform(request.with(as(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(json, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private static RequestPostProcessor as(String email) {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(jwt -> jwt.subject(email).claim("roles", List.of("USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
