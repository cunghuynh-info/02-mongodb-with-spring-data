package vn.infodation.mongodb.mflix.service;

import java.util.List;

import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import vn.infodation.mongodb.common.RawStage;
import vn.infodation.mongodb.mflix.dto.GenreStats;
import vn.infodation.mongodb.mflix.dto.MostCommentedMovie;
import vn.infodation.mongodb.mflix.dto.MovieSearchCriteria;
import vn.infodation.mongodb.mflix.repository.MovieCriteriaBuilder;

/**
 * Phase 2.5 - 2.7. Every pipeline here runs against the collection by name rather than against
 * {@code Movie.class}: a typed context validates every field reference against the entity, and
 * stages that invent fields ({@code commentCount}) are rejected. Untyped input, typed output.
 */
@Service
@RequiredArgsConstructor
public class MovieAggregationService {

    private static final String MOVIES = "movies";

    /**
     * Phase 2.9 - allowDiskUse lets a group spill past the 100 MB in-memory stage limit. On this
     * dataset nothing spills, but leaving it off is how an aggregation that worked in dev dies
     * in production.
     */
    private static final AggregationOptions OPTIONS = AggregationOptions.builder()
            .allowDiskUse(true)
            .cursorBatchSize(200)
            .build();

    private final MongoTemplate mongoTemplate;

    /** Phase 2.5 - one row per genre. */
    public List<GenreStats> genreStats(int limit) {
        Aggregation aggregation = Aggregation.newAggregation(
                        Aggregation.match(Criteria.where("genres").exists(true)),
                        Aggregation.unwind("genres"),
                        Aggregation.group("genres")
                                .count().as("movieCount")
                                .avg("imdb.rating").as("averageRating")
                                .max("year").as("latestYear"),
                        Aggregation.project("movieCount", "averageRating", "latestYear")
                                .and("_id").as("genre")
                                .andExclude("_id"),
                        Aggregation.sort(Sort.Direction.DESC, "movieCount"),
                        Aggregation.limit(limit))
                .withOptions(OPTIONS);

        return mongoTemplate.aggregate(aggregation, MOVIES, GenreStats.class).getMappedResults();
    }

    /**
     * Phase 2.6 - one round trip producing the three things a browse screen needs: the facet
     * counts, the decade histogram and the first page of results.
     */
    public Document browse(MovieSearchCriteria filter, int pageSize) {
        Aggregation aggregation = Aggregation.newAggregation(
                        Aggregation.match(MovieCriteriaBuilder.build(filter)),
                        Aggregation.facet(
                                        Aggregation.unwind("genres"),
                                        Aggregation.sortByCount("genres"),
                                        Aggregation.limit(10))
                                .as("genres")
                                .and(Aggregation.bucket("year")
                                        // "other" catches both the pre-1900 films and the few
                                        // documents where year is stored as a string.
                                        .withBoundaries(1900, 1950, 1970, 1990, 2000, 2010, 2020)
                                        .withDefaultBucket("other")
                                        .andOutputCount().as("count"))
                                .as("decades")
                                // gt(0) is not a quality filter, it is a type filter. BSON sorts
                                // strings above numbers, so without it the documents whose
                                // imdb.rating is "" take every top slot. Comparison operators
                                // are type-bracketed, so this drops them.
                                .and(Aggregation.match(Criteria.where("imdb.rating").gt(0)),
                                        Aggregation.sort(Sort.Direction.DESC, "imdb.rating"),
                                        Aggregation.limit(pageSize),
                                        Aggregation.project("title", "year")
                                                .and("imdb.rating").as("rating"))
                                .as("top"))
                .withOptions(OPTIONS);

        Document result = mongoTemplate.aggregate(aggregation, MOVIES, Document.class)
                .getUniqueMappedResult();
        return result == null ? new Document() : result;
    }

    /**
     * Phase 2.7 - {@code $lookup} with a sub-pipeline that counts on the server. The naive
     * version joins every comment document into the movie just to call {@code $size} on it;
     * this one ships back a single number per movie.
     * <p>
     * The {@code num_mflix_comments} predicate is not decoration. {@code $lookup} resolves the
     * foreign field once per input document, and {@code sample_mflix.comments} ships with only
     * an {@code _id} index - so joining ~6000 candidate movies means ~6000 scans of 41k
     * comments, and the query never returns. Narrowing on the denormalised counter first
     * (Phase 1.5) cuts the input to a handful of documents.
     * <p>
     * The counter is maintained on write, so it can drift if someone writes comments around
     * the application; the {@code $lookup} count is still the authoritative number returned.
     * Phase 3 adds the index on {@code comments.movie_id} that makes the prefilter optional.
     */
    public List<MostCommentedMovie> mostCommented(int fromYear, int minComments, int limit) {
        Aggregation aggregation = Aggregation.newAggregation(
                        Aggregation.match(Criteria.where("year").gte(fromYear)
                                .and("num_mflix_comments").gte(minComments)),
                        RawStage.of("""
                                { "$lookup": {
                                    "from": "comments",
                                    "localField": "_id",
                                    "foreignField": "movie_id",
                                    "pipeline": [ { "$count": "count" } ],
                                    "as": "commentStats"
                                } }"""),
                        RawStage.of("""
                                { "$set": {
                                    "commentCount": { "$ifNull": [ { "$first": "$commentStats.count" }, 0 ] }
                                } }"""),
                        Aggregation.match(Criteria.where("commentCount").gte(minComments)),
                        Aggregation.sort(Sort.Direction.DESC, "commentCount"),
                        Aggregation.limit(limit),
                        // _id is kept deliberately. Aliasing it to "id" and excluding "_id"
                        // produces the right BSON but reads back as null: Spring treats an
                        // "id" property as the entity identifier and looks for it in _id.
                        Aggregation.project("title", "year", "commentCount"))
                .withOptions(OPTIONS);

        return mongoTemplate.aggregate(aggregation, MOVIES, MostCommentedMovie.class).getMappedResults();
    }
}
