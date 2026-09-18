package vn.infodation.mongodb.analytics.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.BulkOperationException;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.BulkOperations.BulkMode;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.mongodb.bulk.BulkWriteResult;

import lombok.extern.slf4j.Slf4j;
import vn.infodation.mongodb.analytics.domain.Account;
import vn.infodation.mongodb.config.IndexConfig;

/**
 * Phase 5 - {@link BulkOperations}: one round trip instead of n.
 * <p>
 * The API exists for exactly one reason, and it is not elegance. A per-document
 * {@code save()} pays a network round trip and a server-side write each time; a bulk write
 * ships the whole batch and gets one acknowledgement back.
 */
@Slf4j
@Service
public class BulkWriteService {

    /** Batches above this get split by the driver anyway; 1000 is a reasonable working size. */
    public static final int DEFAULT_BATCH_SIZE = 1000;

    private final MongoTemplate analytics;
    private final MongoTemplate mflix;

    public BulkWriteService(@Qualifier("analyticsTemplate") MongoTemplate analytics,
                            MongoTemplate mflix) {
        this.analytics = analytics;
        this.mflix = mflix;
    }

    /** Phase 5.1 - the mixed batch, and what {@link BulkWriteResult} actually tells you. */
    public Map<String, Object> raiseLimits(List<Integer> accountIds, int delta) {
        BulkOperations bulk = analytics.bulkOps(BulkMode.UNORDERED, Account.class);
        for (Integer accountId : accountIds) {
            bulk.updateOne(
                    Query.query(Criteria.where("account_id").is(accountId)),
                    new Update().inc("limit", delta));
        }
        return summarise(bulk.execute());
    }

    /**
     * Phase 5.5 - upsert with {@code $setOnInsert}. Replaying the same batch is a no-op for
     * {@code createdAt}, so a retried job does not rewrite history.
     */
    public Map<String, Object> upsertAccounts(List<Integer> accountIds, BigDecimal openingBalance) {
        BulkOperations bulk = analytics.bulkOps(BulkMode.UNORDERED, Account.class);
        for (Integer accountId : accountIds) {
            bulk.upsert(
                    Query.query(Criteria.where("account_id").is(accountId)),
                    new Update()
                            .setOnInsert("balance", openingBalance)
                            .setOnInsert("createdAt", Instant.now())
                            .set("updatedAt", Instant.now()));
        }
        return summarise(bulk.execute());
    }

    /**
     * Phase 5.2 - ordered stops at the first failure, unordered keeps going.
     * <p>
     * Driven through {@code lab_subscribers}, which has a unique partial index on
     * {@code email} (Phase 3.7). The batch inserts three documents where the second duplicates
     * the first. Returns how many actually landed, per mode.
     */
    public Map<String, Object> duplicateKeyBehaviour(String email) {
        mflix.remove(new Query(), IndexConfig.SUBSCRIBERS);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ordered", attemptDuplicateBatch(BulkMode.ORDERED, email));
        mflix.remove(new Query(), IndexConfig.SUBSCRIBERS);
        result.put("unordered", attemptDuplicateBatch(BulkMode.UNORDERED, email));
        return result;
    }

    private Map<String, Object> attemptDuplicateBatch(BulkMode mode, String email) {
        BulkOperations bulk = mflix.bulkOps(mode, IndexConfig.SUBSCRIBERS);
        bulk.insert(new Document("email", email).append("seq", 1));
        bulk.insert(new Document("email", email).append("seq", 2));   // duplicate - fails
        bulk.insert(new Document("email", "other-" + email).append("seq", 3));

        String outcome;
        try {
            bulk.execute();
            outcome = "no error";
        } catch (BulkOperationException ex) {
            // Not a DataIntegrityViolationException - BulkOperationException sits directly
            // under DataAccessException, and getErrors() carries one entry per failed write
            // with its original index in the batch.
            outcome = "%s (%d write error(s))".formatted(ex.getClass().getSimpleName(), ex.getErrors().size());
        }

        long landed = mflix.count(new Query(), IndexConfig.SUBSCRIBERS);
        return Map.of(
                "mode", mode.name(),
                "outcome", outcome,
                "documentsWritten", landed,
                "thirdDocumentSurvived", landed == 2);
    }

    /**
     * Phase 5.3 - the measurement that justifies the API. Writes {@code total} synthetic
     * accounts one at a time, then the same batch in chunks, and reports both.
     */
    public Map<String, Object> benchmark(int total, int batchSize) {
        String collection = "lab_bulk_benchmark";

        mflix.remove(new Query(), collection);
        long oneByOneStart = System.nanoTime();
        for (int i = 0; i < total; i++) {
            mflix.insert(new Document("seq", i).append("payload", "row-" + i), collection);
        }
        long oneByOneMillis = (System.nanoTime() - oneByOneStart) / 1_000_000;

        mflix.remove(new Query(), collection);
        long bulkStart = System.nanoTime();
        List<Document> batch = new ArrayList<>(batchSize);
        for (int i = 0; i < total; i++) {
            batch.add(new Document("seq", i).append("payload", "row-" + i));
            if (batch.size() == batchSize) {
                mflix.bulkOps(BulkMode.UNORDERED, collection).insert(batch).execute();
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            mflix.bulkOps(BulkMode.UNORDERED, collection).insert(batch).execute();
        }
        long bulkMillis = (System.nanoTime() - bulkStart) / 1_000_000;

        long written = mflix.count(new Query(), collection);
        mflix.remove(new Query(), collection);

        return Map.of(
                "documents", total,
                "batchSize", batchSize,
                "oneByOneMillis", oneByOneMillis,
                "bulkMillis", bulkMillis,
                "speedup", bulkMillis == 0 ? -1 : Math.round(oneByOneMillis * 10.0 / bulkMillis) / 10.0,
                "documentsWritten", written);
    }

    /**
     * Phase 5.6 - stream in, bulk out. {@code mongoTemplate.stream} holds a server cursor open
     * instead of materialising the collection, so memory stays flat no matter how many comments
     * there are; the writes accumulate into batches on the way past.
     */
    public Map<String, Object> denormaliseCommentAuthors(int batchSize) {
        String target = "lab_comment_authors";
        mflix.remove(new Query(), target);

        long read = 0;
        long written = 0;
        List<org.bson.Document> batch = new ArrayList<>(batchSize);

        try (Stream<Document> comments = mflix.stream(
                new Query().limit(0), Document.class, "comments")) {
            for (Document comment : (Iterable<Document>) comments::iterator) {
                read++;
                batch.add(new Document("email", comment.getString("email"))
                        .append("name", comment.getString("name"))
                        .append("movie_id", comment.get("movie_id")));
                if (batch.size() == batchSize) {
                    written += flush(target, batch);
                }
            }
        }
        written += flush(target, batch);

        long total = mflix.count(new Query(), target);
        mflix.remove(new Query(), target);
        return Map.of("commentsRead", read, "documentsWritten", written, "collectionSize", total);
    }

    private long flush(String collection, List<Document> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        BulkWriteResult result = mflix.bulkOps(BulkMode.UNORDERED, collection).insert(batch).execute();
        batch.clear();
        return result.getInsertedCount();
    }

    private static Map<String, Object> summarise(BulkWriteResult result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("acknowledged", result.wasAcknowledged());
        summary.put("inserted", result.getInsertedCount());
        summary.put("matched", result.getMatchedCount());
        summary.put("modified", result.getModifiedCount());
        summary.put("upserted", result.getUpserts().size());
        summary.put("deleted", result.getDeletedCount());
        return summary;
    }
}
