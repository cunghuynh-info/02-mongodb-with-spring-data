package vn.infodation.mongodb.mflix.repository;

import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import vn.infodation.mongodb.mflix.domain.Comment;

public interface CommentRepository extends MongoRepository<Comment, ObjectId> {

    /** Phase 1.2 - the second query of the manual two-query resolution. */
    Page<Comment> findByMovieIdOrderByDateDesc(ObjectId movieId, Pageable pageable);

    long countByMovieId(ObjectId movieId);

    /**
     * Phase 4.4 - the authenticated user's own comments, filtered by the server.
     * <p>
     * {@code authentication.name} rather than {@code principal.username}: under a bearer token
     * the principal is a {@code Jwt}, which has no {@code username} property, and the expression
     * would fail at runtime on the only path that ever reaches it. {@code authentication.name}
     * is the {@code sub} claim for a JWT and {@code getUsername()} for a {@code UserDetails}, so
     * it means the same thing under both.
     * <p>
     * Needs the {@code SecurityEvaluationContextExtension} bean in {@code MethodSecurityConfig};
     * without it the SpEL cannot see {@code authentication} at all.
     */
    @Query("{ 'email' : ?#{authentication.name} }")
    Page<Comment> findMine(Pageable pageable);
}
