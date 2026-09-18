package vn.infodation.mongodb.support;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * A miniature stand-in for {@code sample_mflix}, shaped like the real thing - including one
 * deliberately malformed document, because the real dataset has those too.
 */
public final class SampleFixtures {

    public static final ObjectId GODFATHER = new ObjectId("000000000000000000000001");
    public static final ObjectId GODFATHER_II = new ObjectId("000000000000000000000002");
    public static final ObjectId GOODFELLAS = new ObjectId("000000000000000000000003");
    public static final ObjectId TITANIC = new ObjectId("000000000000000000000004");
    public static final ObjectId MATRIX = new ObjectId("000000000000000000000005");
    public static final ObjectId MALFORMED = new ObjectId("000000000000000000000006");

    /** Comments seeded per movie: Godfather 3, Titanic 1, everything else 0. */
    public static final int GODFATHER_COMMENTS = 3;

    private static final Instant BASE = Instant.parse("2020-01-01T00:00:00Z");

    private SampleFixtures() {
    }

    public static void reset(MongoTemplate template) {
        template.remove(new Query(), "movies");
        template.remove(new Query(), "comments");

        template.insert(List.of(
                movie(GODFATHER, "The Godfather", 1972, List.of("Crime", "Drama"),
                        List.of("Marlon Brando", "Al Pacino"), 9.2, 200_000L, 3),
                movie(GODFATHER_II, "The Godfather: Part II", 1974, List.of("Crime", "Drama"),
                        List.of("Al Pacino", "Robert De Niro"), 9.0, 150_000L, 0),
                movie(GOODFELLAS, "Goodfellas", 1990, List.of("Biography", "Crime", "Drama"),
                        List.of("Robert De Niro", "Ray Liotta"), 8.7, 120_000L, 0),
                movie(TITANIC, "Titanic", 1997, List.of("Drama", "Romance"),
                        List.of("Leonardo DiCaprio", "Kate Winslet"), 7.8, 180_000L, 1),
                movie(MATRIX, "The Matrix", 1999, List.of("Action", "Sci-Fi"),
                        List.of("Keanu Reeves", "Carrie-Anne Moss"), 8.7, 170_000L, 0),
                malformed()),
                "movies");

        template.insert(List.of(
                comment(GODFATHER, "Ada Lovelace", "ada@example.com", "Still the best.", 0),
                comment(GODFATHER, "Grace Hopper", "grace@example.com", "The pacing holds up.", 1),
                comment(GODFATHER, "Alan Turing", "alan@example.com", "Cinematography!", 2),
                comment(TITANIC, "Ada Lovelace", "ada@example.com", "There was room on the door.", 3)),
                "comments");
    }

    private static Document movie(ObjectId id, String title, int year, List<String> genres,
                                  List<String> cast, double rating, long votes, int commentCount) {
        return new Document("_id", id)
                .append("title", title)
                .append("year", year)
                .append("plot", title + " plot")
                .append("genres", genres)
                .append("cast", cast)
                .append("directors", List.of("A Director"))
                .append("imdb", new Document("rating", rating).append("votes", votes).append("id", id.getTimestamp()))
                .append("awards", new Document("wins", 1).append("nominations", 2).append("text", "won things"))
                .append("num_mflix_comments", commentCount);
    }

    /**
     * Mirrors the two shapes that actually appear in {@code sample_mflix}: a year stored as a
     * string with a stray character, and an empty string where a rating should be. Without the
     * lenient readers in {@code SampleDataConversions}, reading this one throws.
     */
    private static Document malformed() {
        return new Document("_id", MALFORMED)
                .append("title", "A Badly Typed Film")
                .append("year", "2005è")
                .append("genres", List.of("Drama"))
                .append("cast", List.of("Nobody"))
                .append("imdb", new Document("rating", "").append("votes", "").append("id", 999))
                .append("num_mflix_comments", 0);
    }

    private static Document comment(ObjectId movieId, String name, String email, String text, int daysAfterBase) {
        return new Document("_id", new ObjectId())
                .append("movie_id", movieId)
                .append("name", name)
                .append("email", email)
                .append("text", text)
                .append("date", java.util.Date.from(BASE.plus(daysAfterBase, ChronoUnit.DAYS)));
    }
}
