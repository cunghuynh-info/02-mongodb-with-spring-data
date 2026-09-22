package vn.infodation.mongodb.common.async;

import java.time.Instant;

/**
 * Phase 9.4 - the record a fire-and-forget {@code @Async} job leaves behind.
 * <p>
 * A {@code void}-returning {@code @Async} method has no return channel of its own once it has
 * been dispatched to the executor: whoever started it already got their response back (a job
 * id, typically). This is where the eventual result, or the eventual failure, actually lands.
 */
public record AsyncJobStatus(String jobId, State state, Instant startedAt, Instant finishedAt,
                             Object result, String errorMessage) {

    public enum State { RUNNING, DONE, FAILED }

    public static AsyncJobStatus running(String jobId) {
        return new AsyncJobStatus(jobId, State.RUNNING, Instant.now(), null, null, null);
    }

    public AsyncJobStatus done(Object result) {
        return new AsyncJobStatus(jobId, State.DONE, startedAt(), Instant.now(), result, null);
    }

    public AsyncJobStatus failed(String errorMessage) {
        return new AsyncJobStatus(jobId, State.FAILED, startedAt(), Instant.now(), null, errorMessage);
    }
}
