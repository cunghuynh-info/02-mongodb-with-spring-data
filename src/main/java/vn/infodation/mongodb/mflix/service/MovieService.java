package vn.infodation.mongodb.mflix.service;

import java.util.List;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import vn.infodation.mongodb.common.ExplainSummary;
import vn.infodation.mongodb.common.NotFoundException;
import vn.infodation.mongodb.common.PageResponse;
import vn.infodation.mongodb.mflix.domain.Movie;
import vn.infodation.mongodb.mflix.dto.MovieDetail;
import vn.infodation.mongodb.mflix.dto.MovieSearchCriteria;
import vn.infodation.mongodb.mflix.dto.MovieSummary;
import vn.infodation.mongodb.mflix.repository.MovieRepository;

@Service
@RequiredArgsConstructor
public class MovieService {

    private final MovieRepository movieRepository;

    public Movie require(ObjectId id) {
        return movieRepository.findById(id).orElseThrow(() -> new NotFoundException("movie", id));
    }

    /** Phase 1.1 - the embedded document, one read, no joins. */
    public MovieDetail detail(ObjectId id) {
        return MovieDetail.from(require(id));
    }

    /** Phase 2.2 */
    public PageResponse<MovieDetail> search(MovieSearchCriteria filter, Pageable pageable) {
        Page<Movie> page = movieRepository.search(filter, pageable);
        return PageResponse.of(page).map(MovieDetail::from);
    }

    /** Phase 2.3 */
    public List<MovieSummary> summaries(MovieSearchCriteria filter, Pageable pageable) {
        return movieRepository.searchSummaries(filter, pageable);
    }

    /** Phase 2.4 */
    public MovieDetail addVotes(ObjectId id, long delta) {
        return movieRepository.addVotes(id, delta)
                .map(MovieDetail::from)
                .orElseThrow(() -> new NotFoundException("movie", id));
    }

    public MovieDetail addGenre(ObjectId id, String genre) {
        return movieRepository.addGenre(id, genre)
                .map(MovieDetail::from)
                .orElseThrow(() -> new NotFoundException("movie", id));
    }

    public MovieDetail removeGenre(ObjectId id, String genre) {
        return movieRepository.removeGenre(id, genre)
                .map(MovieDetail::from)
                .orElseThrow(() -> new NotFoundException("movie", id));
    }

    /** Phase 2.9 - the raw explain, and the four numbers that matter. */
    public ExplainSummary explain(MovieSearchCriteria filter, Pageable pageable) {
        Document explain = movieRepository.explainSearch(filter, pageable);
        return ExplainSummary.of(explain);
    }

    public Document explainRaw(MovieSearchCriteria filter, Pageable pageable) {
        return movieRepository.explainSearch(filter, pageable);
    }
}
