package vn.infodation.mongodb.mflix.dto;

import java.time.Instant;

import vn.infodation.mongodb.common.ObjectIds;
import vn.infodation.mongodb.mflix.domain.Comment;
import vn.infodation.mongodb.mflix.domain.CommentRef;
import vn.infodation.mongodb.mflix.domain.EmbeddedComment;

public record CommentView(String id, String name, String email, String text, Instant date) {

    public static CommentView from(Comment comment) {
        return new CommentView(ObjectIds.hex(comment.getId()), comment.getName(), comment.getEmail(),
                comment.getText(), comment.getDate());
    }

    public static CommentView from(CommentRef comment) {
        return new CommentView(ObjectIds.hex(comment.getId()), comment.getName(), comment.getEmail(),
                comment.getText(), comment.getDate());
    }

    public static CommentView from(EmbeddedComment comment) {
        return new CommentView(ObjectIds.hex(comment.getCommentId()), comment.getName(), null,
                comment.getText(), comment.getDate());
    }
}
