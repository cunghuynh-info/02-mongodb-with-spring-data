package vn.infodation.mongodb.config;

import com.mongodb.client.MongoClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.convert.MongoConverter;

/**
 * Phase 0.3 - the Atlas sample data spans several databases, but Spring Boot auto-configures
 * exactly one {@link MongoTemplate} bound to the database in the connection string.
 * <p>
 * Declaring any {@code MongoOperations} bean makes {@code MongoDataAutoConfiguration} back off
 * from its own {@code mongoTemplate}, so the primary template is redeclared here as well. The
 * bean name stays {@code mongoTemplate} because that is what Spring Data repositories look up
 * by default, and {@link Primary} keeps plain {@code MongoTemplate} injection unambiguous.
 * <p>
 * All templates share the auto-configured {@link MongoConverter}, so the mapping metadata and
 * the custom conversions in {@link SampleDataConversions} apply everywhere.
 */
@Configuration
public class MongoTemplatesConfig {

    public static final String MFLIX_DB = "sample_mflix";
    public static final String ANALYTICS_DB = "sample_analytics";
    public static final String TRAINING_DB = "sample_training";
    public static final String SUPPLIES_DB = "sample_supplies";

    @Bean
    @Primary
    public MongoTemplate mongoTemplate(MongoDatabaseFactory databaseFactory, MongoConverter converter) {
        return new MongoTemplate(databaseFactory, converter);
    }

    @Bean
    public MongoTemplate analyticsTemplate(MongoClient client, MongoConverter converter) {
        return templateFor(client, converter, ANALYTICS_DB);
    }

    @Bean
    public MongoTemplate trainingTemplate(MongoClient client, MongoConverter converter) {
        return templateFor(client, converter, TRAINING_DB);
    }

    @Bean
    public MongoTemplate suppliesTemplate(MongoClient client, MongoConverter converter) {
        return templateFor(client, converter, SUPPLIES_DB);
    }

    private static MongoTemplate templateFor(MongoClient client, MongoConverter converter, String database) {
        return new MongoTemplate(new SimpleMongoClientDatabaseFactory(client, database), converter);
    }
}
