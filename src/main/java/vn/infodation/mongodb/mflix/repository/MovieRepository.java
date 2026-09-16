package vn.infodation.mongodb.mflix.repository;

import java.util.List;

import org.bson.types.ObjectId;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import vn.infodation.mongodb.mflix.domain.Movie;

/**
 * Derived queries cover the easy half; {@link MovieRepositoryCustom} covers the rest.
 * Spring Data finds the implementation by the {@code Impl} suffix on the fragment interface.
 */
public interface MovieRepository extends MongoRepository<Movie, ObjectId>, MovieRepositoryCustom {

    List<Movie> findByGenresContainingAndYearBetween(String genre, int from, int to, Pageable pageable);

    List<Movie> findByTitleIgnoreCase(String title);
}
