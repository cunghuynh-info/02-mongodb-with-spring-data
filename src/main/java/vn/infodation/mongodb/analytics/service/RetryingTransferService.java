package vn.infodation.mongodb.analytics.service;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;

import com.mongodb.MongoException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import vn.infodation.mongodb.analytics.domain.Transfer;

/**
 * Phase 6.6 - retrying a transaction that the server asked you to retry.
 * <p>
 * MongoDB labels the failures it considers retryable. {@code TransientTransactionError} means
 * the whole transaction has to be replayed from the start; {@code UnknownTransactionCommitResult}
 * means the commit may or may not have happened and should be re-sent. The driver retries the
 * commit on its own, but nothing retries the <em>callback</em>, which is why this sits outside
 * the {@code @Transactional} boundary rather than inside it.
 * <p>
 * The callback therefore has to be safe to run twice. {@link TransferService#transfer} re-reads
 * the balance and re-checks the guard every attempt, so a replay either succeeds cleanly or
 * fails the guard - it never applies the same {@code $inc} twice, because an aborted attempt
 * left nothing behind.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetryingTransferService {

    public static final int MAX_ATTEMPTS = 3;

    private final TransferService transferService;

    public Transfer transfer(int fromAccountId, int toAccountId, BigDecimal amount, String idempotencyKey) {
        RuntimeException last = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return transferService.transfer(fromAccountId, toAccountId, amount, idempotencyKey);
            } catch (RuntimeException ex) {
                if (!isRetryable(ex)) {
                    throw ex;
                }
                last = ex;
                log.warn("retryable transaction failure on attempt {}/{}: {}",
                        attempt, MAX_ATTEMPTS, ex.getMessage());
                backOff(attempt);
            }
        }
        throw last;
    }

    /** Walks the cause chain for the server's own labels rather than matching on messages. */
    static boolean isRetryable(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof MongoException mongo
                    && (mongo.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)
                    || mongo.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL))) {
                return true;
            }
            if (cause instanceof TransientDataAccessException) {
                return true;
            }
        }
        return false;
    }

    private static void backOff(int attempt) {
        try {
            // Jittered, so two threads colliding do not line up again on the retry.
            Thread.sleep(ThreadLocalRandom.current().nextLong(5L * attempt, 20L * attempt));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while retrying transaction", ex);
        }
    }
}
