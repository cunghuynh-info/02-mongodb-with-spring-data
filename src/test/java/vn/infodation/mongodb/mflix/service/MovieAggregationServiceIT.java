package vn.infodation.mongodb.mflix.service;

import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

import vn.infodation.mongodb.mflix.dto.GenreStats;
import vn.infodation.mongodb.mflix.dto.MostCommentedMovie;
import vn.infodation.mongodb.mflix.dto.MovieSearchCriteria;
import vn.infodation.mongodb.support.AbstractMongoIntegrationTest;
import vn.infodation.mongodb.support.SampleFixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Phases 2.5 - 2.7. Every assertion is a number the fixtures pin down. */
class MovieAggregationServiceIT extends AbstractMongoIntegrationTest {

    @Autowired
    MovieAggregationService aggregationService;

    @Autowired
    MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        SampleFixtures.reset(mongoTemplate);
    }

    @Test
    void genreStatsCountsEachGenreOnce() {
        List<GenreStats> stats = aggregationService.genreStats(20);

        // Drama: Godfather, Godfather II, Goodfellas, Titanic and the malformed one = 5.
        GenreStats drama = byGenre(stats, "Drama");
        assertThat(drama.movieCount()).isEqualTo(5);
        assertThat(drama.latestYear()).isEqualTo(2005);

        GenreStats crime = byGenre(stats, "Crime");
        assertThat(crime.movieCount()).isEqualTo(3);
        // (9.2 + 9.0 + 8.7) / 3 - the empty-string rating is ignored by $avg, not counted as 0.
        assertThat(crime.averageRating()).isCloseTo(8.9667, within(0.001));

        assertThat(stats).isSortedAccordingTo(
                (a, b) -> Long.compare(b.movieCount(), a.movieCount()));
    }

    @Test
    void browseReturnsFacetsAndAPageInOneRoundTrip() {
        Document result = aggregationService.browse(MovieSearchCriteria.empty(), 3);

        assertThat(result).containsKeys("genres", "decades", "top");

        // Five of the six fixtures have a numeric rating; the "" one is type-filtered out of
        // the top list, so asking for 3 of 5 still gives 3, ranked by rating.
        List<Document> top = result.getList("top", Document.class);
        assertThat(top).hasSize(3);
        // Goodfellas and The Matrix both sit at 8.7, so only the first two places are
        // deterministic - asserting the third would be asserting an arbitrary tiebreak.
        assertThat(top).extracting(d -> d.getString("title"))
                .startsWith("The Godfather", "The Godfather: Part II");
        assertThat(top).allSatisfy(d -> assertThat(d.getDouble("rating")).isGreaterThanOrEqualTo(8.7));

        List<Document> genres = result.getList("genres", Document.class);
        assertThat(genres.get(0)).containsEntry("_id", "Drama").containsEntry("count", 5);

        // The malformed document has a string year, so it lands in the default bucket.
        List<Document> decades = result.getList("decades", Document.class);
        assertThat(decades).anySatisfy(bucket -> assertThat(bucket).containsEntry("_id", "other"));
    }

    @Test
    void mostCommentedCountsOnTheServerWithoutJoiningEveryComment() {
        List<MostCommentedMovie> result = aggregationService.mostCommented(1900, 1, 10);

        assertThat(result).extracting(MostCommentedMovie::title)
                .containsExactly("The Godfather", "Titanic");
        assertThat(result.get(0).commentCount()).isEqualTo(SampleFixtures.GODFATHER_COMMENTS);
        assertThat(result.get(0).id()).isEqualTo(SampleFixtures.GODFATHER.toHexString());
    }

    @Test
    void mostCommentedRespectsTheMinimumThreshold() {
        assertThat(aggregationService.mostCommented(1900, 4, 10)).isEmpty();
    }

    private static GenreStats byGenre(List<GenreStats> stats, String genre) {
        return stats.stream()
                .filter(s -> genre.equals(s.genre()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no stats for genre " + genre));
    }
}
