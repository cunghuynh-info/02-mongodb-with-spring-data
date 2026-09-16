package vn.infodation.mongodb.mflix.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PostFilter;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import vn.infodation.mongodb.common.PageResponse;
import vn.infodation.mongodb.mflix.domain.Comment;
import vn.infodation.mongodb.mflix.dto.CommentView;
import vn.infodation.mongodb.mflix.repository.CommentRepository;

/**
 * Phase 4.5 - "my comments", filtered in the database and filtered in the JVM, side by side.
 * <p>
 * Hit both with the same {@code size} and compare {@code items.length}. They will not agree, and
 * the reason is worth internalising: {@code @PostFilter} runs after the query has already been
 * paged, so it removes rows from a page the server considered full. Ask for 20 and you get
 * however many of that particular 20 happened to be yours - the page size becomes a maximum, the
 * total count is a lie, and page 2 re-reads rows page 1 already discarded.
 */
@Service
@RequiredArgsConstructor
public class MyCommentsService {

    private final CommentRepository comments;

    /** The predicate goes to the server, so a page of 20 is 20 of the caller's comments. */
    public PageResponse<CommentView> mine(Pageable pageable) {
        Page<Comment> page = comments.findMine(pageable);
        return PageResponse.of(page).map(CommentView::from);
    }

    /**
     * The same intent, applied too late.
     * <p>
     * Note the {@link ArrayList}: {@code @PostFilter} removes elements through the collection's
     * own iterator, so returning {@code Stream.toList()} or {@code List.of(...)} throws
     * {@code UnsupportedOperationException} from inside the framework - an immutable list is not
     * a filterable one.
     */
    @PostFilter("filterObject.email == authentication.name")
    public List<CommentView> minePostFiltered(Pageable pageable) {
        return comments.findAll(pageable).getContent().stream()
                .map(CommentView::from)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }
}
