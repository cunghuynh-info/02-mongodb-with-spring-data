package vn.infodation.mongodb.mflix.domain;

import java.time.Instant;
import java.util.List;

import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.mongodb.core.mapping.DocumentReference;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * Phase 1.1 - {@code sample_mflix.movies}.
 * <p>
 * Everything that is read together with the movie and is bounded in size is embedded:
 * {@link Imdb}, {@link Awards}, {@link Tomatoes} and the small string arrays. Comments are not:
 * a popular movie has thousands, they are written independently of the movie, and they would
 * push the document toward the 16 MB ceiling - so they live in their own collection.
 */
@Data
@Document(collection = "movies")
public class Movie {

    @Id
    private ObjectId id;

    private String title;
    private Integer year;
    private String rated;
    private String plot;
    private String fullplot;
    private Integer runtime;
    private String poster;

    private List<String> genres;
    private List<String> cast;
    private List<String> directors;
    private List<String> countries;
    private List<String> languages;

    private Instant released;

    private Imdb imdb;
    private Awards awards;
    private Tomatoes tomatoes;

    @Field("num_mflix_comments")
    private Integer numMflixComments;

    /**
     * Phase 1.5 - the extended reference pattern: the newest few comments are duplicated onto
     * the movie so the detail screen needs one read, while the full list stays in
     * {@code comments}. Maintained with {@code $push} + {@code $slice}, which costs an extra
     * write per comment.
     */
    @Field("recent_comments")
    private List<EmbeddedComment> recentComments;

    /**
     * Phase 1.3 - an inverse reference. The movie document stores nothing for this field
     * ({@link ReadOnlyProperty}); the lookup runs {@code {movie_id: <this._id>}} against the
     * comments collection. {@code lazy = true} means the query only fires on first access -
     * which is also why this field is excluded from {@code toString}/{@code equals}, and why
     * entities are never serialised straight to JSON.
     */
    @ReadOnlyProperty
    @DocumentReference(lookup = "{ 'movie_id' : ?#{#self._id} }", sort = "{ 'date' : -1 }", lazy = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<Comment> comments;
}
