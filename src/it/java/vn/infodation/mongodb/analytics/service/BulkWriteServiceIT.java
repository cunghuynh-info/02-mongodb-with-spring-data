package vn.infodation.mongodb.analytics.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import vn.infodation.mongodb.analytics.domain.Account;
import vn.infodation.mongodb.support.AbstractMongoIntegrationTest;
import vn.infodation.mongodb.support.AnalyticsFixtures;
import vn.infodation.mongodb.support.SampleFixtures;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 5. */
class BulkWriteServiceIT extends AbstractMongoIntegrationTest {

    @Autowired
    BulkWriteService bulkWriteService;

    @Autowired
    @Qualifier("analyticsTemplate")
    MongoTemplate analytics;

    @Autowired
    MongoTemplate mflix;

    @BeforeEach
    void setUp() {
        AnalyticsFixtures.reset(analytics);
        // The stream-to-bulk case reads sample_mflix.comments.
        SampleFixtures.reset(mflix);
    }

    /** Phase 5.1 - the result counts are exact, not approximate. */
    @Test
    void bulkUpdateReportsMatchedAndModifiedSeparately() {
        Map<String, Object> result = bulkWriteService.raiseLimits(List.of(1, 2, 999), 500);

        // Three operations, two accounts exist: matched 2, modified 2, nothing inserted.
        assertThat(result).containsEntry("matched", 2).containsEntry("modified", 2);
        assertThat(result).containsEntry("inserted", 0).containsEntry("upserted", 0);

        assertThat(account(1).getCreditLimit()).isEqualTo(AnalyticsFixtures.LIMIT + 500);
    }

    /** Phase 5.5 - a replayed upsert leaves createdAt alone. */
    @Test
    void upsertIsIdempotentForSetOnInsertFields() {
        Map<String, Object> first = bulkWriteService.upsertAccounts(List.of(50, 51), new BigDecimal("10.00"));
        assertThat(first).containsEntry("upserted", 2);

        Object createdAt = analytics.findOne(byAccount(50), org.bson.Document.class, "accounts")
                .get("createdAt");

        Map<String, Object> second = bulkWriteService.upsertAccounts(List.of(50, 51), new BigDecimal("999.00"));

        assertThat(second).containsEntry("upserted", 0).containsEntry("matched", 2);
        assertThat(analytics.findOne(byAccount(50), org.bson.Document.class, "accounts").get("createdAt"))
                .isEqualTo(createdAt);
        // The opening balance was $setOnInsert, so the replay did not overwrite it either.
        assertThat(account(50).getBalance()).isEqualByComparingTo("10.00");
    }

    /**
     * Phase 5.2 - the behavioural difference, asserted rather than described. Ordered aborts at
     * the duplicate and never attempts the third document; unordered writes it anyway.
     */
    @Test
    void unorderedContinuesPastAFailureAndOrderedDoesNot() {
        Map<String, Object> result = bulkWriteService.duplicateKeyBehaviour("dup@example.com");

        @SuppressWarnings("unchecked")
        Map<String, Object> ordered = (Map<String, Object>) result.get("ordered");
        @SuppressWarnings("unchecked")
        Map<String, Object> unordered = (Map<String, Object>) result.get("unordered");

        assertThat(ordered).containsEntry("documentsWritten", 1L)
                .containsEntry("thirdDocumentSurvived", false);
        assertThat(unordered).containsEntry("documentsWritten", 2L)
                .containsEntry("thirdDocumentSurvived", true);

        // Both still report the failure - unordered does not swallow it.
        assertThat(ordered.get("outcome")).isNotEqualTo("no error");
        assertThat(unordered.get("outcome")).isNotEqualTo("no error");
    }

    /** Phase 5.3 - modest sizes so the suite stays quick; the direction is the assertion. */
    @Test
    void bulkIsFasterThanOneByOne() {
        Map<String, Object> result = bulkWriteService.benchmark(2000, 500);

        assertThat(result).containsEntry("documentsWritten", 2000L);
        long oneByOne = ((Number) result.get("oneByOneMillis")).longValue();
        long bulk = ((Number) result.get("bulkMillis")).longValue();
        assertThat(bulk).isLessThan(oneByOne);
    }

    /** Phase 5.6 - streaming read, batched write, nothing held in memory. */
    @Test
    void streamToBulkWritesEveryDocumentItReads() {
        Map<String, Object> result = bulkWriteService.denormaliseCommentAuthors(2);

        assertThat(result.get("commentsRead")).isEqualTo(result.get("documentsWritten"));
        assertThat(((Number) result.get("commentsRead")).longValue()).isPositive();
    }

    private Account account(int accountId) {
        return analytics.findOne(byAccount(accountId), Account.class);
    }

    private static Query byAccount(int accountId) {
        return Query.query(Criteria.where("account_id").is(accountId));
    }
}
