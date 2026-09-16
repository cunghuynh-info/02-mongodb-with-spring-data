package vn.infodation.mongodb.mflix.repository;

import java.util.List;
import java.util.Optional;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import vn.infodation.mongodb.mflix.domain.Movie;
import vn.infodation.mongodb.mflix.dto.MovieSearchCriteria;
import vn.infodation.mongodb.mflix.dto.MovieSummary;

/** Phase 2.1 - the fragment where {@code MongoTemplate} does what derived queries cannot. */
public interface MovieRepositoryCustom {

    Page<Movie> search(MovieSearchCriteria filter, Pageable pageable);

    /** Phase 2.3 - same filter, interface projection instead of the whole document. */
    List<MovieSummary> searchSummaries(MovieSearchCriteria filter, Pageable pageable);

    /** Phase 2.4 - {@code findAndModify} with {@code $inc}, returning the updated document. */
    Optional<Movie> addVotes(ObjectId movieId, long delta);

    /** Phase 2.4 - {@code $addToSet}: adding a genre twice is a no-op. */
    Optional<Movie> addGenre(ObjectId movieId, String genre);

    /** Phase 2.4 - {@code $pull}. */
    Optional<Movie> removeGenre(ObjectId movieId, String genre);

    /** Phase 2.9 / Phase 3 - {@code explain} with executionStats for the search query. */
    Document explainSearch(MovieSearchCriteria filter, Pageable pageable);
}
