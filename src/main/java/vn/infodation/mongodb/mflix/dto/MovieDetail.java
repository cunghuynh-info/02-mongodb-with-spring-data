package vn.infodation.mongodb.mflix.dto;

import java.time.Instant;
import java.util.List;

import vn.infodation.mongodb.common.ObjectIds;
import vn.infodation.mongodb.mflix.domain.Movie;

/**
 * Entities are never serialised directly: {@code Movie.comments} is a lazy reference, and
 * touching it from Jackson would fire a query per response without anybody asking for it.
 */
public record MovieDetail(
        String id,
        String title,
        Integer year,
        String rated,
        String plot,
        Integer runtime,
        List<String> genres,
        List<String> cast,
        List<String> directors,
        Instant released,
        Double imdbRating,
        Long imdbVotes,
        Integer awardWins,
        Integer commentCount,
        List<CommentView> recentComments) {

    public static MovieDetail from(Movie movie) {
        return new MovieDetail(
                ObjectIds.hex(movie.getId()),
                movie.getTitle(),
                movie.getYear(),
                movie.getRated(),
                movie.getPlot(),
                movie.getRuntime(),
                movie.getGenres(),
                movie.getCast(),
                movie.getDirectors(),
                movie.getReleased(),
                movie.getImdb() == null ? null : movie.getImdb().getRating(),
                movie.getImdb() == null ? null : movie.getImdb().getVotes(),
                movie.getAwards() == null ? null : movie.getAwards().getWins(),
                movie.getNumMflixComments(),
                movie.getRecentComments() == null ? List.of()
                        : movie.getRecentComments().stream().map(CommentView::from).toList());
    }
}
