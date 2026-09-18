package vn.infodation.mongodb.mflix.service;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

import vn.infodation.mongodb.mflix.domain.Movie;
import vn.infodation.mongodb.mflix.dto.CommentView;
import vn.infodation.mongodb.mflix.dto.MovieWithComments;
import vn.infodation.mongodb.mflix.repository.MovieRepository;
import vn.infodation.mongodb.support.AbstractMongoIntegrationTest;
import vn.infodation.mongodb.support.SampleFixtures;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 1 - the three read strategies must agree, and the bounded embed must stay bounded. */
class CommentServiceIT extends AbstractMongoIntegrationTest {

    @Autowired
    CommentService commentService;

    @Autowired
    MovieRepository movieRepository;

    @Autowired
    MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        SampleFixtures.reset(mongoTemplate);
    }

    @Test
    void allThreeStrategiesReturnTheSameComments() {
        MovieWithComments manual = commentService.manual(SampleFixtures.GODFATHER, 10);
        MovieWithComments reference = commentService.viaReference(SampleFixtures.GODFATHER, 10);
        MovieWithComments lookup = commentService.viaLookup(SampleFixtures.GODFATHER, 10);

        assertThat(manual.comments()).hasSize(SampleFixtures.GODFATHER_COMMENTS);
        assertThat(ids(reference.comments())).containsExactlyElementsOf(ids(manual.comments()));
        assertThat(ids(lookup.comments())).containsExactlyElementsOf(ids(manual.comments()));
        assertThat(lookup.movie().title()).isEqualTo("The Godfather");
    }

    @Test
    void referencedCommentsAreNotStoredOnTheMovieDocument() {
        // The whole point of referencing: the movie document is unchanged by comment volume.
        org.bson.Document raw = mongoTemplate.getCollection("movies")
                .find(new org.bson.Document("_id", SampleFixtures.GODFATHER))
                .first();

        assertThat(raw).isNotNull();
        assertThat(raw).doesNotContainKey("comments");
        assertThat(mongoTemplate.getCollection("comments").countDocuments()).isEqualTo(4);
    }

    @Test
    void eagerReferenceResolvesTheMovieOnEachComment() {
        List<CommentView> comments = commentService.viaEagerReference(SampleFixtures.GODFATHER, 10);

        assertThat(comments).hasSize(SampleFixtures.GODFATHER_COMMENTS);
        assertThat(comments).extracting(CommentView::name)
                .contains("Ada Lovelace", "Grace Hopper", "Alan Turing");
    }

    @Test
    void lookupLimitIsAppliedOnTheServer() {
        MovieWithComments lookup = commentService.viaLookup(SampleFixtures.GODFATHER, 2);

        assertThat(lookup.comments()).hasSize(2);
        assertThat(lookup.queryCount()).isEqualTo(1);
    }

    @Test
    void addingACommentBumpsTheCounterAndKeepsTheEmbedCapped() {
        int extra = 7;
        for (int i = 0; i < extra; i++) {
            commentService.addComment(SampleFixtures.GODFATHER, "Writer " + i,
                    "writer%d@example.com".formatted(i), "comment " + i);
        }

        Movie movie = movieRepository.findById(SampleFixtures.GODFATHER).orElseThrow();

        assertThat(movie.getNumMflixComments())
                .isEqualTo(SampleFixtures.GODFATHER_COMMENTS + extra);
        assertThat(movie.getRecentComments()).hasSize(CommentService.RECENT_COMMENTS_KEPT);
        // $slice: -n keeps the tail, so the newest survive and the oldest fall off.
        assertThat(movie.getRecentComments())
                .extracting(c -> c.getName())
                .containsExactly("Writer 2", "Writer 3", "Writer 4", "Writer 5", "Writer 6");

        // The full list is still in the comments collection - nothing was lost.
        assertThat(commentService.commentsFor(SampleFixtures.GODFATHER,
                org.springframework.data.domain.PageRequest.of(0, 50)).totalElements())
                .isEqualTo(SampleFixtures.GODFATHER_COMMENTS + extra);
    }

    private static List<String> ids(List<CommentView> comments) {
        return comments.stream().map(CommentView::id).toList();
    }
}
