package vn.infodation.mongodb.common.async;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import vn.infodation.mongodb.common.NotFoundException;

/**
 * Phase 9.4 - an in-memory board for jobs kicked off by a fire-and-forget {@code @Async} method.
 * <p>
 * In-memory and per-instance is a deliberate simplification, to keep this lab exercise about
 * {@code @Async} rather than about job-persistence design: a service that has to survive a
 * restart or run behind more than one instance would write {@link AsyncJobStatus} into its own
 * MongoDB collection instead - the same upsert-a-status-document shape
 * {@link vn.infodation.mongodb.cdc.AccountChangeListener} already uses for its materialised
 * view (Phase 7.7).
 */
@Component
public class AsyncJobRegistry {

    private final Map<String, AsyncJobStatus> jobs = new ConcurrentHashMap<>();

    /** Registers a new job and returns its id; the job itself is expected to run separately. */
    public String start() {
        String jobId = UUID.randomUUID().toString();
        jobs.put(jobId, AsyncJobStatus.running(jobId));
        return jobId;
    }

    public void complete(String jobId, Object result) {
        jobs.computeIfPresent(jobId, (id, status) -> status.done(result));
    }

    public void fail(String jobId, String errorMessage) {
        jobs.computeIfPresent(jobId, (id, status) -> status.failed(errorMessage));
    }

    public Optional<AsyncJobStatus> find(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    public AsyncJobStatus require(String jobId) {
        return find(jobId).orElseThrow(() -> new NotFoundException("job", jobId));
    }
}
