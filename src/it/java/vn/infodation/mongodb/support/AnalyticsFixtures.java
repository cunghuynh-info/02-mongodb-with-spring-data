package vn.infodation.mongodb.support;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import vn.infodation.mongodb.analytics.domain.Account;
import vn.infodation.mongodb.analytics.domain.Transfer;
import vn.infodation.mongodb.cdc.AccountChangeListener;
import vn.infodation.mongodb.cdc.ResumeTokenStore;

/** Two accounts with known balances, for Phases 5, 6 and 7. */
public final class AnalyticsFixtures {

    public static final int ALICE = 1;
    public static final int BOB = 2;
    public static final int LIMIT = 9000;
    public static final BigDecimal OPENING_BALANCE = new BigDecimal("1000.00");
    /** Both accounts together; the invariant the concurrency test checks. */
    public static final BigDecimal TOTAL = new BigDecimal("2000.00");

    private AnalyticsFixtures() {
    }

    public static void reset(MongoTemplate analytics) {
        analytics.remove(new Query(), Account.class);
        analytics.remove(new Query(), Transfer.class);
        analytics.remove(new Query(), AccountChangeListener.SUMMARY_COLLECTION);
        analytics.remove(new Query(), ResumeTokenStore.COLLECTION);

        analytics.insert(List.of(
                account(ALICE, OPENING_BALANCE),
                account(BOB, OPENING_BALANCE)), "accounts");
    }

    private static Account account(int accountId, BigDecimal balance) {
        return Account.builder()
                .accountId(accountId)
                .creditLimit(LIMIT)
                .products(List.of("Derivatives", "CurrencyService"))
                .balance(balance)
                .build();
    }
}
