package vn.infodation.mongodb.common;

import org.bson.Document;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperationContext;

/**
 * A pipeline stage written as raw JSON and passed through unmapped.
 * <p>
 * Used where the typed builders do not reach: {@code $lookup} with a sub-pipeline here, and
 * {@code $search} in Phase 8. Passing the document through untouched is deliberate - the field
 * names in these stages are already the ones stored in MongoDB, not Java property names.
 */
public record RawStage(Document stage) implements AggregationOperation {

    public static RawStage of(String json) {
        return new RawStage(Document.parse(json));
    }

    @Override
    public Document toDocument(AggregationOperationContext context) {
        return stage;
    }
}
