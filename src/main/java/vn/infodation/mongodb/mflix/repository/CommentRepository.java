package vn.infodation.mongodb.mflix.repository;

import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import vn.infodation.mongodb.mflix.domain.Comment;

public interface CommentRepository extends MongoRepository<Comment, ObjectId> {

    /** Phase 1.2 - the second query of the manual two-query resolution. */
    Page<Comment> findByMovieIdOrderByDateDesc(ObjectId movieId, Pageable pageable);

    long countByMovieId(ObjectId movieId);
}
