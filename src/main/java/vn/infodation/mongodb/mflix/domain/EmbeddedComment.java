package vn.infodation.mongodb.mflix.domain;

import java.time.Instant;

import org.bson.types.ObjectId;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Phase 1.5 - the slice of a comment that is worth duplicating onto the movie. Deliberately
 * narrower than {@link Comment}: an extended reference should carry only what the screen needs,
 * and it keeps {@code commentId} so the full document is still reachable.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmbeddedComment {

    private ObjectId commentId;
    private String name;
    private String text;
    private Instant date;

    public static EmbeddedComment of(Comment comment) {
        return new EmbeddedComment(comment.getId(), comment.getName(), comment.getText(), comment.getDate());
    }
}
