package vn.infodation.mongodb.search;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import vn.infodation.mongodb.support.AbstractMongoIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 9.2 / 9.3 - {@link SearchIndexService#awaitQueryable} called through the proxy versus
 * called on {@code this}. Both run against an index name that never exists, so they run out the
 * clock on {@code timeout} instead of racing a real index build - the point here is the call
 * path, not Atlas Search itself (see {@link SearchServiceIT} for that).
 */
class SearchIndexServiceAsyncIT extends AbstractMongoIntegrationTest {

    private static final String MISSING_INDEX = "does-not-exist";
    private static final Duration TIMEOUT = Duration.ofMillis(300);

    @Autowired
    SearchIndexService indexService;

    /** The proxied call hands back a future almost immediately; the poll loop runs elsewhere. */
    @Test
    void proxiedCallReturnsBeforeThePollLoopFinishes() {
        long start = System.nanoTime();
        CompletableFuture<Boolean> future = indexService.awaitQueryable(MISSING_INDEX, TIMEOUT);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs)
                .as("the @Async proxy should hand back a future long before the timeout elapses")
                .isLessThan(TIMEOUT.toMillis());
        assertThat(future.join()).isFalse();
    }

    /** No proxy in the call path, so the whole poll loop runs on the calling thread instead. */
    @Test
    void selfInvocationBlocksTheCallingThreadForTheWholeTimeout() {
        long start = System.nanoTime();
        CompletableFuture<Boolean> future = indexService.awaitQueryableViaSelfInvocation(MISSING_INDEX, TIMEOUT);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(future).as("still completed - just synchronously, before this method returned").isDone();
        assertThat(elapsedMs)
                .as("with no thread hand-off, this call cannot return before the poll loop is done")
                .isGreaterThanOrEqualTo(TIMEOUT.toMillis());
        assertThat(future.join()).isFalse();
    }
}
