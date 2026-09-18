package vn.infodation.mongodb.analytics.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import vn.infodation.mongodb.analytics.domain.Transfer;
import vn.infodation.mongodb.support.AbstractMongoIntegrationTest;
import vn.infodation.mongodb.support.AnalyticsFixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 6. */
class TransferServiceIT extends AbstractMongoIntegrationTest {

    @Autowired
    TransferService transferService;

    @Autowired
    RetryingTransferService retryingTransferService;

    @Autowired
    @Qualifier("analyticsTemplate")
    MongoTemplate analytics;

    @BeforeEach
    void setUp() {
        AnalyticsFixtures.reset(analytics);
    }

    @Test
    void aCommittedTransferMovesMoneyAndWritesTheAudit() {
        transferService.transfer(AnalyticsFixtures.ALICE, AnalyticsFixtures.BOB,
                new BigDecimal("250.00"), "happy-path");

        assertThat(transferService.balanceOf(AnalyticsFixtures.ALICE)).isEqualByComparingTo("750.00");
        assertThat(transferService.balanceOf(AnalyticsFixtures.BOB)).isEqualByComparingTo("1250.00");
        assertThat(transferService.transfers()).hasSize(1)
                .first().extracting(Transfer::getIdempotencyKey).isEqualTo("happy-path");
    }

    /**
     * Phase 6.2 - the one that proves the transaction is real. Both {@code $inc}s and the
     * audit insert have already run when the exception is thrown; none of them may survive.
     */
    @Test
    void aFailureAfterBothWritesRollsBackEverything() {
        assertThatThrownBy(() -> transferService.transferThenFail(
                AnalyticsFixtures.ALICE, AnalyticsFixtures.BOB, new BigDecimal("100.00")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(transferService.balanceOf(AnalyticsFixtures.ALICE))
                .isEqualByComparingTo(AnalyticsFixtures.OPENING_BALANCE);
        assertThat(transferService.balanceOf(AnalyticsFixtures.BOB))
                .isEqualByComparingTo(AnalyticsFixtures.OPENING_BALANCE);
        assertThat(analytics.count(new Query(), Transfer.class)).isZero();
    }

    /** Phase 6.3 - the guard rejects, and leaves nothing behind. */
    @Test
    void anOverdraftIsRejectedWithoutWritingAnything() {
        assertThatThrownBy(() -> transferService.transfer(
                AnalyticsFixtures.ALICE, AnalyticsFixtures.BOB, new BigDecimal("5000.00"), "overdraft"))
                .isInstanceOf(TransferService.InsufficientFundsException.class);

        assertThat(transferService.balanceOf(AnalyticsFixtures.ALICE))
                .isEqualByComparingTo(AnalyticsFixtures.OPENING_BALANCE);
        assertThat(analytics.count(new Query(), Transfer.class)).isZero();
    }

    /** Phase 6.4 - since MongoDB 4.4 this is allowed; on 8.0 it simply works. */
    @Test
    void writingToACollectionThatDoesNotExistYetSucceedsOnModernServers() {
        String collection = "lab_created_in_txn_" + System.nanoTime();

        assertThat(transferService.writeToNewCollection(collection)).isEqualTo(1);
        assertThat(analytics.count(new Query(), collection)).isEqualTo(1);

        analytics.dropCollection(collection);
    }

    /**
     * Phase 6.7 - the real proof. Twenty threads transferring in both directions at once; the
     * only thing that must hold is that money is neither created nor destroyed.
     * <p>
     * Under {@code SNAPSHOT} read concern a losing transaction aborts with a transient label
     * rather than interleaving, and {@link RetryingTransferService} replays it.
     */
    @Test
    void concurrentTransfersPreserveTheTotalBalance() throws Exception {
        int threads = 20;
        BigDecimal amount = new BigDecimal("10.00");
        AtomicInteger committed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Void>> work = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                boolean aliceToBob = i % 2 == 0;
                String key = "concurrent-" + i;
                work.add(() -> {
                    try {
                        retryingTransferService.transfer(
                                aliceToBob ? AnalyticsFixtures.ALICE : AnalyticsFixtures.BOB,
                                aliceToBob ? AnalyticsFixtures.BOB : AnalyticsFixtures.ALICE,
                                amount, key);
                        committed.incrementAndGet();
                    } catch (RuntimeException ex) {
                        rejected.incrementAndGet();
                    }
                    return null;
                });
            }
            for (Future<Void> future : pool.invokeAll(work)) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(committed.get() + rejected.get()).isEqualTo(threads);
        assertThat(committed.get()).isPositive();

        // The invariant. Individual balances are non-deterministic; their sum is not.
        assertThat(transferService.totalBalance()).isEqualByComparingTo(AnalyticsFixtures.TOTAL);

        // One audit row per committed transfer - no partial commits, no duplicates.
        assertThat(transferService.transfers()).hasSize(committed.get());
    }
}
