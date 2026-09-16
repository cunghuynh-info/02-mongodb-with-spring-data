package vn.infodation.mongodb.mflix.repository;

import java.util.List;

import org.bson.types.ObjectId;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import vn.infodation.mongodb.mflix.domain.CommentRef;

/**
 * Phase 1.3 - same collection as {@link CommentRepository}, but the mapped type resolves the
 * movie eagerly. The explicit {@code @Query} is needed because the property is {@code movie},
 * not {@code movieId}, and matching on a reference by id is not something the derived-query
 * parser expresses.
 */
public interface CommentRefRepository extends MongoRepository<CommentRef, ObjectId> {

    @Query("{ 'movie_id' : ?0 }")
    List<CommentRef> findForMovie(ObjectId movieId, Pageable pageable);
}
