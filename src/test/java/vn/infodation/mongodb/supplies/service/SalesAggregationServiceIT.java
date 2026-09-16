package vn.infodation.mongodb.supplies.service;

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

import vn.infodation.mongodb.supplies.dto.StoreRevenue;
import vn.infodation.mongodb.support.AbstractMongoIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2.8 - also the proof that the Phase 0.3 multi-database templates actually reach a
 * second database on the same connection.
 */
class SalesAggregationServiceIT extends AbstractMongoIntegrationTest {

    @Autowired
    SalesAggregationService salesAggregationService;

    @Autowired
    SalesQueryService salesQueryService;

    @Autowired
    @Qualifier("suppliesTemplate")
    MongoTemplate suppliesTemplate;

    @BeforeEach
    void setUp() {
        suppliesTemplate.remove(new Query(), "sales");
        suppliesTemplate.insert(List.of(
                sale("2017-03-15T00:00:00Z", "Denver",
                        item("12.50", 2), item("5.00", 3)),
                sale("2017-03-20T00:00:00Z", "Denver",
                        item("10.00", 1)),
                sale("2017-04-01T00:00:00Z", "Seattle",
                        item("7.25", 4))),
                "sales");
    }

    @Test
    void rollsUpRevenuePerStoreAndMonth() {
        List<StoreRevenue> revenue = salesAggregationService.revenuePerStoreAndMonth(50);

        assertThat(revenue).hasSize(2);

        StoreRevenue denver = revenue.get(0);
        assertThat(denver.storeLocation()).isEqualTo("Denver");
        assertThat(denver.year()).isEqualTo(2017);
        assertThat(denver.month()).isEqualTo(3);
        // 12.50*2 + 5.00*3 + 10.00*1, summed as Decimal128 so there is no float drift.
        assertThat(denver.revenue()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(denver.lineItems()).isEqualTo(3);

        StoreRevenue seattle = revenue.get(1);
        assertThat(seattle.storeLocation()).isEqualTo("Seattle");
        assertThat(seattle.month()).isEqualTo(4);
        assertThat(seattle.revenue()).isEqualByComparingTo(new BigDecimal("29.00"));
        assertThat(seattle.lineItems()).isEqualTo(1);
    }

    @Test
    void writesLandInTheSuppliesDatabaseNotTheDefaultOne() {
        assertThat(suppliesTemplate.getDb().getName()).isEqualTo("sample_supplies");
    }

    /**
     * Phase 2.2 - the case that makes {@code $elemMatch} necessary. The "mixed" sale holds a
     * laptop (quantity 1) and a mouse (quantity 9): separate predicates are both satisfied by
     * the document, but no single item satisfies both.
     */
    @Test
    void elemMatchRequiresOneElementToSatisfyEveryCondition() {
        suppliesTemplate.remove(new Query(), "sales");
        suppliesTemplate.insert(List.of(
                        sale("2017-05-01T00:00:00Z", "Denver",
                                named("laptop", "900.00", 1), named("mouse", "20.00", 9)),
                        sale("2017-05-02T00:00:00Z", "Denver",
                                named("laptop", "900.00", 6))),
                "sales");

        assertThat(salesQueryService.salesWithItem("laptop", 5)).hasSize(1);
        assertThat(salesQueryService.salesWithItemLoose("laptop", 5)).hasSize(2);
    }

    /**
     * Phase 2.8 - $densify fills the months that $group could not emit because nothing sold.
     * Denver sells in January and April only; February and March must appear as zeroes.
     */
    @Test
    void densifyFillsTheMonthsWithNoSales() {
        suppliesTemplate.remove(new Query(), "sales");
        suppliesTemplate.insert(List.of(
                        sale("2017-01-10T00:00:00Z", "Denver", item("10.00", 1)),
                        sale("2017-04-10T00:00:00Z", "Denver", item("20.00", 1))),
                "sales");

        List<Document> dense = salesAggregationService.revenuePerStoreAndMonthDense(50);

        // Jan, Feb, Mar, Apr - four rows from two documents.
        assertThat(dense).hasSize(4);
        assertThat(dense).allSatisfy(row -> assertThat(row.getString("storeLocation")).isEqualTo("Denver"));

        List<Object> revenues = dense.stream().map(row -> row.get("revenue")).toList();
        assertThat(revenues).hasSize(4);
        // The invented months are explicit zeroes, not nulls, so a chart cannot skip them.
        assertThat(dense.get(1).get("revenue")).isNotNull();
        assertThat(dense.get(1).get("lineItems")).isEqualTo(0);
        assertThat(dense.get(2).get("lineItems")).isEqualTo(0);

        // The real months kept their values.
        assertThat(dense.get(0).get("lineItems")).isEqualTo(1);
        assertThat(dense.get(3).get("lineItems")).isEqualTo(1);
    }

    private static Document sale(String saleDate, String storeLocation, Document... items) {
        return new Document("saleDate", Date.from(Instant.parse(saleDate)))
                .append("storeLocation", storeLocation)
                .append("items", List.of(items));
    }

    private static Document item(String price, int quantity) {
        return named("thing", price, quantity);
    }

    private static Document named(String name, String price, int quantity) {
        return new Document("name", name)
                .append("price", new Decimal128(new BigDecimal(price)))
                .append("quantity", quantity);
    }
}
