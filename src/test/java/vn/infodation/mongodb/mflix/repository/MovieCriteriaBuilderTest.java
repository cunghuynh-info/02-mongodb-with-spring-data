package vn.infodation.mongodb.mflix.repository;

import java.util.List;
import java.util.regex.Pattern;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import vn.infodation.mongodb.mflix.dto.MovieSearchCriteria;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2.2 - the query document is asserted without a database. Fast, and it catches the
 * mistakes that matter: a predicate silently dropped, or a user's input leaking into the regex.
 */
class MovieCriteriaBuilderTest {

    @Test
    void emptyFilterProducesNoPredicates() {
        Document query = MovieCriteriaBuilder.build(MovieSearchCriteria.empty()).getCriteriaObject();

        assertThat(query).isEmpty();
    }

    @Test
    void onlyPopulatedFieldsContribute() {
        MovieSearchCriteria filter = new MovieSearchCriteria(
                null, List.of("Drama"), 1990, null, null, null);

        Document query = MovieCriteriaBuilder.build(filter).getCriteriaObject();

        assertThat(query).containsOnlyKeys("$and");
        List<?> and = query.getList("$and", Document.class);
        assertThat(and).hasSize(2);
        assertThat(and.toString()).contains("genres").contains("year").doesNotContain("imdb");
    }

    @Test
    void yearRangeCollapsesIntoOnePredicate() {
        MovieSearchCriteria filter = new MovieSearchCriteria(
                null, null, 1990, 1999, null, null);

        List<Document> and = MovieCriteriaBuilder.build(filter).getCriteriaObject()
                .getList("$and", Document.class);

        assertThat(and).hasSize(1);
        assertThat(and.get(0).get("year", Document.class))
                .containsEntry("$gte", 1990)
                .containsEntry("$lte", 1999);
    }

    @Test
    void titleIsQuotedSoRegexMetacharactersAreLiteral() {
        MovieSearchCriteria filter = new MovieSearchCriteria(
                "(500) Days", null, null, null, null, null);

        String json = MovieCriteriaBuilder.build(filter).getCriteriaObject().toJson();

        // \Q...\E rather than an unescaped "(" that would blow up as a regex group.
        assertThat(json).contains("\\\\Q(500) Days\\\\E");
    }

    @Test
    void castMatchesAnyArrayElementWithoutElemMatch() {
        MovieSearchCriteria filter = new MovieSearchCriteria(
                null, null, null, null, null, "Al Pacino");

        List<Document> and = MovieCriteriaBuilder.build(filter).getCriteriaObject()
                .getList("$and", Document.class);

        // Regression guard: an earlier version used elemMatch(new Criteria().regex(..)), which
        // renders as an empty {$elemMatch: {}} and silently matches nothing.
        // Criteria.regex stores a java.util.regex.Pattern, not a {$regex: ..} sub-document.
        Object cast = and.get(0).get("cast");
        assertThat(cast).isInstanceOf(Pattern.class);
        assertThat(((Pattern) cast).pattern()).isEqualTo("\\QAl Pacino\\E");
        assertThat(((Pattern) cast).flags() & Pattern.CASE_INSENSITIVE).isNotZero();
    }
}
