package vn.infodation.mongodb.mflix.web;

import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import vn.infodation.mongodb.common.ExplainSummary;
import vn.infodation.mongodb.config.IndexConfig;
import vn.infodation.mongodb.mflix.service.IndexInspectionService;

/** Phase 3 - look at what exists, and at what the planner does with it. */
@Tag(name = "3 - Indexes", description = "What exists, covered queries, and the partial-index implication trap. Admin only.")
@RestController
@RequestMapping("/api/indexes")
@RequiredArgsConstructor
public class IndexController {

    private final IndexInspectionService inspectionService;

    @GetMapping("/{collection}")
    public List<Map<String, Object>> indexes(@PathVariable String collection) {
        return inspectionService.indexes(collection);
    }

    /**
     * Phase 3.4 - a covered query. Every field requested is in the index, so the server never
     * touches a document: {@code docsExamined} comes back 0.
     * <p>
     * Note what is <em>not</em> projected: {@code genres}. It is the array field that makes this
     * index multikey, and a multikey index cannot cover a projection of the array itself - the
     * index holds one entry per element, not the original array.
     */
    @GetMapping("/covered-query")
    public ExplainSummary coveredQuery(@RequestParam(defaultValue = "Crime") String genre) {
        return inspectionService.explain("movies",
                new Document("genres", genre),
                new Document("year", -1),
                new Document("_id", 0).append("year", 1).append("imdb.rating", 1));
    }

    /**
     * Phase 3.6 - the partial-index trap. {@code threshold} above the index's own
     * {@code imdb.rating > 7} filter implies it and can use the index; anything below cannot,
     * however few documents it would match.
     */
    @GetMapping("/partial-index")
    public Map<String, ExplainSummary> partialIndex(@RequestParam(defaultValue = "9.0") double threshold) {
        return Map.of(
                "impliesTheFilter", inspectionService.explain("movies",
                        new Document("imdb.rating", new Document("$gte", threshold)), null, null),
                "doesNotImplyTheFilter", inspectionService.explain("movies",
                        new Document("imdb.rating", new Document("$gte", 1.0)), null, null),
                "sameQueryHintedAtTheFullIndex", inspectionService.explainWithHint("movies",
                        new Document("imdb.rating", new Document("$gte", threshold)),
                        IndexConfig.MOVIES_BROWSE));
    }
}
