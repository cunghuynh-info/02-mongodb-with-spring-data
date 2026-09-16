package vn.infodation.mongodb.mflix.dto;

import java.util.List;

/**
 * The payload the three Phase 1 strategies all have to produce - manual resolution,
 * {@code @DocumentReference} and {@code $lookup}. {@code strategy} and {@code queryCount} are
 * there so the difference shows up in the response rather than only in the logs.
 */
public record MovieWithComments(
        String strategy,
        int queryCount,
        MovieDetail movie,
        List<CommentView> comments) {
}
