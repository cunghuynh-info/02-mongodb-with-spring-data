package vn.infodation.mongodb.supplies.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOptions;
import org.springframework.data.mongodb.core.aggregation.ArithmeticOperators;
import org.springframework.data.mongodb.core.aggregation.DateOperators;
import org.springframework.stereotype.Service;

import org.bson.Document;

import vn.infodation.mongodb.common.RawStage;
import vn.infodation.mongodb.supplies.dto.StoreRevenue;

/**
 * Phase 2.8 - a second database, reached through the {@code suppliesTemplate} from Phase 0.3.
 * <p>
 * {@code items} is an array of line items, so the money lives one {@code $unwind} down.
 * {@code items.price} is a Decimal128 in the sample data, which is why the sum maps cleanly to
 * {@link java.math.BigDecimal} instead of accumulating float error.
 */
@Service
public class SalesAggregationService {

    private static final String SALES = "sales";

    private final MongoTemplate suppliesTemplate;

    public SalesAggregationService(@Qualifier("suppliesTemplate") MongoTemplate suppliesTemplate) {
        this.suppliesTemplate = suppliesTemplate;
    }

    public List<StoreRevenue> revenuePerStoreAndMonth(int limit) {
        Aggregation aggregation = Aggregation.newAggregation(
                        Aggregation.unwind("items"),
                        Aggregation.project()
                                .and("storeLocation").as("storeLocation")
                                .and(DateOperators.dateOf("saleDate").year()).as("year")
                                .and(DateOperators.dateOf("saleDate").month()).as("month")
                                .and(ArithmeticOperators.valueOf("items.price")
                                        .multiplyBy("items.quantity")).as("lineTotal"),
                        Aggregation.group("storeLocation", "year", "month")
                                .sum("lineTotal").as("revenue")
                                .count().as("lineItems"),
                        Aggregation.sort(Sort.by(
                                Sort.Order.asc("_id.storeLocation"),
                                Sort.Order.asc("_id.year"),
                                Sort.Order.asc("_id.month"))),
                        Aggregation.limit(limit),
                        Aggregation.project("revenue", "lineItems")
                                .and("_id.storeLocation").as("storeLocation")
                                .and("_id.year").as("year")
                                .and("_id.month").as("month")
                                .andExclude("_id"))
                .withOptions(AggregationOptions.builder().allowDiskUse(true).build());

        return suppliesTemplate.aggregate(aggregation, SALES, StoreRevenue.class).getMappedResults();
    }

    /**
     * Phase 2.8, second form - {@code $dateTrunc} to bucket by month, and {@code $densify} to
     * invent the months where nothing sold.
     * <p>
     * {@code $group} can only emit months that exist in the data, so a store with no sales in
     * February is simply absent - and a chart drawn from that silently closes the gap, making
     * the dip disappear. {@code $densify} inserts the missing documents, and the
     * {@code $ifNull} afterwards is what turns them into explicit zeroes rather than nulls.
     * <p>
     * Written as raw stages: neither operator has a typed builder worth the indirection here,
     * and these are copy-pasteable into mongosh.
     */
    public List<Document> revenuePerStoreAndMonthDense(int limit) {
        Aggregation aggregation = Aggregation.newAggregation(
                        RawStage.of("{ \"$unwind\": \"$items\" }"),
                        RawStage.of("""
                                { "$set": {
                                    "month": { "$dateTrunc": { "date": "$saleDate", "unit": "month" } },
                                    "lineTotal": { "$multiply": ["$items.price", "$items.quantity"] }
                                } }"""),
                        RawStage.of("""
                                { "$group": {
                                    "_id": { "storeLocation": "$storeLocation", "month": "$month" },
                                    "revenue": { "$sum": "$lineTotal" },
                                    "lineItems": { "$sum": 1 }
                                } }"""),
                        RawStage.of("""
                                { "$set": { "storeLocation": "$_id.storeLocation", "month": "$_id.month" } }"""),
                        RawStage.of("{ \"$sort\": { \"storeLocation\": 1, \"month\": 1 } }"),
                        // bounds "partition" densifies between each store's own first and last
                        // month; "full" would span every store's range, inventing months before
                        // a shop existed.
                        RawStage.of("""
                                { "$densify": {
                                    "field": "month",
                                    "partitionByFields": ["storeLocation"],
                                    "range": { "step": 1, "unit": "month", "bounds": "partition" }
                                } }"""),
                        RawStage.of("""
                                { "$set": {
                                    "revenue": { "$ifNull": ["$revenue", 0] },
                                    "lineItems": { "$ifNull": ["$lineItems", 0] }
                                } }"""),
                        RawStage.of("""
                                { "$project": { "_id": 0, "storeLocation": 1, "month": 1,
                                                "revenue": 1, "lineItems": 1 } }"""),
                        RawStage.of("{ \"$limit\": " + limit + " }"))
                .withOptions(AggregationOptions.builder().allowDiskUse(true).build());

        return suppliesTemplate.aggregate(aggregation, SALES, Document.class).getMappedResults();
    }
}
