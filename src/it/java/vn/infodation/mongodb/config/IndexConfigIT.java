package vn.infodation.mongodb.config;

import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import vn.infodation.mongodb.common.ExplainSummary;
import vn.infodation.mongodb.mflix.service.IndexInspectionService;
import vn.infodation.mongodb.support.AbstractMongoIntegrationTest;
import vn.infodation.mongodb.support.SampleFixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 3 - the indexes exist, and the planner actually uses them. */
class IndexConfigIT extends AbstractMongoIntegrationTest {

    @Autowired
    MongoTemplate mongoTemplate;

    @Autowired
    IndexInspectionService inspection;

    @Autowired
    IndexConfig indexConfig;

    @BeforeEach
    void setUp() {
        SampleFixtures.reset(mongoTemplate);
        // The runner already created these at context start; re-running proves it is idempotent.
        indexConfig.createAll();
    }

    @Test
    void indexCreationIsIdempotent() {
        indexConfig.createAll();
        indexConfig.createAll();

        assertThat(inspection.indexes("movies"))
                .extracting(index -> index.get("name"))
                .containsOnlyOnce(IndexConfig.MOVIES_BROWSE, IndexConfig.MOVIES_TOP_RATED);
    }

    /** Phase 3.3 - the compound index turns the Phase 2 filter into an index scan. */
    @Test
    void compoundIndexServesTheGenreYearQuery() {
        ExplainSummary summary = inspection.explain("movies",
                new Document("genres", "Crime")
                        .append("year", new Document("$gte", 1970).append("$lte", 1980)),
                new Document("year", -1),
                null);

        assertThat(summary.indexName()).isEqualTo(IndexConfig.MOVIES_BROWSE);
        assertThat(summary.returned()).isEqualTo(2);
        // The index is ordered by year already, so no in-memory SORT is needed.
        assertThat(summary.winningStage()).isNotEqualTo("COLLSCAN");
    }

    /**
     * Phase 3.3 - the prefix rule. A query on {@code year} alone cannot touch MOVIES_BROWSE,
     * however obviously {@code year} appears in it: an index is only usable from its leading
     * field inward, and that index starts with {@code genres}. This is exactly why Phase 4
     * had to add a second index for the paging sort.
     */
    @Test
    void aQueryThatSkipsTheIndexPrefixCannotUseThatIndex() {
        ExplainSummary summary = inspection.explain("movies",
                new Document("year", new Document("$gte", 1990)), null, null);

        assertThat(summary.indexName())
                .isNotEqualTo(IndexConfig.MOVIES_BROWSE)
                .isEqualTo(IndexConfig.MOVIES_YEAR_ID);
    }

    /** Phase 3.3 - and with no index at all on the field, it is a full scan. */
    @Test
    void anUnindexedFieldIsAlwaysACollectionScan() {
        ExplainSummary summary = inspection.explain("movies",
                new Document("rated", "R"), null, null);

        assertThat(summary.indexName()).isNull();
        assertThat(summary.winningStage()).isEqualTo("COLLSCAN");
    }

    /** Phase 3.4 - everything the projection asks for is in the index, so no document is read. */
    @Test
    void coveredQueryTouchesNoDocuments() {
        ExplainSummary summary = inspection.explain("movies",
                new Document("genres", "Crime"),
                new Document("year", -1),
                new Document("_id", 0).append("year", 1).append("imdb.rating", 1));

        assertThat(summary.indexName()).isEqualTo(IndexConfig.MOVIES_BROWSE);
        assertThat(summary.docsExamined()).isZero();
        assertThat(summary.returned()).isEqualTo(3);
    }

    /**
     * Phase 3.4 - the limit of covering. Adding {@code genres} back to the projection breaks it:
     * the index is multikey, holding one entry per array element rather than the array, so the
     * server has to fetch each document to rebuild it.
     */
    @Test
    void aMultikeyIndexCannotCoverAProjectionOfTheArrayField() {
        ExplainSummary summary = inspection.explain("movies",
                new Document("genres", "Crime"),
                new Document("year", -1),
                new Document("_id", 0).append("genres", 1).append("year", 1));

        assertThat(summary.indexName()).isEqualTo(IndexConfig.MOVIES_BROWSE);
        assertThat(summary.docsExamined()).isEqualTo(3);
    }

    /**
     * Phase 3.6 - a partial index only serves queries whose predicate implies its filter.
     * {@code rating >= 9} implies {@code rating > 7}; {@code rating >= 1} does not, even though
     * it would match a subset of the collection.
     */
    @Test
    void partialIndexIsOnlyUsedWhenTheQueryImpliesItsFilter() {
        ExplainSummary implies = inspection.explain("movies",
                new Document("imdb.rating", new Document("$gte", 9.0)), null, null);
        ExplainSummary doesNotImply = inspection.explain("movies",
                new Document("imdb.rating", new Document("$gte", 1.0)), null, null);

        assertThat(implies.indexName()).isEqualTo(IndexConfig.MOVIES_TOP_RATED);
        assertThat(doesNotImply.indexName()).isNull();
        assertThat(doesNotImply.winningStage()).isEqualTo("COLLSCAN");
    }

    /** Phase 3.5 - the partial index is a fraction of the size of the full one. */
    @Test
    void partialIndexIsSmallerThanTheCompoundOne() {
        Map<String, Object> partial = indexNamed("movies", IndexConfig.MOVIES_TOP_RATED);
        Map<String, Object> compound = indexNamed("movies", IndexConfig.MOVIES_BROWSE);

        assertThat(partial.get("partial")).isEqualTo(true);
        assertThat(compound.get("partial")).isEqualTo(false);
        assertThat(((Number) partial.get("sizeBytes")).longValue())
                .isLessThanOrEqualTo(((Number) compound.get("sizeBytes")).longValue());
    }

    /**
     * Phase 3.7 - unique, but only where the field exists. Any number of documents without an
     * email can coexist; two with the same email cannot.
     */
    @Test
    void uniquePartialIndexIgnoresDocumentsWithoutTheField() {
        mongoTemplate.remove(new Query(), IndexConfig.SUBSCRIBERS);

        mongoTemplate.insert(List.of(
                        new Document("name", "no email here"),
                        new Document("name", "none here either"),
                        new Document("name", "has one").append("email", "a@example.com")),
                IndexConfig.SUBSCRIBERS);

        assertThat(mongoTemplate.count(new Query(), IndexConfig.SUBSCRIBERS)).isEqualTo(3);

        assertThatThrownBy(() -> mongoTemplate.insert(
                new Document("name", "duplicate").append("email", "a@example.com"),
                IndexConfig.SUBSCRIBERS))
                .isInstanceOf(DuplicateKeyException.class);
    }

    /** Phase 3.8 - TTL and wildcard indexes exist with the options that were asked for. */
    @Test
    void ttlAndWildcardIndexesAreCreated() {
        List<Map<String, Object>> indexes = inspection.indexes(IndexConfig.SESSIONS);

        assertThat(indexes).extracting(index -> index.get("name"))
                .contains(IndexConfig.SESSIONS_TTL, IndexConfig.SESSIONS_WILDCARD);

        Document raw = mongoTemplate.getDb().getCollection(IndexConfig.SESSIONS)
                .listIndexes(Document.class).into(new java.util.ArrayList<>()).stream()
                .filter(index -> IndexConfig.SESSIONS_TTL.equals(index.getString("name")))
                .findFirst()
                .orElseThrow();
        assertThat(raw.get("expireAfterSeconds")).isNotNull();
    }

    private Map<String, Object> indexNamed(String collection, String name) {
        return inspection.indexes(collection).stream()
                .filter(index -> name.equals(index.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no index " + name + " on " + collection));
    }
}
