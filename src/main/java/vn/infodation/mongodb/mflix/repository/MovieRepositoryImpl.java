package vn.infodation.mongodb.mflix.repository;

import java.util.List;
import java.util.Optional;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.support.PageableExecutionUtils;

import com.mongodb.ExplainVerbosity;
import com.mongodb.client.FindIterable;

import lombok.RequiredArgsConstructor;
import vn.infodation.mongodb.mflix.domain.Movie;
import vn.infodation.mongodb.mflix.dto.MovieSearchCriteria;
import vn.infodation.mongodb.mflix.dto.MovieSummary;

import static org.springframework.data.mongodb.core.FindAndModifyOptions.options;

@RequiredArgsConstructor
public class MovieRepositoryImpl implements MovieRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    @Override
    public Page<Movie> search(MovieSearchCriteria filter, Pageable pageable) {
        Query query = new Query(MovieCriteriaBuilder.build(filter)).with(pageable);
        List<Movie> movies = mongoTemplate.find(query, Movie.class);
        // PageableExecutionUtils skips the count query when the result obviously fits on one
        // page - one fewer round trip on the common case.
        return PageableExecutionUtils.getPage(movies, pageable,
                () -> mongoTemplate.count(Query.of(query).limit(-1).skip(-1), Movie.class));
    }

    @Override
    public List<MovieSummary> searchSummaries(MovieSearchCriteria filter, Pageable pageable) {
        return mongoTemplate.query(Movie.class)
                .as(MovieSummary.class)
                .matching(new Query(MovieCriteriaBuilder.build(filter)).with(pageable))
                .all();
    }

    @Override
    public Optional<Movie> addVotes(ObjectId movieId, long delta) {
        return findAndModify(movieId, new Update().inc("imdb.votes", delta));
    }

    @Override
    public Optional<Movie> addGenre(ObjectId movieId, String genre) {
        return findAndModify(movieId, new Update().addToSet("genres", genre));
    }

    @Override
    public Optional<Movie> removeGenre(ObjectId movieId, String genre) {
        return findAndModify(movieId, new Update().pull("genres", genre));
    }

    private Optional<Movie> findAndModify(ObjectId movieId, Update update) {
        Movie updated = mongoTemplate.findAndModify(
                Query.query(Criteria.where("_id").is(movieId)),
                update,
                options().returnNew(true),
                Movie.class);
        return Optional.ofNullable(updated);
    }

    @Override
    public Document explainSearch(MovieSearchCriteria filter, Pageable pageable) {
        Query query = new Query(MovieCriteriaBuilder.build(filter)).with(pageable);
        Bson sort = query.getSortObject();

        FindIterable<Document> find = mongoTemplate.getCollection(
                        mongoTemplate.getCollectionName(Movie.class))
                .find(query.getQueryObject())
                .sort(sort)
                .skip((int) Math.max(0, pageable.getOffset()))
                .limit(pageable.getPageSize());

        return find.explain(ExplainVerbosity.EXECUTION_STATS);
    }
}
