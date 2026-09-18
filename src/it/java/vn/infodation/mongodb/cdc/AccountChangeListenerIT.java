package vn.infodation.mongodb.cdc;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import org.awaitility.Awaitility;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import vn.infodation.mongodb.analytics.domain.Account;
import vn.infodation.mongodb.support.AbstractMongoIntegrationTest;
import vn.infodation.mongodb.support.AnalyticsFixtures;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 7. */
class AccountChangeListenerIT extends AbstractMongoIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    AccountChangeListener listener;

    @Autowired
    ResumeTokenStore tokenStore;

    @Autowired
    @Qualifier("analyticsTemplate")
    MongoTemplate analytics;

    @BeforeEach
    void setUp() {
        listener.stop();
        listener.reset();
        AnalyticsFixtures.reset(analytics);
    }

    @AfterEach
    void tearDown() {
        listener.stop();
    }

    /** Phase 7.1 / 7.3 - the stream sees the writes, and only the operations it asked for. */
    @Test
    void observesInsertsAndUpdates() {
        listener.startAndAwait(false, TIMEOUT);

        analytics.updateFirst(byAccount(AnalyticsFixtures.ALICE),
                new Update().inc("balance", new BigDecimal("25.00")), Account.class);

        Awaitility.await().atMost(TIMEOUT).until(() -> listener.eventCount() >= 1);

        List<Document> events = listener.observed();
        assertThat(events).isNotEmpty();
        assertThat(events).extracting(event -> event.getString("operationType"))
                .allMatch(type -> List.of("insert", "update", "replace", "delete").contains(type));
        assertThat(events.get(events.size() - 1).get("after", Document.class))
                .containsEntry("account_id", AnalyticsFixtures.ALICE);
    }

    /** Phase 7.7 - the denormalised view keeps up without the writer knowing it exists. */
    @Test
    void maintainsTheAccountSummaryView() {
        listener.startAndAwait(false, TIMEOUT);

        analytics.updateFirst(byAccount(AnalyticsFixtures.BOB),
                new Update().inc("balance", new BigDecimal("40.00")), Account.class);

        Awaitility.await().atMost(TIMEOUT).untilAsserted(() -> {
            Document summary = analytics.findOne(
                    Query.query(Criteria.where("_id").is(AnalyticsFixtures.BOB)),
                    Document.class, AccountChangeListener.SUMMARY_COLLECTION);
            assertThat(summary).isNotNull();
            assertThat(summary.getString("lastOperation")).isEqualTo("update");
            assertThat(summary.get("changeCount")).isNotNull();
        });
    }

    /** Phase 7.5 - a token is stored per event, ready for a restart. */
    @Test
    void storesAResumeTokenAfterHandlingEachEvent() {
        listener.startAndAwait(false, TIMEOUT);

        analytics.updateFirst(byAccount(AnalyticsFixtures.ALICE),
                new Update().inc("balance", new BigDecimal("1.00")), Account.class);

        Awaitility.await().atMost(TIMEOUT)
                .until(() -> tokenStore.load(AccountChangeListener.LISTENER_ID).isPresent());
    }

    /**
     * Phase 7.5 - the one that matters. Stop the listener, write while it is down, restart from
     * the stored token, and the missed change still arrives.
     */
    @Test
    void resumingFromAStoredTokenReplaysChangesMadeWhileDown() {
        listener.startAndAwait(false, TIMEOUT);

        analytics.updateFirst(byAccount(AnalyticsFixtures.ALICE),
                new Update().inc("balance", new BigDecimal("1.00")), Account.class);
        Awaitility.await().atMost(TIMEOUT)
                .until(() -> tokenStore.load(AccountChangeListener.LISTENER_ID).isPresent());

        listener.stop();
        listener.reset();
        assertThat(listener.eventCount()).isZero();

        // Written with nobody listening.
        analytics.updateFirst(byAccount(AnalyticsFixtures.BOB),
                new Update().inc("balance", new BigDecimal("7.00")), Account.class);

        listener.startAndAwait(true, TIMEOUT);

        Awaitility.await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(listener.observed())
                        .anySatisfy(event -> assertThat(event.get("after", Document.class))
                                .containsEntry("account_id", AnalyticsFixtures.BOB)));
    }

    /** Phase 7.4 - before-images arrive once the collection is configured for them. */
    @Test
    void beforeImagesAreAvailableOncePreImagesAreEnabled() {
        listener.enablePreAndPostImages("accounts");
        listener.startAndAwait(false, TIMEOUT);

        analytics.updateFirst(byAccount(AnalyticsFixtures.ALICE),
                new Update().inc("balance", new BigDecimal("13.00")), Account.class);

        Awaitility.await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(listener.observed())
                        .anySatisfy(event -> {
                            Document before = event.get("before", Document.class);
                            Document after = event.get("after", Document.class);
                            assertThat(before).isNotNull();
                            assertThat(after).isNotNull();
                            // A real audit diff: what it was, and what it became.
                            assertThat(before.get("balance")).isNotEqualTo(after.get("balance"));
                        }));
    }

    private static Query byAccount(int accountId) {
        return Query.query(Criteria.where("account_id").is(accountId));
    }
}
