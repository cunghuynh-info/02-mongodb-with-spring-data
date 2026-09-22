package vn.infodation.mongodb.mflix.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import vn.infodation.mongodb.mflix.dto.DashboardStats;
import vn.infodation.mongodb.supplies.service.SalesAggregationService;
import vn.infodation.mongodb.support.AbstractMongoIntegrationTest;
import vn.infodation.mongodb.support.SampleFixtures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 9.6 - {@link DashboardService} combines {@link MovieAggregationService#genreStats}
 * (against {@code sample_mflix}) with {@link SalesAggregationService#revenuePerStoreAndMonth}
 * (against {@code sample_supplies}). Both are already covered on their own elsewhere; this only
 * asserts the combination is correct - the concurrency itself is easiest to see by comparing
 * wall-clock time against calling the two sequentially, not by asserting on timing here.
 */
class DashboardServiceIT extends AbstractMongoIntegrationTest {

    @Autowired
    DashboardService dashboardService;

    @Autowired
    MongoTemplate mflix;

    @Autowired
    @Qualifier("suppliesTemplate")
    MongoTemplate supplies;

    @BeforeEach
    void setUp() {
        SampleFixtures.reset(mflix);

        supplies.remove(new Query(), "sales");
        supplies.insert(new Document("saleDate", Date.from(Instant.parse("2017-03-15T00:00:00Z")))
                .append("storeLocation", "Denver")
                .append("items", List.of(new Document("name", "thing")
                        .append("price", new Decimal128(new BigDecimal("12.50")))
                        .append("quantity", 2))), "sales");
    }

    @Test
    void combinesBothAggregationsIntoOneResult() {
        DashboardStats stats = dashboardService.dashboard(5);

        assertThat(stats.genres()).isNotEmpty();
        assertThat(stats.revenue()).hasSize(1);
        assertThat(stats.revenue().get(0).storeLocation()).isEqualTo("Denver");
        assertThat(stats.tookMillis()).isGreaterThanOrEqualTo(0);
    }
}
