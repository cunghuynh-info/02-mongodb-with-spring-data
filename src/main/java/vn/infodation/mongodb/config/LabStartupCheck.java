package vn.infodation.mongodb.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Phase 0.2 - fails loudly at startup when the lab is not seeded, instead of letting every
 * endpoint return an empty list. Disable with {@code lab.startup-check.enabled=false}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "lab.startup-check.enabled", havingValue = "true", matchIfMissing = true)
public class LabStartupCheck implements ApplicationRunner {

    private final Map<String, MongoTemplate> templates = new LinkedHashMap<>();

    public LabStartupCheck(MongoTemplate mflix,
                           @Qualifier("analyticsTemplate") MongoTemplate analytics,
                           @Qualifier("trainingTemplate") MongoTemplate training,
                           @Qualifier("suppliesTemplate") MongoTemplate supplies) {
        templates.put("movies", mflix);
        templates.put("accounts", analytics);
        templates.put("inspections", training);
        templates.put("sales", supplies);
    }

    @Override
    public void run(ApplicationArguments args) {
        templates.forEach((collection, template) -> {
            String database = template.getDb().getName();
            try {
                long count = template.getCollection(collection).estimatedDocumentCount();
                if (count == 0) {
                    log.warn("{}.{} is empty - run `docker compose up -d` and wait for mongo-lab-seed",
                            database, collection);
                } else {
                    log.info("{}.{}: ~{} documents", database, collection, count);
                }
            } catch (RuntimeException ex) {
                log.warn("could not read {}.{}: {}", database, collection, ex.getMessage());
            }
        });
    }
}
