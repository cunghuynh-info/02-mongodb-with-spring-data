package vn.infodation.mongodb.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import vn.infodation.mongodb.config.MongoTemplatesConfig;

/**
 * Points the application at {@link MongoLabContainer} instead of the compose lab, so the suite
 * runs anywhere Docker does and never touches the seeded data a human is poking at.
 * <p>
 * The trade is that assertions are made against the fixtures in {@link SampleFixtures} rather
 * than against the real Atlas sample data - deterministic, and small enough to reason about.
 */
@SpringBootTest
public abstract class AbstractMongoIntegrationTest {

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri",
                () -> MongoLabContainer.INSTANCE.getDatabaseConnectionString(MongoTemplatesConfig.MFLIX_DB));
        registry.add("lab.startup-check.enabled", () -> "false");
    }
}
