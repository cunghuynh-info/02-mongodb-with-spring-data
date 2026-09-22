package vn.infodation.mongodb.cdc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.bson.BsonDocument;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.messaging.ChangeStreamRequest;
import org.springframework.data.mongodb.core.messaging.MessageListener;
import org.springframework.data.mongodb.core.messaging.MessageListenerContainer;
import org.springframework.data.mongodb.core.messaging.Subscription;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.mongodb.client.model.changestream.FullDocument;
import com.mongodb.client.model.changestream.FullDocumentBeforeChange;
import com.mongodb.client.model.changestream.OperationType;

import lombok.extern.slf4j.Slf4j;
import vn.infodation.mongodb.config.AsyncConfig;

/**
 * Phase 7.1 / 7.3 / 7.4 / 7.5 / 7.7 - one change stream, doing the things a real one has to do.
 * <p>
 * Not started automatically: {@link #start()} is called by the controller or a test, so the
 * listener's lifecycle is observable rather than something that happens during context refresh.
 */
@Slf4j
@Component
public class AccountChangeListener {

    public static final String LISTENER_ID = "accounts";
    /** Phase 7.7 - the materialised view this stream maintains. */
    public static final String SUMMARY_COLLECTION = "account_summary";

    private final MongoTemplate analytics;
    private final ResumeTokenStore tokenStore;
    private final MessageListenerContainer container;

    private final List<Document> observed = new CopyOnWriteArrayList<>();
    private final AtomicLong eventCount = new AtomicLong();
    private volatile Subscription subscription;

    public AccountChangeListener(@Qualifier("analyticsTemplate") MongoTemplate analytics,
                                 ResumeTokenStore tokenStore) {
        this.analytics = analytics;
        this.tokenStore = tokenStore;
        this.container = new org.springframework.data.mongodb.core.messaging
                .DefaultMessageListenerContainer(analytics);
    }

    /**
     * @param resume whether to pick up from the stored token; false starts from "now" and
     *               loses anything written while the listener was down.
     */
    public synchronized Subscription start(boolean resume) {
        if (subscription != null) {
            return subscription;
        }

        ChangeStreamRequest.ChangeStreamRequestBuilder<Document> builder =
                ChangeStreamRequest.<Document>builder(listener())
                        .collection("accounts")
                        // Phase 7.3 - filter on the server. Shipping every change to the JVM
                        // and discarding most of it in Java is the mistake this prevents.
                        .filter(Aggregation.newAggregation(Aggregation.match(
                                Criteria.where("operationType").in("insert", "update", "replace", "delete"))))
                        // Phase 7.4 - UPDATE_LOOKUP gives the document as it is now. The
                        // before-image needs changeStreamPreAndPostImages enabled on the
                        // collection, so it is asked for WHEN_AVAILABLE, not REQUIRED, and the
                        // stream still works when it is off.
                        .fullDocumentLookup(FullDocument.UPDATE_LOOKUP)
                        .fullDocumentBeforeChangeLookup(FullDocumentBeforeChange.WHEN_AVAILABLE);

        if (resume) {
            // Phase 7.5/7.6 - resumeAfter, not startAfter: they differ only around an
            // "invalidate" event (a dropped collection), where startAfter opens a new stream
            // and resumeAfter refuses. Refusing is the safer default - it surfaces the drop.
            tokenStore.load(LISTENER_ID).ifPresent(builder::resumeAfter);
        }

        ChangeStreamRequest<Document> request = builder.build();

        if (!container.isRunning()) {
            container.start();
        }
        subscription = container.register(request, Document.class);
        log.info("account change stream started (resume={})", resume);
        return subscription;
    }

    /**
     * Registering a subscription returns before the cursor is actually open on the server.
     * Anything written in that gap is never seen, which makes for a wonderfully intermittent
     * test - so wait for the subscription to become active.
     */
    public Subscription startAndAwait(boolean resume, Duration timeout) {
        Subscription started = start(resume);
        try {
            started.await(timeout);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the change stream", ex);
        }
        return started;
    }

    /**
     * Phase 9.5 - {@link #startAndAwait} without blocking the caller.
     * <p>
     * {@link #startAndAwait} exists because registering a subscription returns before the
     * server cursor is actually open, so something has to wait on {@link Subscription#await}
     * before the listener can be treated as running. A caller that only needs that guarantee -
     * not the result on this call - gets a {@link CompletableFuture} back immediately instead of
     * blocking its own thread for however long the subscription takes to open. This calls the
     * plain {@link #startAndAwait} on {@code this}, which is fine here: unlike
     * {@code SearchIndexService.awaitQueryableViaSelfInvocation}, {@code startAndAwait} itself
     * carries no {@code @Async} of its own for a self-invocation to bypass - the whole point is
     * to run its blocking body on the executor thread this method was dispatched to.
     */
    @Async(AsyncConfig.TASK_EXECUTOR)
    public CompletableFuture<Subscription> startAndAwaitAsync(boolean resume, Duration timeout) {
        return CompletableFuture.completedFuture(startAndAwait(resume, timeout));
    }

    public synchronized void stop() {
        if (subscription != null) {
            container.remove(subscription);
            subscription = null;
        }
        if (container.isRunning()) {
            container.stop();
        }
        log.info("account change stream stopped after {} events", eventCount.get());
    }

    private MessageListener<ChangeStreamDocument<Document>, Document> listener() {
        return message -> {
            ChangeStreamDocument<Document> raw = message.getRaw();
            if (raw == null) {
                return;
            }
            try {
                handle(raw, message.getBody());
            } catch (RuntimeException ex) {
                // A listener that throws kills the subscription. Log and carry on; a real one
                // would route the event to a dead-letter collection.
                log.error("failed to handle change event", ex);
            }
        };
    }

    private void handle(ChangeStreamDocument<Document> raw, Document after) {
        OperationType type = raw.getOperationType();
        eventCount.incrementAndGet();

        // Typed as TDocument by the driver, so this is already a Document - no conversion.
        Document before = raw.getFullDocumentBeforeChange();

        observed.add(new Document("operationType", type == null ? null : type.getValue())
                .append("documentKey", toDocument(raw.getDocumentKey()))
                .append("before", before)
                .append("after", after)
                .append("at", Instant.now()));

        // Phase 7.7 - project the change into a denormalised view. This is the usual reason to
        // run CDC at all: keep a read-optimised shape in step without the writer knowing.
        if (after != null && after.get("account_id") != null) {
            analytics.upsert(
                    org.springframework.data.mongodb.core.query.Query.query(
                            Criteria.where("_id").is(after.get("account_id"))),
                    new Update()
                            .set("balance", after.get("balance"))
                            .set("limit", after.get("limit"))
                            .set("lastOperation", type == null ? null : type.getValue())
                            .set("lastSeenAt", Instant.now())
                            .inc("changeCount", 1),
                    SUMMARY_COLLECTION);
        }

        // Phase 7.5 - persist after handling, never before. Saving first means a crash between
        // the two loses the event with no way to notice.
        BsonDocument token = raw.getResumeToken();
        if (token != null) {
            tokenStore.save(LISTENER_ID, token);
        }
    }

    private static Document toDocument(BsonDocument bson) {
        return bson == null ? null : Document.parse(bson.toJson());
    }

    /** Phase 7.4 - enables before-images so an audit diff is possible at all. */
    public void enablePreAndPostImages(String collection) {
        analytics.getDb().runCommand(new Document("collMod", collection)
                .append("changeStreamPreAndPostImages", new Document("enabled", true)));
    }

    public List<Document> observed() {
        return List.copyOf(observed);
    }

    public long eventCount() {
        return eventCount.get();
    }

    public void reset() {
        observed.clear();
        eventCount.set(0);
    }

    public boolean isRunning() {
        return subscription != null;
    }
}
