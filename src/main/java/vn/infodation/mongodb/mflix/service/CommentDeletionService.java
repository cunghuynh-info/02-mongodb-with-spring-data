package vn.infodation.mongodb.mflix.service;

import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import vn.infodation.mongodb.common.NotFoundException;
import vn.infodation.mongodb.mflix.domain.Comment;
import vn.infodation.mongodb.mflix.repository.CommentRepository;

/**
 * Phase 4.3 - the same authorization rule, expressed three ways.
 * <p>
 * All three are kept and all three are reachable, the way Phase 1 keeps four routes to the same
 * comment list. They are not equivalent, and the differences are the lesson:
 * <table>
 *   <tr><th>strategy</th><th>round trips</th><th>atomic</th><th>tells you the comment exists</th></tr>
 *   <tr><td>post-authorize</td><td>2 (+1 delete)</td><td>no</td><td>yes, by status code</td></tr>
 *   <tr><td>bean</td><td>2 (+1 delete)</td><td>no</td><td>no</td></tr>
 *   <tr><td>query</td><td>1</td><td>yes</td><td>no</td></tr>
 * </table>
 * <p>
 * Each method here is a public entry point called from the controller, never from its neighbour.
 * Routing them through one private dispatcher would be tidier and would silently disable every
 * annotation on this class - see the note on {@link CommentSecurity}.
 */
@Service
@RequiredArgsConstructor
public class CommentDeletionService {

    private final MongoTemplate mongoTemplate;
    private final CommentRepository comments;
    private final CommentSecurity commentSecurity;

    /**
     * Strategy 3, and the one to reach for.
     * <p>
     * Ownership stops being a check and becomes part of the predicate, so the server deletes the
     * document only if it is yours - in one operation, with no window between deciding and
     * acting. The other two read the comment, decide, and then delete something that may have
     * changed owner in between; here that race cannot be expressed.
     * <p>
     * The cost is the diagnostics: a missing comment and someone else's comment are the same
     * answer. That is a feature here, but it does mean a genuinely confused caller gets no help.
     */
    public void deleteOwned(ObjectId commentId, String email) {
        long deleted = mongoTemplate.remove(
                Query.query(Criteria.where("_id").is(commentId).and("email").is(email)),
                Comment.class).getDeletedCount();

        if (deleted == 0) {
            throw new NotFoundException("comment", commentId);
        }
    }

    /** Strategy 1 - {@code @PostAuthorize} on the read, then a separate delete. */
    public void deleteAfterPostAuthorizeCheck(ObjectId commentId) {
        Comment owned = commentSecurity.loadForDeletion(commentId);
        comments.deleteById(owned.getId());
    }

    /** Strategy 2 - {@code @PreAuthorize} delegating to a bean, which reads the comment itself. */
    @PreAuthorize("@commentSecurity.canDelete(#commentId, authentication)")
    public void deleteAfterPreAuthorizeCheck(ObjectId commentId) {
        comments.deleteById(commentId);
    }
}
