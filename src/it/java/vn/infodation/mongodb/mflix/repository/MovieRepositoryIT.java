package vn.infodation.mongodb.mflix.repository;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;

import vn.infodation.mongodb.common.ExplainSummary;
import vn.infodation.mongodb.config.IndexConfig;
import vn.infodation.mongodb.mflix.domain.Movie;
import vn.infodation.mongodb.mflix.dto.MovieSearchCriteria;
import vn.infodation.mongodb.mflix.dto.MovieSummary;
import vn.infodation.mongodb.support.AbstractMongoIntegrationTest;
import vn.infodation.mongodb.support.SampleFixtures;

import static org.assertj.core.api.Assertions.assertThat;

/** Phases 2.2 - 2.4 and 2.9. */
class MovieRepositoryIT extends AbstractMongoIntegrationTest {

    @Autowired
    MovieRepository movieRepository;

    @Autowired
    MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        SampleFixtures.reset(mongoTemplate);
    }

    @Test
    void filtersByGenreAndYearRange() {
        MovieSearchCriteria filter = new MovieSearchCriteria(
                null, List.of("Crime"), 1970, 1980, null, null);

        Page<Movie> page = movieRepository.search(filter, PageRequest.of(0, 10, Sort.by("year")));

        assertThat(page.getContent()).extracting(Movie::getTitle)
                .containsExactly("The Godfather", "The Godfather: Part II");
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void filtersByMinimumRating() {
        MovieSearchCriteria filter = new MovieSearchCriteria(null, null, null, null, 9.0, null);

        Page<Movie> page = movieRepository.search(filter, PageRequest.of(0, 10, Sort.by("title")));

        assertThat(page.getContent()).extracting(Movie::getTitle)
                .containsExactly("The Godfather", "The Godfather: Part II");
    }

    @Test
    void filtersByCastMember() {
        MovieSearchCriteria filter = new MovieSearchCriteria(null, null, null, null, null, "de niro");

        Page<Movie> page = movieRepository.search(filter, PageRequest.of(0, 10, Sort.by("year")));

        assertThat(page.getContent()).extracting(Movie::getTitle)
                .containsExactly("The Godfather: Part II", "Goodfellas");
    }

    @Test
    void titleMatchTreatsMetacharactersAsLiterals() {
        MovieSearchCriteria filter = new MovieSearchCriteria(
                "Godfather: Part", null, null, null, null, null);

        Page<Movie> page = movieRepository.search(filter, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(Movie::getTitle)
                .containsExactly("The Godfather: Part II");
    }

    @Test
    void readsTheMalformedDocumentWithoutThrowing() {
        Movie movie = movieRepository.findById(SampleFixtures.MALFORMED).orElseThrow();

        assertThat(movie.getYear()).isEqualTo(2005);
        assertThat(movie.getImdb().getRating()).isNull();
    }

    @Test
    void projectionReturnsOnlyTheRequestedFields() {
        List<MovieSummary> summaries = movieRepository.searchSummaries(
                new MovieSearchCriteria(null, List.of("Sci-Fi"), null, null, null, null),
                PageRequest.of(0, 10));

        assertThat(summaries).hasSize(1);
        MovieSummary matrix = summaries.get(0);
        assertThat(matrix.getTitle()).isEqualTo("The Matrix");
        assertThat(matrix.getYear()).isEqualTo(1999);
        assertThat(matrix.getImdb().getRating()).isEqualTo(8.7);
        assertThat(matrix.getId()).isEqualTo(SampleFixtures.MATRIX.toHexString());
    }

    @Test
    void incReturnsTheUpdatedDocument() {
        Movie updated = movieRepository.addVotes(SampleFixtures.MATRIX, 5).orElseThrow();

        assertThat(updated.getImdb().getVotes()).isEqualTo(170_005L);
    }

    @Test
    void addToSetIsIdempotentButPullIsNot() {
        movieRepository.addGenre(SampleFixtures.MATRIX, "Cyberpunk");
        Movie twice = movieRepository.addGenre(SampleFixtures.MATRIX, "Cyberpunk").orElseThrow();

        assertThat(twice.getGenres()).containsExactly("Action", "Sci-Fi", "Cyberpunk");

        Movie pulled = movieRepository.removeGenre(SampleFixtures.MATRIX, "Cyberpunk").orElseThrow();

        assertThat(pulled.getGenres()).containsExactly("Action", "Sci-Fi");
    }

    /**
     * Phase 2.9 / 3.3 - the search query rides the compound index from {@code IndexConfig}.
     * The "before" numbers, taken against the full collection with no index, are recorded in
     * {@code docs/notes/02-querying.md}: 21,349 documents examined to return 20.
     */
    @Test
    void explainShowsTheCompoundIndexServingTheSearch() {
        MovieSearchCriteria filter = new MovieSearchCriteria(
                null, List.of("Crime"), 1970, 1980, null, null);

        ExplainSummary summary = ExplainSummary.of(
                movieRepository.explainSearch(filter, PageRequest.of(0, 10, Sort.by("year"))));

        assertThat(summary.indexName()).isEqualTo(IndexConfig.MOVIES_BROWSE);
        assertThat(summary.returned()).isEqualTo(2);
        // Only the Crime entries are walked, not the whole collection.
        assertThat(summary.docsExamined()).isLessThanOrEqualTo(3);
    }
}
