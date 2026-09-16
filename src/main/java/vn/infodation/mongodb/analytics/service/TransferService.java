package vn.infodation.mongodb.analytics.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;
import vn.infodation.mongodb.analytics.domain.Account;
import vn.infodation.mongodb.analytics.domain.Transfer;
import vn.infodation.mongodb.common.NotFoundException;
import vn.infodation.mongodb.config.TransactionConfig;

/**
 * Phase 6.2 / 6.3 - three writes across two collections that must all land or none.
 * <p>
 * Worth saying out loud (6.8): a single-document update is <em>already</em> atomic, and a
 * transaction costs roughly an order of magnitude more. If both balances lived in one document,
 * none of this would be needed. Reach for a transaction when the invariant genuinely spans
 * documents, not as a reflex carried over from a relational schema.
 */
@Slf4j
@Service
public class TransferService {

    private final MongoTemplate analytics;

    // Explicit constructor: Lombok does not copy @Qualifier onto generated constructor
    // parameters unless lombok.config says so, and a silently wrong template here would mean
    // writing to the wrong database.
    public TransferService(@Qualifier("analyticsTemplate") MongoTemplate analytics) {
        this.analytics = analytics;
    }

    @Transactional(TransactionConfig.TRANSACTION_MANAGER)
    public Transfer transfer(int fromAccountId, int toAccountId, BigDecimal amount, String idempotencyKey) {
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (fromAccountId == toAccountId) {
            throw new IllegalArgumentException("cannot transfer to the same account");
        }

        Account from = require(fromAccountId);
        require(toAccountId);

        // Phase 6.3 - the guard. Under SNAPSHOT read concern this balance is consistent with
        // everything else read in the transaction, and a concurrent transfer that would
        // invalidate it makes the commit fail rather than silently interleave.
        if (from.getBalance() == null || from.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(fromAccountId, from.getBalance(), amount);
        }

        analytics.updateFirst(byAccount(fromAccountId), new Update().inc("balance", amount.negate()), Account.class);
        analytics.updateFirst(byAccount(toAccountId), new Update().inc("balance", amount), Account.class);

        Transfer audit = Transfer.builder()
                .fromAccountId(fromAccountId)
                .toAccountId(toAccountId)
                .amount(amount)
                .at(Instant.now())
                .idempotencyKey(idempotencyKey)
                .build();
        return analytics.insert(audit);
    }

    /**
     * Phase 6.2 - the rollback demonstration: identical to {@link #transfer}, then throws.
     * Nothing may survive.
     */
    @Transactional(TransactionConfig.TRANSACTION_MANAGER)
    public void transferThenFail(int fromAccountId, int toAccountId, BigDecimal amount) {
        // Self-invocation, so the inner @Transactional is bypassed entirely - the proxy is not
        // in the call path. That is harmless here because this method's own boundary is already
        // open and REQUIRED would have joined it anyway, but it is exactly how people end up
        // with a method they believe is transactional and is not.
        transfer(fromAccountId, toAccountId, amount, "rollback-demo");
        throw new IllegalStateException("deliberate failure after both writes");
    }

    /**
     * Phase 6.4 - writing to a collection that does not exist yet.
     * <p>
     * This used to abort the transaction. Since MongoDB 4.4 the server creates the collection
     * implicitly, so on the 8.0 lab it simply works - kept as an executable note, because the
     * "create your collections up front" advice is still everywhere and is now only true for
     * sharded collections and older servers.
     */
    @Transactional(TransactionConfig.TRANSACTION_MANAGER)
    public long writeToNewCollection(String collection) {
        analytics.insert(new org.bson.Document("createdInTransaction", Instant.now()), collection);
        return analytics.count(new Query(), collection);
    }

    public BigDecimal balanceOf(int accountId) {
        return require(accountId).getBalance();
    }

    public BigDecimal totalBalance() {
        return analytics.find(new Query(), Account.class).stream()
                .map(Account::getBalance)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public List<Transfer> transfers() {
        return analytics.findAll(Transfer.class);
    }

    private Account require(int accountId) {
        Account account = analytics.findOne(byAccount(accountId), Account.class);
        if (account == null) {
            throw new NotFoundException("account", accountId);
        }
        return account;
    }

    private static Query byAccount(int accountId) {
        return Query.query(Criteria.where("account_id").is(accountId));
    }

    public static class InsufficientFundsException extends RuntimeException {
        public InsufficientFundsException(int accountId, BigDecimal balance, BigDecimal amount) {
            super("account %d has %s, cannot send %s".formatted(accountId, balance, amount));
        }
    }
}
