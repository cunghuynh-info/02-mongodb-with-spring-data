package vn.infodation.mongodb.mflix.dto;

import java.util.List;

import vn.infodation.mongodb.supplies.dto.StoreRevenue;

/** Phase 9.6 output - two independent aggregations, fetched concurrently. */
public record DashboardStats(List<GenreStats> genres, List<StoreRevenue> revenue, long tookMillis) {
}
