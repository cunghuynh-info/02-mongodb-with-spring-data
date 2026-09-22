package vn.infodation.mongodb.mflix.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import vn.infodation.mongodb.mflix.dto.DashboardStats;
import vn.infodation.mongodb.mflix.dto.GenreStats;
import vn.infodation.mongodb.supplies.dto.StoreRevenue;
import vn.infodation.mongodb.supplies.service.SalesAggregationService;

/**
 * Phase 9.6 - two aggregations against two different databases ({@code sample_mflix} and
 * {@code sample_supplies}), with nothing linking them, run concurrently instead of one after
 * the other.
 * <p>
 * {@link CompletableFuture#allOf} does not fail until every future passed to it finishes - if
 * {@code genres} failed after 5 ms and {@code revenue} took another 200 ms to time out, the
 * combined call would not surface the failure until those 200 ms were up. That trade (a slow
 * success can delay a fast failure) is worth knowing before reaching for {@code allOf} in a
 * service where failing fast matters more than it does here.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final MovieAggregationService movieAggregationService;
    private final SalesAggregationService salesAggregationService;

    public DashboardStats dashboard(int limit) {
        long start = System.nanoTime();

        CompletableFuture<List<GenreStats>> genres = movieAggregationService.genreStatsAsync(limit);
        CompletableFuture<List<StoreRevenue>> revenue = salesAggregationService.revenuePerStoreAndMonthAsync(limit);

        CompletableFuture.allOf(genres, revenue).join();

        long tookMillis = (System.nanoTime() - start) / 1_000_000;
        return new DashboardStats(genres.join(), revenue.join(), tookMillis);
    }
}
