package vn.infodation.mongodb.mflix.service;

import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import com.mongodb.ExplainVerbosity;

import lombok.RequiredArgsConstructor;
import vn.infodation.mongodb.common.ExplainSummary;

/**
 * Phase 3.1 / 3.4 / 3.6 - the measuring tape. Everything here exists so the before/after numbers
 * can be read off an endpoint instead of retyped from a mongosh session.
 */
@Service
@RequiredArgsConstructor
public class IndexInspectionService {

    private final MongoTemplate mongoTemplate;

    /** Index names and on-disk sizes, which is how you see what a partial index saves. */
    public List<Map<String, Object>> indexes(String collection) {
        Document stats = mongoTemplate.getDb().runCommand(
                new Document("collStats", collection));
        Document sizes = stats.get("indexSizes", new Document());

        return mongoTemplate.indexOps(collection).getIndexInfo().stream()
                .map(info -> Map.<String, Object>of(
                        "name", info.getName(),
                        "keys", info.getIndexFields().stream()
                                .map(f -> f.getKey() + ":" + f.getDirection())
                                .toList(),
                        "unique", info.isUnique(),
                        "partial", info.getPartialFilterExpression() != null,
                        "sizeBytes", sizes.get(info.getName(), 0)))
                .toList();
    }

    /** Explain an arbitrary filter, so a query can be compared with and without its index. */
    public ExplainSummary explain(String collection, Document filter, Document sort, Document projection) {
        return ExplainSummary.of(rawExplain(collection, filter, sort, projection, null));
    }

    /** Same, pinned to one index, to prove a specific index can (or cannot) serve the query. */
    public ExplainSummary explainWithHint(String collection, Document filter, String indexName) {
        return ExplainSummary.of(rawExplain(collection, filter, null, null, indexName));
    }

    public Document rawExplain(String collection, Document filter, Document sort,
                               Document projection, String indexName) {
        var find = mongoTemplate.getCollection(collection).find(filter);
        if (sort != null) {
            find = find.sort(sort);
        }
        if (projection != null) {
            find = find.projection(projection);
        }
        if (indexName != null) {
            find = find.hintString(indexName);
        }
        return find.explain(ExplainVerbosity.EXECUTION_STATS);
    }
}
