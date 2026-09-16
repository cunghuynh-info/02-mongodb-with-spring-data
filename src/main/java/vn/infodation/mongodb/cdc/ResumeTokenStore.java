package vn.infodation.mongodb.cdc;

import java.time.Instant;
import java.util.Optional;

import org.bson.BsonDocument;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Phase 7.5 - where a listener left off.
 * <p>
 * Without this, a restart resumes from "now" and every change that happened while the process
 * was down is lost. The token is opaque: it is the change event's own {@code _id}, and the only
 * valid thing to do with it is hand it back as {@code resumeAfter}.
 * <p>
 * Stored in the same deployment as the data on purpose - a token that survives independently of
 * the oplog it points into is a token that will eventually be too old to use (7.6).
 */
@Component
public class ResumeTokenStore {

    public static final String COLLECTION = "cdc_offsets";

    private final MongoTemplate analytics;

    public ResumeTokenStore(@Qualifier("analyticsTemplate") MongoTemplate analytics) {
        this.analytics = analytics;
    }

    public Optional<BsonDocument> load(String listener) {
        Document stored = analytics.findOne(byId(listener), Document.class, COLLECTION);
        if (stored == null) {
            return Optional.empty();
        }
        Document token = stored.get("token", Document.class);
        return Optional.ofNullable(token).map(Document::toBsonDocument);
    }

    public void save(String listener, BsonDocument token) {
        analytics.upsert(byId(listener),
                new Update()
                        .set("token", token)
                        .set("updatedAt", Instant.now()),
                COLLECTION);
    }

    public void clear(String listener) {
        analytics.remove(byId(listener), COLLECTION);
    }

    private static Query byId(String listener) {
        return Query.query(Criteria.where("_id").is(listener));
    }
}
