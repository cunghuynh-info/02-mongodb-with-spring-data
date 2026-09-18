package vn.infodation.mongodb.config;

import java.time.Duration;
import java.util.List;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.index.WildcardIndex;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import vn.infodation.mongodb.security.domain.RefreshToken;
import vn.infodation.mongodb.security.service.AuthenticationEventListener;

/**
 * Phase 3.2 - every index in one place, created at startup and idempotent.
 * <p>
 * Deliberately not {@code @Indexed} annotations ({@code auto-index-creation} is off). Index
 * creation is a schema change: it wants to be reviewed, named, and visible in one file, not
 * scattered across entity fields where nobody notices a new one appearing.
 * <p>
 * Disable with {@code lab.index-creation.enabled=false}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "lab.index-creation.enabled", havingValue = "true", matchIfMissing = true)
public class IndexConfig implements ApplicationRunner {

    /** Phase 3.3 - ESR: equality on genres, sort on year, range on rating. */
    public static final String MOVIES_BROWSE = "movies_genres_year_rating";
    /** Phase 4.7 - the keyset sort key. */
    public static final String MOVIES_YEAR_ID = "movies_year_id";
    /** Phase 3.5 - only the movies worth ranking. */
    public static final String MOVIES_TOP_RATED = "movies_rating_gt7_partial";
    /** What makes the Phase 1 comment lookups and Phase 4 paging sane. */
    public static final String COMMENTS_BY_MOVIE = "comments_movie_date";
    /** Phase 3.5 - partial index on the minority of rows anyone queries. */
    public static final String INSPECTIONS_FAILED = "inspections_failed_partial";
    /** Phase 3.7 - unique, but only where the field is present. */
    public static final String SUBSCRIBERS_EMAIL = "subscribers_email_unique_partial";
    /** Phase 3.8 */
    public static final String SESSIONS_TTL = "sessions_ttl";
    public static final String SESSIONS_WILDCARD = "sessions_attributes_wildcard";
    /** Security 1.3 - what makes the registration race decidable. */
    public static final String USERS_EMAIL = "users_email_unique";
    /** Security 5.2 - expiry as a database concern. */
    public static final String REFRESH_TOKENS_TTL = "refresh_tokens_ttl";
    /** Security 7.3 - an auth log that cleans up after itself. */
    public static final String AUTH_EVENTS_TTL = "auth_events_ttl";

    /** Scratch collections for the index shapes that have no home in the sample data. */
    public static final String SUBSCRIBERS = "lab_subscribers";
    public static final String SESSIONS = "lab_sessions";

    /** The threshold baked into the partial filter; a query must imply it to use the index. */
    public static final double TOP_RATED_THRESHOLD = 7.0;

    private final MongoTemplate mflix;
    private final MongoTemplate training;

    public IndexConfig(MongoTemplate mflix, @Qualifier("trainingTemplate") MongoTemplate training) {
        this.mflix = mflix;
        this.training = training;
    }

    @Override
    public void run(ApplicationArguments args) {
        createAll();
    }

    /** Separated from {@link #run} so tests can force a rebuild without restarting the context. */
    public void createAll() {
        IndexOperations movies = mflix.indexOps("movies");

        // Field order is the whole game. Equality first so the index seeks straight to the
        // genre, then the sort field so the server never needs an in-memory SORT stage, then
        // the range. Any prefix of this index is usable; anything that skips genres is not.
        create(movies, new CompoundIndexDefinition(
                new Document("genres", 1).append("year", -1).append("imdb.rating", -1))
                .named(MOVIES_BROWSE));

        // Phase 4.7 - the keyset sort is {year: -1, _id: -1} with no genre predicate, so it
        // cannot use MOVIES_BROWSE: that index starts with genres, and an index is only usable
        // from its leading field inward. Paging on a different key means another index.
        create(movies, new CompoundIndexDefinition(new Document("year", -1).append("_id", -1))
                .named(MOVIES_YEAR_ID));

        // Phase 3.5 - roughly a third of the collection has a rating above 7, so this index is
        // a fraction of the size of the full one. The cost is that it can only serve queries
        // whose predicate implies `imdb.rating > 7`.
        create(movies, new Index().on("imdb.rating", Sort.Direction.DESC)
                .named(MOVIES_TOP_RATED)
                .partial(PartialIndexFilter.of(Criteria.where("imdb.rating").gt(TOP_RATED_THRESHOLD))));

        // sample_mflix.comments ships with only _id, which is why the Phase 2.7 $lookup could
        // not finish. date descending because that is how comments are always read.
        create(mflix.indexOps("comments"), new CompoundIndexDefinition(
                new Document("movie_id", 1).append("date", -1))
                .named(COMMENTS_BY_MOVIE));

        create(training.indexOps("inspections"), new CompoundIndexDefinition(
                new Document("result", 1).append("date", -1))
                .named(INSPECTIONS_FAILED)
                .partial(PartialIndexFilter.of(Criteria.where("result").is("Fail"))));

        // Phase 3.7 - the modern replacement for a sparse unique index: documents without an
        // email are simply not in the index, so any number of them can coexist.
        create(mflix.indexOps(SUBSCRIBERS), new Index().on("email", Sort.Direction.ASC)
                .named(SUBSCRIBERS_EMAIL)
                .unique()
                .partial(PartialIndexFilter.of(Criteria.where("email").exists(true))));

        // Phase 3.8 - TTL. The background reaper runs about once a minute, so expiry is
        // "eventually, after at least this long", never exact.
        create(mflix.indexOps(SESSIONS), new Index().on("createdAt", Sort.Direction.ASC)
                .named(SESSIONS_TTL)
                .expire(Duration.ofMinutes(10)));

        // Phase 3.8 - wildcard over an open-ended attribute bag, where the field names are
        // data and cannot be enumerated up front.
        create(mflix.indexOps(SESSIONS), new WildcardIndex("attributes").named(SESSIONS_WILDCARD));

        // Security 1.3 - the only thing that can actually decide a registration race, which is
        // why AuthController inserts and catches rather than checking first.
        //
        // Tolerated rather than fatal: this runs against sample_mflix.users, a collection that
        // arrived with a few hundred rows nobody here wrote. If two of them share an address the
        // build of this index fails, and refusing to start the whole application over a data
        // problem in someone's seeded lab helps no one. The log line says what broke.
        createTolerantly(mflix.indexOps("users"), new Index().on("email", Sort.Direction.ASC)
                .named(USERS_EMAIL)
                .unique(), "duplicate emails in sample_mflix.users");

        // Security 5.2 - expireAfterSeconds: 0 means "expire at the time in this field" rather
        // than "this long after it". The reaper still only runs about once a minute, so this is
        // cleanup and not enforcement: TokenService checks expiresAt itself.
        create(mflix.indexOps(RefreshToken.COLLECTION),
                new Index().on("expiresAt", Sort.Direction.ASC)
                        .named(REFRESH_TOKENS_TTL)
                        .expire(Duration.ZERO));

        create(mflix.indexOps(AuthenticationEventListener.COLLECTION),
                new Index().on("createdAt", Sort.Direction.ASC)
                        .named(AUTH_EVENTS_TTL)
                        .expire(Duration.ofDays(30)));

        log.info("indexes ready: movies={}, comments={}",
                names(mflix.indexOps("movies")), names(mflix.indexOps("comments")));
    }

    private void createTolerantly(IndexOperations ops,
                                  org.springframework.data.mongodb.core.index.IndexDefinition index,
                                  String likelyCause) {
        try {
            create(ops, index);
        } catch (RuntimeException ex) {
            log.error("could not create an index - likely cause: {}. {}", likelyCause, ex.getMessage());
        }
    }

    private void create(IndexOperations ops, org.springframework.data.mongodb.core.index.IndexDefinition index) {
        // createIndex is idempotent for an identical definition and throws IndexOptionsConflict
        // if the same name is reused with different options - which is the error you want.
        String name = ops.createIndex(index);
        log.debug("ensured index {}", name);
    }

    private static List<String> names(IndexOperations ops) {
        return ops.getIndexInfo().stream().map(info -> info.getName()).toList();
    }
}
