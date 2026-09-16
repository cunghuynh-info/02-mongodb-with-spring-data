package vn.infodation.mongodb.mflix.web;

import java.util.List;

import org.bson.Document;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import vn.infodation.mongodb.common.ExplainSummary;
import vn.infodation.mongodb.common.ObjectIds;
import vn.infodation.mongodb.common.PageResponse;
import vn.infodation.mongodb.mflix.dto.MovieDetail;
import vn.infodation.mongodb.mflix.dto.MovieSearchCriteria;
import vn.infodation.mongodb.mflix.dto.MovieSummary;
import vn.infodation.mongodb.mflix.service.MovieService;

@RestController
@RequestMapping("/api/movies")
@RequiredArgsConstructor
public class MovieController {

    private final MovieService movieService;

    /** Phase 1.1 */
    @GetMapping("/{id}")
    public MovieDetail get(@PathVariable String id) {
        return movieService.detail(ObjectIds.parse(id));
    }

    /** Phase 2.2 + 4.1 (offset paging). */
    @GetMapping
    public PageResponse<MovieDetail> search(
            MovieSearchCriteria filter,
            @PageableDefault(size = 20, sort = "year", direction = Sort.Direction.DESC) Pageable pageable) {
        return movieService.search(filter, pageable);
    }

    /** Phase 2.3 */
    @GetMapping("/summaries")
    public List<MovieSummary> summaries(
            MovieSearchCriteria filter,
            @PageableDefault(size = 20, sort = "year", direction = Sort.Direction.DESC) Pageable pageable) {
        return movieService.summaries(filter, pageable);
    }

    /** Phase 2.9 - {@code ?raw=true} for the untrimmed explain output. */
    @GetMapping("/explain")
    public Object explain(
            MovieSearchCriteria filter,
            @RequestParam(defaultValue = "false") boolean raw,
            @PageableDefault(size = 20, sort = "year", direction = Sort.Direction.DESC) Pageable pageable) {
        if (raw) {
            Document document = movieService.explainRaw(filter, pageable);
            return document;
        }
        ExplainSummary summary = movieService.explain(filter, pageable);
        return summary;
    }

    /** Phase 2.4 - {@code $inc}. */
    @PostMapping("/{id}/votes")
    public MovieDetail addVotes(@PathVariable String id, @RequestParam(defaultValue = "1") long delta) {
        return movieService.addVotes(ObjectIds.parse(id), delta);
    }

    /** Phase 2.4 - {@code $addToSet}; calling it twice changes nothing. */
    @PostMapping("/{id}/genres")
    public MovieDetail addGenre(@PathVariable String id, @RequestParam String genre) {
        return movieService.addGenre(ObjectIds.parse(id), genre);
    }

    /** Phase 2.4 - {@code $pull}. */
    @DeleteMapping("/{id}/genres")
    public MovieDetail removeGenre(@PathVariable String id, @RequestParam String genre) {
        return movieService.removeGenre(ObjectIds.parse(id), genre);
    }
}
