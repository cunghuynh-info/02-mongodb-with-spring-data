package vn.infodation.mongodb.search;

import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import vn.infodation.mongodb.common.RawStage;

/**
 * Phase 8 - {@code $search}.
 * <p>
 * Written as raw stages. Spring Data has no typed builder for {@code $search}, and inventing one
 * here would mean maintaining a second dialect of an operator set that changes every release -
 * these stage documents are copy-pasteable into mongosh and into the Atlas console, which is
 * worth more than fluency.
 * <p>
 * {@code $search} must be the first stage in the pipeline. Everything else - filtering the
 * results, adding fields, projecting - happens after it.
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    private static final String COLLECTION = SearchIndexService.MOVIES_COLLECTION;
    private static final String INDEX = SearchIndexService.MOVIES_INDEX;

    private final MongoTemplate mongoTemplate;

    /** Phase 8.1 / 8.2 - text search across two fields, with score and highlights. */
    public List<Document> search(String query, int limit) {
        List<AggregationOperation> stages = List.of(
                RawStage.of("""
                        { "$search": {
                            "index": "%s",
                            "text": { "query": %s, "path": ["title", "plot"] },
                            "highlight": { "path": "plot" }
                        } }""".formatted(INDEX, json(query))),
                RawStage.of("{ \"$limit\": " + limit + " }"),
                RawStage.of("""
                        { "$project": {
                            "_id": 1, "title": 1, "year": 1, "genres": 1,
                            "score": { "$meta": "searchScore" },
                            "highlights": { "$meta": "searchHighlights" }
                        } }"""));
        return run(stages);
    }

    /**
     * Phase 8.3 - type-ahead. The {@code autocomplete} operator queries the edge-gram field
     * built at index time, so a three-letter prefix is a single index seek rather than the
     * unanchored {@code regex} from Phase 2.2 that has to read every title.
     */
    public List<Document> autocomplete(String prefix, int limit) {
        List<AggregationOperation> stages = List.of(
                RawStage.of("""
                        { "$search": {
                            "index": "%s",
                            "autocomplete": { "query": %s, "path": "title", "tokenOrder": "sequential" }
                        } }""".formatted(INDEX, json(prefix))),
                RawStage.of("{ \"$limit\": " + limit + " }"),
                RawStage.of("""
                        { "$project": { "_id": 1, "title": 1, "year": 1,
                            "score": { "$meta": "searchScore" } } }"""));
        return run(stages);
    }

    /**
     * Phase 8.4 - compound.
     * <p>
     * The distinction that matters: {@code filter} narrows without contributing to the score,
     * {@code must} narrows <em>and</em> scores, {@code should} only scores. Putting a genre
     * restriction in {@code must} lets it distort the ranking; in {@code filter} it cannot.
     */
    public List<Document> compound(String query, String genre, Integer minYear, int limit) {
        List<String> filters = new ArrayList<>();
        if (genre != null && !genre.isBlank()) {
            filters.add("{ \"text\": { \"query\": %s, \"path\": \"genres\" } }".formatted(json(genre)));
        }
        if (minYear != null) {
            filters.add("{ \"range\": { \"path\": \"year\", \"gte\": %d } }".formatted(minYear));
        }

        String search = """
                { "$search": {
                    "index": "%s",
                    "compound": {
                      "must": [
                        { "text": { "query": %s, "path": ["title", "plot", "fullplot"] } }
                      ],
                      "should": [
                        { "text": { "query": %s, "path": "title", "score": { "boost": { "value": 5 } } } }
                      ],
                      "filter": [ %s ],
                      "mustNot": [
                        { "text": { "query": "Documentary", "path": "genres" } }
                      ]
                    }
                } }""".formatted(INDEX, json(query), json(query), String.join(",", filters));

        return run(List.of(
                RawStage.of(search),
                RawStage.of("{ \"$limit\": " + limit + " }"),
                RawStage.of("""
                        { "$project": { "_id": 1, "title": 1, "year": 1, "genres": 1,
                            "score": { "$meta": "searchScore" } } }""")));
    }

    /**
     * Phase 8.5 - {@code $searchMeta}: counts and facets without the documents.
     * <p>
     * A separate call from the results, and a cheap one - it never materialises a document.
     */
    public Document facets(String query) {
        Document meta = mongoTemplate.aggregate(
                        Aggregation.newAggregation(RawStage.of("""
                                { "$searchMeta": {
                                    "index": "%s",
                                    "facet": {
                                      "operator": { "text": { "query": %s, "path": ["title", "plot"] } },
                                      "facets": {
                                        "genreFacet": { "type": "string", "path": "genres", "numBuckets": 10 },
                                        "decadeFacet": { "type": "number", "path": "year",
                                                         "boundaries": [1900, 1950, 1970, 1990, 2000, 2010, 2020],
                                                         "default": "other" }
                                      }
                                    }
                                } }""".formatted(INDEX, json(query)))),
                        COLLECTION, Document.class)
                .getUniqueMappedResult();
        return meta == null ? new Document() : meta;
    }

    /**
     * Phase 8.6 - paging with {@code searchAfter}, the Atlas Search counterpart to Phase 4's
     * keyset. {@code $skip} after {@code $search} makes mongot produce and discard everything
     * before the offset, exactly like a deep {@code skip} on a normal query.
     */
    public Document page(String query, String searchAfterToken, int size) {
        String after = searchAfterToken == null ? "" : ", \"searchAfter\": %s".formatted(json(searchAfterToken));
        List<Document> hits = run(List.of(
                // No explicit "sort": sorting on a field requires it to be indexed as sortable,
                // and _id is not in the mappings ("_id is not indexed as sortable"). The default
                // relevance order is stable enough for searchAfter, which is what the
                // searchSequenceToken encodes.
                RawStage.of("""
                        { "$search": {
                            "index": "%s",
                            "text": { "query": %s, "path": ["title", "plot"] }
                            %s
                        } }""".formatted(INDEX, json(query), after)),
                RawStage.of("{ \"$limit\": " + size + " }"),
                RawStage.of("""
                        { "$project": { "_id": 1, "title": 1, "year": 1,
                            "score": { "$meta": "searchScore" },
                            "paginationToken": { "$meta": "searchSequenceToken" } } }""")));

        String next = hits.isEmpty() ? null : hits.get(hits.size() - 1).getString("paginationToken");
        return new Document("items", hits).append("nextToken", next).append("size", size);
    }

    /**
     * Phase 8.7 - fuzzy matching. {@code maxEdits: 1} covers a single typo; going to 2 on short
     * words starts matching things a human would not call a match.
     */
    public List<Document> fuzzy(String query, int maxEdits, int limit) {
        return run(List.of(
                RawStage.of("""
                        { "$search": {
                            "index": "%s",
                            "text": {
                              "query": %s,
                              "path": "title",
                              "fuzzy": { "maxEdits": %d, "prefixLength": 1 }
                            }
                        } }""".formatted(INDEX, json(query), maxEdits)),
                RawStage.of("{ \"$limit\": " + limit + " }"),
                RawStage.of("""
                        { "$project": { "_id": 1, "title": 1, "year": 1,
                            "score": { "$meta": "searchScore" } } }""")));
    }

    private List<Document> run(List<AggregationOperation> stages) {
        return mongoTemplate.aggregate(Aggregation.newAggregation(stages), COLLECTION, Document.class)
                .getMappedResults();
    }

    /**
     * Quotes a user string as a JSON literal. These stages are assembled by string
     * interpolation, so this is the boundary that stops a quote in the query text from
     * rewriting the surrounding document - the same reason Phase 2.2 quotes its regexes.
     */
    static String json(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
