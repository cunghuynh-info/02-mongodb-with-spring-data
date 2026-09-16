package vn.infodation.mongodb.search;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import com.mongodb.client.MongoCollection;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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

    /** Blocks until the index is queryable, or gives up. Returns whether it made it. */
    public boolean awaitQueryable(String name, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Document info = info(name);
            if (info != null && (Boolean.TRUE.equals(info.getBoolean("queryable"))
                    || "READY".equals(info.getString("status")))) {
                return true;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public void drop(String name) {
        if (exists(name)) {
            mongoTemplate.getCollection(MOVIES_COLLECTION).dropSearchIndex(name);
        }
    }
}
