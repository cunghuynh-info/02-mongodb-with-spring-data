package vn.infodation.mongodb.config;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;

/**
 * Phase 6.1 / 6.5 - transactions against {@code sample_analytics}.
 * <p>
 * Multi-document transactions need a replica set; the lab is a single-node one, which counts.
 * <p>
 * The manager is built from {@code analyticsTemplate}'s own {@code MongoDatabaseFactory}, not
 * from the primary one. A {@code MongoTransactionManager} binds the session to a specific
 * factory, and work done through a template built on a <em>different</em> factory silently runs
 * outside the transaction - no error, no rollback.
 */
@Configuration
@EnableTransactionManagement
public class TransactionConfig {

    public static final String TRANSACTION_MANAGER = "analyticsTransactionManager";

    @Bean(TRANSACTION_MANAGER)
    public MongoTransactionManager analyticsTransactionManager(
            @Qualifier("analyticsTemplate") MongoTemplate analytics) {

        TransactionOptions options = TransactionOptions.builder()
                // snapshot: every read in the transaction sees the same point in time.
                .readConcern(ReadConcern.SNAPSHOT)
                // majority: the commit is durable on a majority before it is acknowledged, so a
                // failover cannot roll it back under you. Snapshot + majority together are what
                // make the read-modify-write in TransferService safe.
                .writeConcern(WriteConcern.MAJORITY)
                .readPreference(ReadPreference.primary())
                .maxCommitTime(5L, TimeUnit.SECONDS)
                .build();

        return new MongoTransactionManager(analytics.getMongoDatabaseFactory(), options);
    }
}
