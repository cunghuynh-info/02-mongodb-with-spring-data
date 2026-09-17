package vn.infodation.mongodb.mflix.web;

import java.util.Map;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Slice;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import vn.infodation.mongodb.common.ExplainSummary;
import vn.infodation.mongodb.common.KeysetPage;
import vn.infodation.mongodb.mflix.dto.MovieDetail;
import vn.infodation.mongodb.mflix.dto.MovieSearchCriteria;
import vn.infodation.mongodb.mflix.service.MoviePagingService;

/** Phase 4. Offset paging itself lives on {@code GET /api/movies} (4.1). */
@Tag(name = "4 - Paging", description = "Offset, slice, scroll and keyset over the same data. The benchmark is admin only.")
@RestController
@RequestMapping("/api/movies")
@RequiredArgsConstructor
public class PagingController {

    private final MoviePagingService pagingService;

    /** Phase 4.3 - no count query. */
    @GetMapping("/slice")
    public Slice<MovieDetail> slice(@ParameterObject MovieSearchCriteria filter,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return pagingService.slice(filter, page, size);
    }

    /** Phase 4.4 - Spring Data {@code Window} keyset scrolling. */
    @GetMapping("/scroll")
    public KeysetPage<MovieDetail> scroll(@ParameterObject MovieSearchCriteria filter,
                                          @RequestParam(required = false) String cursor,
                                          @RequestParam(defaultValue = "20") int size) {
        return pagingService.scroll(filter, cursor, size);
    }

    /** Phase 4.5 / 4.6 - hand-written keyset behind an opaque cursor. */
    @GetMapping("/keyset")
    public KeysetPage<MovieDetail> keyset(@ParameterObject MovieSearchCriteria filter,
                                          @RequestParam(required = false) String cursor,
                                          @RequestParam(defaultValue = "20") int size) {
        return pagingService.keyset(filter, cursor, size);
    }

    /** Phase 4.2 - offset page 0 vs page N vs keyset at the same depth. */
    @GetMapping("/paging-benchmark")
    public Map<String, Object> benchmark(@ParameterObject MovieSearchCriteria filter,
                                         @RequestParam(defaultValue = "20") int size,
                                         @RequestParam(defaultValue = "500") int deepPage) {
        return pagingService.benchmark(filter, size, deepPage);
    }

    /** Phase 4.7 */
    @GetMapping("/keyset/explain")
    public ExplainSummary explainKeyset(@ParameterObject MovieSearchCriteria filter,
                                        @RequestParam(required = false) String cursor,
                                        @RequestParam(defaultValue = "20") int size) {
        return pagingService.explainKeyset(filter, cursor, size);
    }
}
