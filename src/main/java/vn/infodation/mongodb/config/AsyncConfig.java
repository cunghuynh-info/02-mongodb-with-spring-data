package vn.infodation.mongodb.config;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import lombok.extern.slf4j.Slf4j;

/**
 * Phase 9.1 - the executor every {@code @Async} method in the lab runs on.
 * <p>
 * MongoDB's own asynchronous behaviour (Atlas Search index builds in Phase 8, change streams in
 * Phase 7) happens on the server or inside the driver's reactive client, neither of which this
 * project uses - every {@link org.springframework.data.mongodb.core.MongoTemplate} call here is
 * synchronous. {@code @Async} does not make MongoDB itself faster; it moves a blocking call off
 * whichever thread is waiting on it (a servlet request thread, most often) onto a worker thread
 * from the pool below, so the caller can do something else - or simply stop tying up a request
 * thread - while the wait happens.
 * <p>
 * Every {@code @Async} method in the lab names this executor explicitly
 * ({@code @Async(AsyncConfig.TASK_EXECUTOR)}) rather than relying on the unqualified
 * {@code @Async}, which falls back to {@link org.springframework.core.task.SimpleAsyncTaskExecutor}
 * - a new, never-reused thread per call, with no pool and no queue.
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    public static final String TASK_EXECUTOR = "labTaskExecutor";

    private final Executor labTaskExecutor;

    // Explicit constructor, not @RequiredArgsConstructor: Lombok does not copy @Qualifier onto
    // a generated constructor's parameters unless lombok.config says so (the same reason
    // TransferService and BulkWriteService write their constructors by hand) - picking up the
    // wrong Executor here would be silent, since java.util.concurrent.Executor has no other
    // candidate bean today, but it would stop being silent the moment a second one is added.
    public AsyncConfig(@Qualifier(TASK_EXECUTOR) Executor labTaskExecutor) {
        this.labTaskExecutor = labTaskExecutor;
    }

    /**
     * Core/max sized for a teaching lab, not a production workload: small enough that the
     * executor-starvation exercise (Phase 9.7 - a slow job delaying an unrelated fast one) is
     * easy to trigger deliberately by turning {@code maxPoolSize} down further, rather than
     * something that only shows up under real load.
     */
    @Bean(TASK_EXECUTOR)
    public ThreadPoolTaskExecutor labTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("lab-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return labTaskExecutor;
    }

    /**
     * The only place a {@code void}-returning {@code @Async} method's exception can go -
     * nothing calls it back, so without this override a failure in, say,
     * {@code BulkWriteService.runBenchmarkAsync} is logged at a level easy to miss and nothing
     * else happens. Every fire-and-forget job in the lab also records its own failure in
     * {@code vn.infodation.mongodb.common.async.AsyncJobRegistry} (Phase 9.4); this handler is
     * only the backstop for whatever slips past that.
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return AsyncConfig::logUncaughtAsyncException;
    }

    private static void logUncaughtAsyncException(Throwable ex, Method method, Object... params) {
        log.error("uncaught exception in @Async method {} (args={})", method, params, ex);
    }
}
