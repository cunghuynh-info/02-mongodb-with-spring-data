package vn.infodation.mongodb.search;

import java.time.Duration;
import java.util.List;

import org.awaitility.Awaitility;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

import vn.infodation.mongodb.support.AbstractMongoIntegrationTest;
import vn.infodation.mongodb.support.SampleFixtures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8. Slower than the rest of the suite: mongot has to build the index before anything
 * can be queried, and there is no way to make that instant.
 * <p>
 * The fixtures are seeded once for the class rather than per test - re-seeding would mean
 * waiting for the index to catch up again each time.
 */
class SearchServiceIT extends AbstractMongoIntegrationTest {

    private static final Duration INDEX_TIMEOUT = Duration.ofMinutes(3);
    private static boolean indexReady;

    @Autowired
    SearchService searchService;

    @Autowired
    SearchIndexService indexService;

    @Autowired
    MongoTemplate mongoTemplate;

    @BeforeAll
    static void resetFlag() {
        indexReady = false;
    }

    /** Runs before each test but does its work once. */
    @org.junit.jupiter.api.BeforeEach
    void ensureIndex() {
        if (indexReady) {
            return;
        }
        SampleFixtures.reset(mongoTemplate);
        indexService.ensureMoviesIndex();
        assertThat(indexService.awaitQueryable(SearchIndexService.MOVIES_INDEX, INDEX_TIMEOUT))
                .as("search index did not become queryable")
                .isTrue();

        // Queryable is not the same as caught up: mongot indexes asynchronously, so wait until
        // it can actually see the fixtures before asserting on results.
        Awaitility.await().atMost(Duration.ofMinutes(1))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> !searchService.search("Godfather", 5).isEmpty());
        indexReady = true;
    }

    /** Phase 8.1 / 8.2 - hits come back scored, with highlights. */
    @Test
    void textSearchReturnsScoredResults() {
        List<Document> hits = searchService.search("Godfather", 5);

        assertThat(hits).isNotEmpty();
        assertThat(hits).extracting(hit -> hit.getString("title"))
                .anyMatch(title -> title.contains("Godfather"));
        assertThat(hits.get(0).getDouble("score")).isPositive();
        // Scores are descending - that is what $search sorts by unless told otherwise.
        assertThat(hits).extracting(hit -> hit.getDouble("score"))
                .isSortedAccordingTo((a, b) -> Double.compare(b, a));
    }

    /** Phase 8.3 - a prefix is enough, which is the whole point of the autocomplete mapping. */
    @Test
    void autocompleteMatchesOnAPrefix() {
        List<Document> hits = searchService.autocomplete("God", 5);

        assertThat(hits).extracting(hit -> hit.getString("title"))
                .anyMatch(title -> title.startsWith("The Godfather"));
    }

    /** Phase 8.4 - a filter narrows without touching the ranking. */
    @Test
    void compoundFilterExcludesNonMatchingGenres() {
        List<Document> crime = searchService.compound("Godfather", "Crime", null, 10);
        List<Document> romance = searchService.compound("Godfather", "Romance", null, 10);

        assertThat(crime).isNotEmpty();
        assertThat(crime).allSatisfy(hit ->
                assertThat(hit.getList("genres", String.class)).contains("Crime"));
        assertThat(romance).isEmpty();
    }

    /** Phase 8.5 - facet counts without materialising a document. */
    @Test
    void searchMetaReturnsFacetCounts() {
        Document meta = searchService.facets("plot");

        Document facet = meta.get("facet", Document.class);
        assertThat(facet).isNotNull();
        assertThat(facet).containsKeys("genreFacet", "decadeFacet");

        List<Document> genreBuckets = facet.get("genreFacet", Document.class)
                .getList("buckets", Document.class);
        assertThat(genreBuckets).isNotEmpty();
        assertThat(genreBuckets).allSatisfy(bucket -> assertThat(bucket).containsKey("count"));
    }

    /** Phase 8.6 - searchAfter walks without $skip, and does not repeat a hit. */
    @Test
    void searchAfterPagesWithoutRepeating() {
        Document first = searchService.page("plot", null, 2);
        List<Document> firstItems = first.getList("items", Document.class);
        assertThat(firstItems).hasSize(2);

        String token = first.getString("nextToken");
        assertThat(token).isNotBlank();

        Document second = searchService.page("plot", token, 2);
        List<Document> secondItems = second.getList("items", Document.class);

        assertThat(secondItems).isNotEmpty();
        assertThat(ids(secondItems)).doesNotContainAnyElementsOf(ids(firstItems));
    }

    /**
     * Phase 8.7 - fuzzy matching, and the trap in it.
     * <p>
     * {@code maxEdits: 1} does <em>not</em> find "Godfathar", even though it is one character
     * from "Godfather". The edit distance is measured against the <em>indexed term</em>, and
     * {@code title} uses {@code lucene.english}, which stems "Godfather" to "godfath" - verified
     * by the fact that searching "godfath" and "godfathers" both hit the same documents.
     * "godfathar" is two edits from "godfath", so the budget has to be 2.
     */
    @Test
    void fuzzyEditDistanceIsMeasuredAgainstTheStemmedTerm() {
        assertThat(searchService.fuzzy("Godfathar", 1, 5)).isEmpty();

        assertThat(searchService.fuzzy("Godfathar", 2, 5))
                .extracting(hit -> hit.getString("title"))
                .anyMatch(title -> title.contains("Godfather"));

        // The stem is what is indexed: a plain search for it matches.
        assertThat(searchService.search("godfath", 5))
                .extracting(hit -> hit.getString("title"))
                .anyMatch(title -> title.contains("Godfather"));
    }

    private static List<String> ids(List<Document> hits) {
        return hits.stream().map(hit -> String.valueOf(hit.get("_id"))).toList();
    }
}
