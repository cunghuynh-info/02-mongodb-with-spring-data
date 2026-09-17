package vn.infodation.mongodb.mflix.web;

import java.util.List;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import vn.infodation.mongodb.common.PageResponse;
import vn.infodation.mongodb.mflix.dto.CommentView;
import vn.infodation.mongodb.mflix.service.MyCommentsService;

/**
 * Phase 4.4 and 4.5 - the caller's own comments, with no id in the URL.
 * <p>
 * Nothing here takes an email parameter: the only reason it works is that the query can see the
 * authentication. An endpoint that accepted {@code ?email=} would be the same feature and an
 * authorization hole.
 */
@Tag(name = "4 - My comments", description = "Filtered by the server through the authentication, versus filtered too late by @PostFilter.")
@RestController
@RequestMapping("/api/comments")
@RequiredArgsConstructor
public class MyCommentsController {

    private final MyCommentsService myComments;

    /** Phase 4.4 - filtered by the server. */
    @GetMapping("/mine")
    public PageResponse<CommentView> mine(@ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return myComments.mine(pageable);
    }

    /** Phase 4.5 - the same thing filtered after paging. Compare the counts. */
    @GetMapping("/mine/post-filtered")
    public List<CommentView> minePostFiltered(@ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return myComments.minePostFiltered(pageable);
    }
}
