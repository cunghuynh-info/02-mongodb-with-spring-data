package vn.infodation.mongodb.mflix.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import vn.infodation.mongodb.common.CursorCodec;
import vn.infodation.mongodb.common.ExplainSummary;
import vn.infodation.mongodb.common.KeysetPage;
import vn.infodation.mongodb.mflix.domain.Movie;
import vn.infodation.mongodb.mflix.dto.MovieDetail;
import vn.infodation.mongodb.mflix.dto.MovieSearchCriteria;
import vn.infodation.mongodb.mflix.repository.MovieCriteriaBuilder;

/**
 * Phase 4 - offset and keyset, side by side.
 * <p>
 * The sort is always {@code {year: -1, _id: -1}}. The {@code _id} is not decoration: without a
 * unique tiebreaker, two movies from the same year have no defined order, so the boundary
 * between page n and page n+1 can move between requests and a row is duplicated or skipped.
 * <p>
 * Keyset also requires the sort key to be totally ordered. MongoDB brackets comparisons by BSON
 * type, so the handful of {@code sample_mflix} documents storing {@code year} as a string are
 * invisible to a {@code year < x} predicate and simply never appear. Filter to one type, or
 * page on a field you control.
 */
@Service
@RequiredArgsConstructor
public class MoviePagingService {

    /**
     * Property names, not field names: Spring Data's keyset scrolling matches the sort against
     * the entity's properties and looks up each one in the position map, so the tiebreaker has
     * to be spelled {@code id} rather than {@code _id}. The mapper translates it on the way out.
     */
    public static final Sort KEYSET_SORT = Sort.by(Sort.Order.desc("year"), Sort.Order.desc("id"));

    /** The same sort as stored fields, for the raw explain calls that bypass the mapper. */
    private static final org.bson.Document KEYSET_SORT_FIELDS =
            new org.bson.Document("year", -1).append("_id", -1);

    private final MongoTemplate mongoTemplate;

    /** Phase 4.3 - a Slice: same skip/limit, but no count query behind it. */
    public Slice<MovieDetail> slice(MovieSearchCriteria filter, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, KEYSET_SORT);
        Query query = new Query(MovieCriteriaBuilder.build(filter)).with(pageable);

        // Ask for one more than the page size; its presence is what "hasNext" means.
        query.limit(size + 1);
        List<Movie> movies = mongoTemplate.find(query, Movie.class);

        boolean hasNext = movies.size() > size;
        List<MovieDetail> content = movies.stream()
                .limit(size)
                .map(MovieDetail::from)
                .toList();
        return new org.springframework.data.domain.SliceImpl<>(content, pageable, hasNext);
    }

    /**
     * Phase 4.4 - Spring Data's own keyset support. {@code Window} carries the position for the
     * next call, so the {@code $or} predicate never has to be written by hand.
     */
    public KeysetPage<MovieDetail> scroll(MovieSearchCriteria filter, String cursor, int size) {
        ScrollPosition position = cursor == null
                ? ScrollPosition.keyset()
                : ScrollPosition.forward(keysOf(cursor));

        Query query = new Query(MovieCriteriaBuilder.build(filter)).with(KEYSET_SORT).limit(size);
        Window<Movie> window = mongoTemplate.query(Movie.class).matching(query).scroll(position);

        List<Movie> movies = window.getContent();
        return page(movies, movies.size(), window.hasNext(), size);
    }

    /**
     * Phase 4.5 - the same thing by hand, so the predicate is visible:
     * {@code (year < :y) OR (year = :y AND _id < :id)}.
     */
    public KeysetPage<MovieDetail> keyset(MovieSearchCriteria filter, String cursor, int size) {
        Query query = new Query(keysetCriteria(filter, cursor)).with(KEYSET_SORT).limit(size + 1);
        List<Movie> movies = mongoTemplate.find(query, Movie.class);

        boolean hasNext = movies.size() > size;
        return page(movies, Math.min(movies.size(), size), hasNext, size);
    }

    /**
     * Phase 4.2 / 4.7 - the measurement. Offset paging at depth pays twice: the server walks
     * and discards every skipped document, and the count query scans again. Keyset examines
     * exactly one page regardless of how deep you are.
     */
    public Map<String, Object> benchmark(MovieSearchCriteria filter, int size, int deepPage) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("size", size);
        result.put("deepPage", deepPage);

        result.put("offsetPage0", timeOffset(filter, 0, size));
        result.put("offsetPageDeep", timeOffset(filter, deepPage, size));
        result.put("keysetPage0", timeKeyset(filter, null, size));

        // Walk to the same depth with the cursor, then time one more page from there.
        String cursor = null;
        for (int i = 0; i < deepPage; i++) {
            KeysetPage<MovieDetail> page = keyset(filter, cursor, size);
            cursor = page.nextCursor();
            if (cursor == null) {
                break;
            }
        }
        result.put("keysetPageDeep", timeKeyset(filter, cursor, size));

        result.put("explainOffsetDeep", explainOffset(filter, deepPage, size));
        return result;
    }

    private Map<String, Object> timeOffset(MovieSearchCriteria filter, int page, int size) {
        long start = System.nanoTime();
        Query query = new Query(MovieCriteriaBuilder.build(filter))
                .with(PageRequest.of(page, size, KEYSET_SORT));
        int returned = mongoTemplate.find(query, Movie.class).size();
        long found = System.nanoTime();
        long total = mongoTemplate.count(Query.of(query).limit(-1).skip(-1), Movie.class);
        long end = System.nanoTime();

        return Map.of(
                "returned", returned,
                "totalElements", total,
                "findMillis", millis(start, found),
                "countMillis", millis(found, end),
                "totalMillis", millis(start, end));
    }

    private Map<String, Object> timeKeyset(MovieSearchCriteria filter, String cursor, int size) {
        long start = System.nanoTime();
        KeysetPage<MovieDetail> page = keyset(filter, cursor, size);
        long end = System.nanoTime();
        return Map.of(
                "returned", page.items().size(),
                "hasNext", page.hasNext(),
                "totalMillis", millis(start, end));
    }

    private ExplainSummary explainOffset(MovieSearchCriteria filter, int page, int size) {
        Query query = new Query(MovieCriteriaBuilder.build(filter))
                .with(PageRequest.of(page, size, KEYSET_SORT));
        return ExplainSummary.of(mongoTemplate.getCollection("movies")
                .find(query.getQueryObject())
                .sort(KEYSET_SORT_FIELDS)
                .skip(page * size)
                .limit(size)
                .explain(com.mongodb.ExplainVerbosity.EXECUTION_STATS));
    }

    /** Phase 4.7 - proves the keyset query rides the compound index at any depth. */
    public ExplainSummary explainKeyset(MovieSearchCriteria filter, String cursor, int size) {
        Query query = new Query(keysetCriteria(filter, cursor)).with(KEYSET_SORT).limit(size);
        return ExplainSummary.of(mongoTemplate.getCollection("movies")
                .find(query.getQueryObject())
                .sort(KEYSET_SORT_FIELDS)
                .limit(size)
                .explain(com.mongodb.ExplainVerbosity.EXECUTION_STATS));
    }

    private Criteria keysetCriteria(MovieSearchCriteria filter, String cursor) {
        List<Criteria> parts = new ArrayList<>();
        Criteria base = MovieCriteriaBuilder.build(filter);
        if (!base.getCriteriaObject().isEmpty()) {
            parts.add(base);
        }

        if (cursor != null) {
            CursorCodec.Position position = CursorCodec.decode(cursor);
            parts.add(new Criteria().orOperator(
                    Criteria.where("year").lt(position.year()),
                    new Criteria().andOperator(
                            Criteria.where("year").is(position.year()),
                            Criteria.where("_id").lt(position.id()))));
        }

        return parts.isEmpty() ? new Criteria() : new Criteria().andOperator(parts.toArray(Criteria[]::new));
    }

    private static Map<String, Object> keysOf(String cursor) {
        CursorCodec.Position position = CursorCodec.decode(cursor);
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("year", position.year());
        keys.put("id", position.id());
        return keys;
    }

    private static KeysetPage<MovieDetail> page(List<Movie> movies, int keep, boolean hasNext, int size) {
        List<Movie> kept = movies.subList(0, keep);
        String nextCursor = kept.isEmpty() || !hasNext
                ? null
                : CursorCodec.encode(kept.get(kept.size() - 1).getYear(), kept.get(kept.size() - 1).getId());
        return new KeysetPage<>(kept.stream().map(MovieDetail::from).toList(), nextCursor, hasNext, size);
    }

    private static long millis(long from, long to) {
        return (to - from) / 1_000_000;
    }
}
