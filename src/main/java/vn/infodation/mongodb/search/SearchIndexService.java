package vn.infodation.mongodb.search;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.mongodb.client.MongoCollection;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import vn.infodation.mongodb.config.AsyncConfig;

/**
 * Phase 8 - the {@code movies_search} index, in Java.
 * <p>
 * The seed container already creates it for the compose lab (see
 * {@code docker/seed/search-indexes.js}); this exists so tests can build the same index on a
 * throwaway deployment, and so the definition lives somewhere a reviewer will look.
 * <p>
 * Search indexes are asynchronous. {@code createSearchIndex} returns as soon as the request is
 * accepted, and querying before the build finishes returns nothing rather than an error - which
 * is the single most confusing thing about Atlas Search and the reason for
 * {@link #awaitQueryable}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchIndexService {

    public static final String MOVIES_INDEX = "movies_search";
    public static final String MOVIES_COLLECTION = "movies";

    private final MongoTemplate mongoTemplate;

    /**
     * Mirrors {@code docker/seed/search-indexes.js}. {@code dynamic: false} and an explicit
     * field list: a dynamic index covers everything, including the 4 KB {@code fullplot} of
     * every document, and is far larger than anything queried here needs.
     * <p>
     * {@code genres} and {@code year} are indexed twice, once for matching and once as a facet
     * type - {@code $searchMeta} facets only work on {@code stringFacet}/{@code numberFacet}.
     */
    public static Document moviesIndexDefinition() {
        return new Document("mappings", new Document("dynamic", false)
                .append("fields", new Document()
                        .append("title", List.of(
                                new Document("type", "string").append("analyzer", "lucene.english"),
                                new Document("type", "autocomplete")
                                        .append("tokenization", "edgeGram")
                                        .append("minGrams", 2)
                                        .append("maxGrams", 15)))
                        .append("plot", new Document("type", "string").append("analyzer", "lucene.english"))
                        .append("fullplot", new Document("type", "string").append("analyzer", "lucene.english"))
                        .append("genres", List.of(
                                new Document("type", "string").append("analyzer", "lucene.keyword"),
                                new Document("type", "stringFacet")))
                        .append("cast", new Document("type", "string"))
                        .append("directors", new Document("type", "string"))
                        .append("year", List.of(
                                new Document("type", "number"),
                                new Document("type", "numberFacet")))
                        .append("imdb.rating", new Document("type", "number"))));
    }

    /** Creates the index if missing, updates it if the definition changed. */
    public void ensureMoviesIndex() {
        MongoCollection<Document> movies = mongoTemplate.getCollection(MOVIES_COLLECTION);
        Document definition = moviesIndexDefinition();

        if (exists(MOVIES_INDEX)) {
            movies.updateSearchIndex(MOVIES_INDEX, definition);
            log.info("updated search index {}", MOVIES_INDEX);
        } else {
            movies.createSearchIndex(MOVIES_INDEX, definition);
            log.info("created search index {}", MOVIES_INDEX);
        }
    }

    public boolean exists(String name) {
        return info(name) != null;
    }

    public Document info(String name) {
        for (Document index : mongoTemplate.getCollection(MOVIES_COLLECTION).listSearchIndexes()) {
            if (name.equals(index.getString("name"))) {
                return index;
            }
        }
        return null;
    }

    /**
     * Phase 9.2 - waits until the index is queryable, or gives up, without blocking the caller.
     * <p>
     * The poll loop itself has not changed since Phase 8 - it still sleeps a second at a time
     * against a real deadline. What changed is who does the waiting: {@code @Async} moves this
     * whole method onto {@link AsyncConfig#TASK_EXECUTOR}, so a caller gets a
     * {@link CompletableFuture} back immediately instead of a boolean after however long the
     * build takes. {@link SearchController#rebuildIndex} is the intended caller - a different
     * bean, so the {@code @Async} proxy is actually in the call path (see
     * {@link #awaitQueryableViaSelfInvocation} for what happens when it is not).
     */
    @Async(AsyncConfig.TASK_EXECUTOR)
    public CompletableFuture<Boolean> awaitQueryable(String name, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Document info = info(name);
            if (info != null && (Boolean.TRUE.equals(info.getBoolean("queryable"))
                    || "READY".equals(info.getString("status")))) {
                return CompletableFuture.completedFuture(true);
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return CompletableFuture.completedFuture(false);
            }
        }
        return CompletableFuture.completedFuture(false);
    }

    /**
     * Phase 9.3 - the self-invocation pitfall, kept on purpose so it can be run and observed
     * rather than taken on faith.
     * <p>
     * {@code @Async} only takes effect through the Spring AOP proxy wrapped around this bean.
     * {@link #ensureMoviesIndex()} calling {@code awaitQueryable(...)} directly on {@code this}
     * would be exactly this mistake: a call from one method to another on the <em>same</em>
     * instance never goes through the proxy, so the real method runs on the calling thread with
     * no error and no warning - it still returns a {@code CompletableFuture<Boolean>}, only it
     * is already completed by the time the caller gets it back, because the entire poll loop ran
     * first. {@link SearchController#rebuildIndex} shows the fix: call {@link #awaitQueryable}
     * from a different bean, and the proxy is actually in the call path.
     */
    public CompletableFuture<Boolean> awaitQueryableViaSelfInvocation(String name, Duration timeout) {
        return awaitQueryable(name, timeout); // self-invocation - the @Async above is a no-op here
    }

    public void drop(String name) {
        if (exists(name)) {
            mongoTemplate.getCollection(MOVIES_COLLECTION).dropSearchIndex(name);
        }
    }
}
