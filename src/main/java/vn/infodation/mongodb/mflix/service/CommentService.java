package vn.infodation.mongodb.mflix.service;

import java.time.Instant;
import java.util.List;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import vn.infodation.mongodb.common.NotFoundException;
import vn.infodation.mongodb.common.PageResponse;
import vn.infodation.mongodb.common.RawStage;
import vn.infodation.mongodb.mflix.domain.Comment;
import vn.infodation.mongodb.mflix.domain.CommentRef;
import vn.infodation.mongodb.mflix.domain.EmbeddedComment;
import vn.infodation.mongodb.mflix.domain.Movie;
import vn.infodation.mongodb.mflix.dto.CommentView;
import vn.infodation.mongodb.mflix.dto.MovieDetail;
import vn.infodation.mongodb.mflix.dto.MovieWithComments;
import vn.infodation.mongodb.mflix.repository.CommentRefRepository;
import vn.infodation.mongodb.mflix.repository.CommentRepository;
import vn.infodation.mongodb.mflix.repository.MovieRepository;

/**
 * Phase 1 - the same movie-with-comments payload reached three ways, plus the write path that
 * keeps the bounded embed in sync.
 */
@Service
@RequiredArgsConstructor
public class CommentService {

    /** How many comments the extended reference on the movie keeps. */
    public static final int RECENT_COMMENTS_KEPT = 5;

    private final MongoTemplate mongoTemplate;
    private final MovieRepository movieRepository;
    private final CommentRepository commentRepository;
    private final CommentRefRepository commentRefRepository;

    /** Phase 1.2 - referenced, resolved by hand. Two queries, both indexable, both paged. */
    public PageResponse<CommentView> commentsFor(ObjectId movieId, Pageable pageable) {
        requireMovie(movieId);
        Page<Comment> page = commentRepository.findByMovieIdOrderByDateDesc(movieId, pageable);
        return PageResponse.of(page).map(CommentView::from);
    }

    /** Phase 1.2 - the full payload, assembled from two explicit round trips. */
    public MovieWithComments manual(ObjectId movieId, int limit) {
        Movie movie = requireMovie(movieId);
        Page<Comment> comments = commentRepository.findByMovieIdOrderByDateDesc(
                movieId, PageRequest.of(0, limit));
        return new MovieWithComments("manual", 2, MovieDetail.from(movie),
                comments.getContent().stream().map(CommentView::from).toList());
    }

    /**
     * Phase 1.3 - {@code @DocumentReference}. Reads the same as a field access, but the getter
     * fires the second query, and there is no way to page or limit it: the whole comment list
     * for the movie comes back. Convenient, and easy to be surprised by.
     */
    public MovieWithComments viaReference(ObjectId movieId, int limit) {
        Movie movie = requireMovie(movieId);
        List<Comment> resolved = movie.getComments() == null ? List.of() : movie.getComments();
        return new MovieWithComments("documentReference", 2, MovieDetail.from(movie),
                resolved.stream().limit(limit).map(CommentView::from).toList());
    }

    /**
     * Phase 1.3 - the other direction, eager. Each {@link CommentRef} pulls its movie back
     * individually, so a page of {@code n} comments costs {@code n + 1} queries.
     */
    public List<CommentView> viaEagerReference(ObjectId movieId, int limit) {
        requireMovie(movieId);
        return commentRefRepository
                .findForMovie(movieId, PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "date")))
                .stream()
                .map(CommentView::from)
                .toList();
    }

    /**
     * Phase 1.4 - one round trip. The sub-pipeline sorts and limits on the server, which the
     * reference-based versions cannot do.
     */
    public MovieWithComments viaLookup(ObjectId movieId, int limit) {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("_id").is(movieId)),
                RawStage.of("""
                        { "$lookup": {
                            "from": "comments",
                            "localField": "_id",
                            "foreignField": "movie_id",
                            "pipeline": [ { "$sort": { "date": -1 } }, { "$limit": %d } ],
                            "as": "joinedComments"
                        } }""".formatted(limit)));

        AggregationResults<Document> results =
                mongoTemplate.aggregate(aggregation, "movies", Document.class);
        Document document = results.getUniqueMappedResult();
        if (document == null) {
            throw new NotFoundException("movie", movieId);
        }

        List<Document> joined = document.getList("joinedComments", Document.class, List.of());
        Movie movie = mongoTemplate.getConverter().read(Movie.class, document);

        return new MovieWithComments("lookup", 1, MovieDetail.from(movie),
                joined.stream()
                        .map(c -> mongoTemplate.getConverter().read(Comment.class, c))
                        .map(CommentView::from)
                        .toList());
    }

    /**
     * Phase 1.5 - write path. Three effects in two round trips: insert the comment, then a
     * single update that pushes onto the capped embed and bumps the counter.
     * <p>
     * {@code $slice: -RECENT_COMMENTS_KEPT} keeps the newest few and drops the rest, so the
     * array cannot grow without bound - which is the only thing that makes embedding safe here.
     */
    public CommentView addComment(ObjectId movieId, String name, String email, String text) {
        requireMovie(movieId);

        Comment comment = Comment.builder()
                .movieId(movieId)
                .name(name)
                .email(email)
                .text(text)
                .date(Instant.now())
                .build();
        commentRepository.insert(comment);

        mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(movieId)),
                new Update()
                        // $slice only applies to a $push that uses $each, hence each() rather
                        // than value(). Property names, not field names - the entity class is
                        // passed below, so the mapper translates them.
                        .push("recentComments")
                        .slice(-RECENT_COMMENTS_KEPT)
                        .each(EmbeddedComment.of(comment))
                        .inc("numMflixComments", 1),
                Movie.class);

        return CommentView.from(comment);
    }

    private Movie requireMovie(ObjectId movieId) {
        return movieRepository.findById(movieId)
                .orElseThrow(() -> new NotFoundException("movie", movieId));
    }
}
