package vn.infodation.mongodb;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;

import vn.infodation.mongodb.support.AbstractMongoIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 0.2 / 0.3 - the context starts and every template points where it should. */
class ApplicationContextIT extends AbstractMongoIntegrationTest {

    @Autowired
    MongoTemplate mongoTemplate;

    @Autowired
    @Qualifier("analyticsTemplate")
    MongoTemplate analyticsTemplate;

    @Autowired
    @Qualifier("trainingTemplate")
    MongoTemplate trainingTemplate;

    @Test
    void contextLoads() {
        assertThat(mongoTemplate.getDb().getName()).isEqualTo("sample_mflix");
        assertThat(analyticsTemplate.getDb().getName()).isEqualTo("sample_analytics");
        assertThat(trainingTemplate.getDb().getName()).isEqualTo("sample_training");
    }

    @Test
    void theExtraTemplatesShareTheAutoConfiguredConverter() {
        assertThat(analyticsTemplate.getConverter()).isSameAs(mongoTemplate.getConverter());
    }
}
