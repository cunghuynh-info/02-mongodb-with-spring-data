package vn.infodation.mongodb.mflix.service;

import java.util.Objects;

import org.bson.types.ObjectId;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import vn.infodation.mongodb.common.NotFoundException;
import vn.infodation.mongodb.mflix.domain.Comment;
import vn.infodation.mongodb.mflix.repository.CommentRepository;

/**
 * Phase 4.3 - the ownership check, for the two strategies that do it as a check.
 * <p>
 * This is a separate bean for a reason that is easy to learn the hard way: method security is
 * applied by a proxy, so an annotated method called from <em>inside</em> the same bean bypasses
 * the proxy entirely and the annotation does nothing at all. No warning, no log line - the
 * authorization simply is not there. Keeping the annotated methods on a bean that only ever gets
 * called from another bean makes that impossible to get wrong.
 */
@Component("commentSecurity")
@RequiredArgsConstructor
public class CommentSecurity {

    private final CommentRepository comments;

    /**
     * Strategy 2's predicate, reached from SpEL as {@code @commentSecurity.canDelete(...)}.
     * <p>
     * An unknown comment returns false, so a caller probing for ids gets 403 for every id
     * whether or not it exists. Answering 404 for the ones that are missing would turn this into
     * an oracle for which comments exist.
     */
    public boolean canDelete(ObjectId commentId, Authentication authentication) {
        if (commentId == null || authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return comments.findById(commentId)
                .map(comment -> Objects.equals(comment.getEmail(), authentication.getName()))
                .orElse(false);
    }

    /**
     * Strategy 1's read. The document is fetched, returned, and only then judged - which is why
     * {@code returnObject} exists at all. It costs a round trip, and the decision happens after
     * the data has already been read into this JVM.
     */
    @PostAuthorize("returnObject.email == authentication.name")
    public Comment loadForDeletion(ObjectId commentId) {
        return comments.findById(commentId)
                .orElseThrow(() -> new NotFoundException("comment", commentId));
    }
}
