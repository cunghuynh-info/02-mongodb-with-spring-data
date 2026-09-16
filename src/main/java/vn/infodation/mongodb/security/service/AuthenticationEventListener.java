package vn.infodation.mongodb.security.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.bson.Document;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Phase 7.3 - a login audit trail.
 * <p>
 * Written as a raw {@link Document} rather than a mapped entity: this is an append-only log with
 * no behaviour and nothing ever reads it back into Java, so a domain class would be ceremony.
 * The collection has a TTL index in {@code IndexConfig} - an auth log that grows forever is a
 * liability rather than an asset.
 * <p>
 * The email is recorded, never the password, and never the token. A failure event carries the
 * attempted principal, which is enough to spot someone working through a list of addresses.
 */
@Component
@RequiredArgsConstructor
public class AuthenticationEventListener {

    public static final String COLLECTION = "lab_auth_events";

    private final MongoTemplate mongoTemplate;

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        record("SUCCESS", String.valueOf(event.getAuthentication().getName()), null);
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        record("FAILURE", String.valueOf(event.getAuthentication().getName()),
                event.getException().getClass().getSimpleName());
    }

    private void record(String outcome, String email, String reason) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("outcome", outcome);
        event.put("email", email);
        event.put("reason", reason);
        event.put("createdAt", Instant.now());

        mongoTemplate.insert(new Document(event), COLLECTION);
    }
}
