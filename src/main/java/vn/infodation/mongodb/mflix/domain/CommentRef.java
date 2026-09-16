package vn.infodation.mongodb.mflix.domain;

import java.time.Instant;

import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.DocumentReference;
import org.springframework.data.mongodb.core.mapping.Field;

import lombok.Data;

/**
 * Phase 1.3 - the same {@code comments} collection mapped with an <em>eager</em>
 * {@link DocumentReference} to show what it costs.
 * <p>
 * This is a separate class rather than another field on {@link Comment} because two properties
 * cannot map to the same {@code movie_id} field - Spring Data rejects the entity at startup.
 * <p>
 * Reading a page of these issues one extra query per comment (N+1). Eager to-one references are
 * only reasonable when you fetch a single document; for lists, resolve by hand or use
 * {@code $lookup}.
 */
@Data
@Document(collection = "comments")
public class CommentRef {

    @Id
    private ObjectId id;

    private String name;
    private String email;

    @Field("movie_id")
    @DocumentReference
    private Movie movie;

    private String text;
    private Instant date;
}
