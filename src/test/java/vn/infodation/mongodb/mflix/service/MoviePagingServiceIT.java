package vn.infodation.mongodb.mflix.service;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Slice;
import org.springframework.data.mongodb.core.MongoTemplate;

import vn.infodation.mongodb.common.CursorCodec;
import vn.infodation.mongodb.common.ExplainSummary;
import vn.infodation.mongodb.common.KeysetPage;
import vn.infodation.mongodb.config.IndexConfig;
import vn.infodation.mongodb.mflix.dto.MovieDetail;
import vn.infodation.mongodb.mflix.dto.MovieSearchCriteria;
import vn.infodation.mongodb.support.AbstractMongoIntegrationTest;
import vn.infodation.mongodb.support.SampleFixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 4. */
class MoviePagingServiceIT extends AbstractMongoIntegrationTest {

    /**
     * Excludes the fixture whose {@code year} is a string. Keyset paging needs a totally
     * ordered sort key, and MongoDB brackets comparisons by BSON type - a {@code year < 1999}
     * predicate can never match a string, so that document would be invisible to every page
     * after the first. This filter is the lesson, not a workaround.
     */
    private static final MovieSearchCriteria WELL_TYPED_YEARS =
            new MovieSearchCriteria(null, null, 1900, null, null, null);

    @Autowired
    MoviePagingService pagingService;

    @Autowired
    MongoTemplate mongoTemplate;

    @BeforeEach
    void setUp() {
        SampleFixtures.reset(mongoTemplate);
    }

    /** Phase 4.4 / 4.5 - both keyset implementations walk the collection identically. */
    @Test
    void keysetWalksEveryDocumentExactlyOnce() {
        assertThat(titlesByCursor(false)).containsExactly(
                "The Matrix", "Titanic", "Goodfellas", "The Godfather: Part II", "The Godfather");
    }

    @Test
    void springWindowScrollAgreesWithTheHandWrittenKeyset() {
        assertThat(titlesByCursor(true)).isEqualTo(titlesByCursor(false));
    }

    @Test
    void keysetPagesHaveNoDuplicatesAndNoGaps() {
        List<String> walked = titlesByCursor(false);

        assertThat(walked).doesNotHaveDuplicates();
        assertThat(walked).hasSize(5);
    }

    /** Phase 4.3 - a Slice knows whether there is more, and nothing else. */
    @Test
    void sliceReportsHasNextWithoutATotal() {
        Slice<MovieDetail> first = pagingService.slice(WELL_TYPED_YEARS, 0, 2);
        Slice<MovieDetail> last = pagingService.slice(WELL_TYPED_YEARS, 2, 2);

        assertThat(first.getContent()).hasSize(2);
        assertThat(first.hasNext()).isTrue();
        assertThat(last.getContent()).hasSize(1);
        assertThat(last.hasNext()).isFalse();
    }

    /** Phase 4.6 - the cursor is opaque, and a broken one is a 400 rather than a 500. */
    @Test
    void cursorRoundTripsAndRejectsGarbage() {
        org.bson.types.ObjectId id = SampleFixtures.MATRIX;
        String cursor = CursorCodec.encode(1999, id);

        assertThat(cursor).doesNotContain("1999").doesNotContain(id.toHexString());
        assertThat(CursorCodec.decode(cursor)).isEqualTo(new CursorCodec.Position(1999, id));

        assertThatThrownBy(() -> CursorCodec.decode("not-a-cursor"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lastPageReturnsNoCursor() {
        KeysetPage<MovieDetail> page = pagingService.keyset(WELL_TYPED_YEARS, null, 50);

        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    /**
     * Phase 4.7 - the keyset query rides its own index and reads only its own page.
     * <p>
     * Not the Phase 3 compound index: that one starts with {@code genres}, and this query has
     * no genre predicate. Paging on {@code {year, _id}} needs an index on {@code {year, _id}}.
     */
    @Test
    void keysetQueryUsesTheSortIndex() {
        KeysetPage<MovieDetail> first = pagingService.keyset(WELL_TYPED_YEARS, null, 2);
        ExplainSummary summary = pagingService.explainKeyset(WELL_TYPED_YEARS, first.nextCursor(), 2);

        assertThat(summary.indexName()).isEqualTo(IndexConfig.MOVIES_YEAR_ID);
        assertThat(summary.returned()).isEqualTo(2);
        // keysExamined, not docsExamined, is where deep offset paging actually hurts: the index
        // supplies the sort either way, so both read 20 documents, but `skip` walks every key it
        // discards. Measured on the full 21k collection (docs/notes/04-paging.md): page 500 by
        // offset examines 10,020 keys, the same page by cursor examines 21.
        assertThat(summary.keysExamined()).isLessThanOrEqualTo(5);
    }

    /** Phase 4.2 - the benchmark runs and reports both halves of the offset cost. */
    @Test
    void benchmarkSeparatesFindTimeFromCountTime() {
        var result = pagingService.benchmark(WELL_TYPED_YEARS, 2, 2);

        assertThat(result).containsKeys(
                "offsetPage0", "offsetPageDeep", "keysetPage0", "keysetPageDeep", "explainOffsetDeep");

        @SuppressWarnings("unchecked")
        var offsetPage0 = (java.util.Map<String, Object>) result.get("offsetPage0");
        assertThat(offsetPage0).containsKeys("findMillis", "countMillis", "totalElements");
        assertThat(offsetPage0.get("totalElements")).isEqualTo(5L);
    }

    private List<String> titlesByCursor(boolean useSpringWindow) {
        List<String> titles = new ArrayList<>();
        String cursor = null;

        for (int guard = 0; guard < 20; guard++) {
            KeysetPage<MovieDetail> page = useSpringWindow
                    ? pagingService.scroll(WELL_TYPED_YEARS, cursor, 2)
                    : pagingService.keyset(WELL_TYPED_YEARS, cursor, 2);
            page.items().forEach(movie -> titles.add(movie.title()));
            cursor = page.nextCursor();
            if (cursor == null) {
                return titles;
            }
        }
        throw new AssertionError("cursor walk did not terminate");
    }
}
