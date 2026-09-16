package vn.infodation.mongodb.mflix.domain;

import java.time.Instant;

import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Phase 1.2 - the referenced child, resolved by hand.
 * <p>
 * {@code movieId} is a plain {@link ObjectId} foreign key, and {@code name}/{@code email} are
 * denormalised from the user so rendering a comment list needs no second collection. That
 * duplication is the trade: a user renaming themselves leaves stale names behind, which is
 * usually fine for an authored comment and never fine for, say, a permission flag.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "comments")
public class Comment {

    @Id
    private ObjectId id;

    private String name;
    private String email;

    @Field("movie_id")
    private ObjectId movieId;

    private String text;
    private Instant date;
}
