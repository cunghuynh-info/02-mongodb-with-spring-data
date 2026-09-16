package vn.infodation.mongodb.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;

/**
 * Phase 7.1 - turns on the auditing entity callbacks.
 * <p>
 * Worth knowing where this does <em>not</em> reach: the callbacks fire for
 * {@code MongoTemplate} saves and repository writes, and not at all for an update expressed as
 * {@code $inc} or {@code $addToSet} - Phase 2.4's vote and genre endpoints go straight to the
 * server without an entity ever existing, so no annotation on the class can observe them. An
 * audit trail built only from these annotations therefore has holes exactly where the atomic
 * update operators are used, which tends to be the interesting writes.
 */
@Configuration
@EnableMongoAuditing(auditorAwareRef = "securityAuditorAware")
public class MongoAuditingConfig {
}
