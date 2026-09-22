package vn.infodation.mongodb.mflix.web;

import java.util.List;

import org.bson.Document;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import vn.infodation.mongodb.mflix.dto.DashboardStats;
import vn.infodation.mongodb.mflix.dto.GenreStats;
import vn.infodation.mongodb.mflix.dto.MostCommentedMovie;
import vn.infodation.mongodb.mflix.dto.MovieSearchCriteria;
import vn.infodation.mongodb.mflix.service.DashboardService;
import vn.infodation.mongodb.mflix.service.MovieAggregationService;
import vn.infodation.mongodb.supplies.dto.StoreRevenue;
import vn.infodation.mongodb.supplies.service.SalesAggregationService;

/** Phase 2.5 - 2.8. */
@Tag(name = "2 - Aggregation",
        description = "$unwind, $group, $facet and $bucket, plus a $lookup with a sub-pipeline. "
                + "/stats/browse returns counts, buckets and a page in one round trip.")
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final MovieAggregationService movieAggregationService;
    private final SalesAggregationService salesAggregationService;
    private final DashboardService dashboardService;

    @GetMapping("/genres")
    public List<GenreStats> genres(@RequestParam(defaultValue = "20") int limit) {
        return movieAggregationService.genreStats(limit);
    }

    @GetMapping("/browse")
    public Document browse(MovieSearchCriteria filter,
                           @RequestParam(defaultValue = "10") int pageSize) {
        return movieAggregationService.browse(filter, pageSize);
    }

    @GetMapping("/most-commented")
    public List<MostCommentedMovie> mostCommented(
            @RequestParam(defaultValue = "2000") int fromYear,
            @RequestParam(defaultValue = "1") int minComments,
            @RequestParam(defaultValue = "20") int limit) {
        return movieAggregationService.mostCommented(fromYear, minComments, limit);
    }

    @GetMapping("/sales-revenue")
    public List<StoreRevenue> salesRevenue(@RequestParam(defaultValue = "50") int limit) {
        return salesAggregationService.revenuePerStoreAndMonth(limit);
    }

    /** Phase 2.8 - the same rollup with $dateTrunc, and empty months filled in by $densify. */
    @GetMapping("/sales-revenue-dense")
    public List<Document> salesRevenueDense(@RequestParam(defaultValue = "50") int limit) {
        return salesAggregationService.revenuePerStoreAndMonthDense(limit);
    }

    /**
     * Phase 9.6 - {@link #genres} and {@link #salesRevenue} fetched concurrently against two
     * different databases instead of one after the other; compare the two endpoints' combined
     * latency against calling this one to see the difference {@code @Async} makes here.
     */
    @GetMapping("/dashboard")
    public DashboardStats dashboard(@RequestParam(defaultValue = "20") int limit) {
        return dashboardService.dashboard(limit);
    }
}
