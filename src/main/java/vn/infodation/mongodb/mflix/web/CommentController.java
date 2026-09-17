package vn.infodation.mongodb.mflix.web;

import java.util.List;

import org.bson.types.ObjectId;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import vn.infodation.mongodb.common.ObjectIds;
import vn.infodation.mongodb.common.PageResponse;
import vn.infodation.mongodb.mflix.dto.CommentView;
import vn.infodation.mongodb.mflix.dto.MovieWithComments;
import vn.infodation.mongodb.mflix.service.CommentDeletionService;
import vn.infodation.mongodb.mflix.service.CommentService;

/**
 * Phase 1 - the four read paths sit side by side on purpose. Hit each with the same movie id
 * and compare the response and the query log.
 */
@Tag(name = "1 - Comments", description = "The same comment list reached four ways - compare the round trips. Writing needs a token.")
@RestController
@RequestMapping("/api/movies/{id}/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;
    private final CommentDeletionService commentDeletionService;

    /** Phase 1.2 - referenced and paged. */
    @GetMapping
    public PageResponse<CommentView> list(
            @PathVariable String id,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
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

    /**
     * Phase 1.5 - insert plus the bounded embed maintenance.
     * <p>
     * Phase 4.2 took {@code name} and {@code email} out of the request body. They were the
     * author's identity, supplied by whoever was posting, which meant anyone could sign a
     * comment with anyone else's address - and Phase 4.3 then hangs deletion rights off exactly
     * that field. The denormalised copy on the document is unchanged; it is just no longer the
     * client's to choose.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommentView add(@PathVariable String id,
                           @Valid @RequestBody NewComment body,
                           Authentication authentication) {
        return commentService.addComment(ObjectIds.parse(id),
                displayName(authentication), authentication.getName(), body.text());
    }

    /**
     * Phase 4.3 - three implementations of one rule, chosen by {@code ?strategy=}.
     * <p>
     * Only in a lab would this be a query parameter. It is here so all three can be run against
     * the same data from {@code http/requests.http} and compared; production picks one.
     */
    @DeleteMapping("/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id,
                       @PathVariable String commentId,
                       @RequestParam(defaultValue = "query") String strategy,
                       Authentication authentication) {
        ObjectId comment = ObjectIds.parse(commentId);

        switch (strategy) {
            case "query" -> commentDeletionService.deleteOwned(comment, authentication.getName());
            case "post-authorize" -> commentDeletionService.deleteAfterPostAuthorizeCheck(comment);
            case "bean" -> commentDeletionService.deleteAfterPreAuthorizeCheck(comment);
            default -> throw new IllegalArgumentException(
                    "strategy must be one of query, post-authorize, bean");
        }
    }

    /**
     * The JWT carries no display name, so the {@code sub} claim stands in. Reading the user
     * document for it would be a second query on every comment; the denormalised name is a
     * convenience, not a source of truth.
     */
    private static String displayName(Authentication authentication) {
        return authentication.getName();
    }

    public record NewComment(@NotBlank @Size(max = 4000) String text) {
    }
}
