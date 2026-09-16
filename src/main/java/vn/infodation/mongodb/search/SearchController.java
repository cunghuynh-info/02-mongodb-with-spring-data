package vn.infodation.mongodb.search;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** Phase 8 - Atlas Search, served by mongot in the atlas-local image. */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;
    private final SearchIndexService indexService;

    /** Phase 8.1 / 8.2 */
    @GetMapping("/movies")
    public List<Document> search(@RequestParam String q,
                                 @RequestParam(defaultValue = "10") int limit) {
        return searchService.search(q, limit);
    }

    /** Phase 8.3 */
    @GetMapping("/autocomplete")
    public List<Document> autocomplete(@RequestParam String q,
                                       @RequestParam(defaultValue = "10") int limit) {
        return searchService.autocomplete(q, limit);
    }

    /** Phase 8.4 */
    @GetMapping("/compound")
    public List<Document> compound(@RequestParam String q,
                                   @RequestParam(required = false) String genre,
                                   @RequestParam(required = false) Integer minYear,
                                   @RequestParam(defaultValue = "10") int limit) {
        return searchService.compound(q, genre, minYear, limit);
    }

    /** Phase 8.5 */
    @GetMapping("/facets")
    public Document facets(@RequestParam String q) {
        return searchService.facets(q);
    }

    /** Phase 8.6 - pass the previous response's {@code nextToken} back as {@code after}. */
    @GetMapping("/page")
    public Document page(@RequestParam String q,
                         @RequestParam(required = false) String after,
                         @RequestParam(defaultValue = "5") int size) {
        return searchService.page(q, after, size);
    }

    /** Phase 8.7 */
    @GetMapping("/fuzzy")
    public List<Document> fuzzy(@RequestParam String q,
                                @RequestParam(defaultValue = "1") int maxEdits,
                                @RequestParam(defaultValue = "10") int limit) {
        return searchService.fuzzy(q, maxEdits, limit);
    }

    @GetMapping("/index")
    public Map<String, Object> indexStatus() {
        Document info = indexService.info(SearchIndexService.MOVIES_INDEX);
        return info == null
                ? Map.of("exists", false)
                : Map.of("exists", true,
                "status", String.valueOf(info.getString("status")),
                "queryable", String.valueOf(info.getBoolean("queryable")));
    }

    /** Recreate the index from {@link SearchIndexService#moviesIndexDefinition()} and wait. */
    @PostMapping("/index")
    public Map<String, Object> rebuildIndex() {
        indexService.ensureMoviesIndex();
        boolean queryable = indexService.awaitQueryable(SearchIndexService.MOVIES_INDEX, Duration.ofMinutes(3));
        return Map.of("queryable", queryable);
    }
}
