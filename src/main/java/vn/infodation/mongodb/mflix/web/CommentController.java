package vn.infodation.mongodb.mflix.web;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import vn.infodation.mongodb.common.ObjectIds;
import vn.infodation.mongodb.common.PageResponse;
import vn.infodation.mongodb.mflix.dto.CommentView;
import vn.infodation.mongodb.mflix.dto.MovieWithComments;
import vn.infodation.mongodb.mflix.service.CommentService;

/**
 * Phase 1 - the four read paths sit side by side on purpose. Hit each with the same movie id
 * and compare the response and the query log.
 */
@RestController
@RequestMapping("/api/movies/{id}/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    /** Phase 1.2 - referenced and paged. */
    @GetMapping
    public PageResponse<CommentView> list(
            @PathVariable String id,
            @PageableDefault(size = 20) Pageable pageable) {
        return commentService.commentsFor(ObjectIds.parse(id), pageable);
    }

    /** Phase 1.2 - two explicit queries. */
    @GetMapping("/manual")
    public MovieWithComments manual(@PathVariable String id,
                                    @RequestParam(defaultValue = "10") int limit) {
        return commentService.manual(ObjectIds.parse(id), limit);
    }

    /** Phase 1.3 - resolved through {@code @DocumentReference}. */
    @GetMapping("/via-reference")
    public MovieWithComments viaReference(@PathVariable String id,
                                          @RequestParam(defaultValue = "10") int limit) {
        return commentService.viaReference(ObjectIds.parse(id), limit);
    }

    /** Phase 1.3 - the eager inverse; watch the query count in the log. */
    @GetMapping("/via-eager-reference")
    public List<CommentView> viaEagerReference(@PathVariable String id,
                                               @RequestParam(defaultValue = "10") int limit) {
        return commentService.viaEagerReference(ObjectIds.parse(id), limit);
    }

    /** Phase 1.4 - one round trip. */
    @GetMapping("/via-lookup")
    public MovieWithComments viaLookup(@PathVariable String id,
                                       @RequestParam(defaultValue = "10") int limit) {
        return commentService.viaLookup(ObjectIds.parse(id), limit);
    }

    /** Phase 1.5 - insert plus the bounded embed maintenance. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommentView add(@PathVariable String id, @RequestBody NewComment body) {
        return commentService.addComment(ObjectIds.parse(id), body.name(), body.email(), body.text());
    }

    public record NewComment(String name, String email, String text) {
    }
}
