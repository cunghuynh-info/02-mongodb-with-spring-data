package vn.infodation.mongodb.mflix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import vn.infodation.mongodb.mflix.domain.Comment;
import vn.infodation.mongodb.mflix.repository.CommentRepository;
import vn.infodation.mongodb.support.AbstractMongoIntegrationTest;
import vn.infodation.mongodb.support.SampleFixtures;

/**
 * Phase 4.3 - one rule, three implementations, and the ways they differ.
 * <p>
 * All three refuse to delete someone else's comment, which is the part that matters. What they
 * do <em>not</em> agree on is what they tell the caller about a comment that is not theirs, and
 * the {@code post-authorize} column below is a worked example of an authorization check that is
 * correct and still leaks:
 * <pre>
 *                    someone else's id   an id that does not exist
 *   post-authorize          403                    404             &lt;- the difference is the leak
 *   bean                    403                    403
 *   query                   404                    404
 * </pre>
 * A caller who can tell 403 from 404 can walk a list of ids and learn which ones are real.
 */
@AutoConfigureMockMvc
class CommentOwnershipIT extends AbstractMongoIntegrationTest {

    private static final String ADA = "ada@example.com";
    private static final String GRACE = "grace@example.com";
    private static final ObjectId MISSING = new ObjectId("0000000000000000000000ff");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MongoTemplate mongoTemplate;

    @Autowired
    CommentRepository comments;

    @BeforeEach
    void setUp() {
        SampleFixtures.reset(mongoTemplate);
    }

    @Test
    void everyStrategyLetsYouDeleteYourOwnComment() throws Exception {
        for (String strategy : List.of("query", "post-authorize", "bean")) {
            SampleFixtures.reset(mongoTemplate);
            ObjectId own = commentBy(ADA);

            mockMvc.perform(delete(uri(own)).param("strategy", strategy).with(as(ADA)))
                    .andExpect(status().isNoContent());

            assertThat(comments.findById(own))
                    .as("strategy %s should have deleted the comment", strategy)
                    .isEmpty();
        }
    }

    @Test
    void noStrategyLetsYouDeleteSomeoneElsesComment() throws Exception {
        for (String strategy : List.of("query", "post-authorize", "bean")) {
            SampleFixtures.reset(mongoTemplate);
            ObjectId theirs = commentBy(GRACE);

            mockMvc.perform(delete(uri(theirs)).param("strategy", strategy).with(as(ADA)))
                    .andExpect(status().is4xxClientError());

            assertThat(comments.findById(theirs))
                    .as("strategy %s must not have deleted someone else's comment", strategy)
                    .isPresent();
        }
    }

    /**
     * The leak, pinned. {@code post-authorize} answers 403 for a comment that exists and 404 for
     * one that does not, so the status code reports existence to someone with no right to know.
     */
    @Test
    void postAuthorizeDistinguishesSomeoneElsesCommentFromAMissingOne() throws Exception {
        ObjectId theirs = commentBy(GRACE);

        mockMvc.perform(delete(uri(theirs)).param("strategy", "post-authorize").with(as(ADA)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(uri(MISSING)).param("strategy", "post-authorize").with(as(ADA)))
                .andExpect(status().isNotFound());
    }

    @Test
    void theOtherTwoStrategiesGiveTheSameAnswerToBoth() throws Exception {
        ObjectId theirs = commentBy(GRACE);

        // The bean check answers "no" to everything it cannot authorise.
        mockMvc.perform(delete(uri(theirs)).param("strategy", "bean").with(as(ADA)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(uri(MISSING)).param("strategy", "bean").with(as(ADA)))
                .andExpect(status().isForbidden());

        // Ownership inside the query cannot distinguish the two cases even in principle: the
        // delete simply matched nothing.
        mockMvc.perform(delete(uri(theirs)).param("strategy", "query").with(as(ADA)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(uri(MISSING)).param("strategy", "query").with(as(ADA)))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousCannotDeleteAnything() throws Exception {
        ObjectId own = commentBy(ADA);

        mockMvc.perform(delete(uri(own)))
                .andExpect(status().isUnauthorized());

        assertThat(comments.findById(own)).isPresent();
    }

    private ObjectId commentBy(String email) {
        Comment comment = mongoTemplate.findAll(Comment.class).stream()
                .filter(candidate -> email.equals(candidate.getEmail()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no fixture comment for " + email));
        return comment.getId();
    }

    private static String uri(ObjectId commentId) {
        return "/api/movies/%s/comments/%s"
                .formatted(SampleFixtures.GODFATHER.toHexString(), commentId.toHexString());
    }

    /** A bearer identity without a login: ownership only ever reads {@code authentication.name}. */
    private static RequestPostProcessor as(String email) {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(jwt -> jwt.subject(email).claim("roles", List.of("USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
