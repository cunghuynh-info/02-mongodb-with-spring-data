package vn.infodation.mongodb.supplies.dto;

import java.math.BigDecimal;

/** Phase 2.8 output - {@code sample_supplies.sales} rolled up per store and month. */
public record StoreRevenue(String storeLocation, Integer year, Integer month,
                           BigDecimal revenue, long lineItems) {
}
