package vn.infodation.mongodb.mflix.dto;

/** Phase 2.7 output. */
public record MostCommentedMovie(String id, String title, Integer year, long commentCount) {
}
