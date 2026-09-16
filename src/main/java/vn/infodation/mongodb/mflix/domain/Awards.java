package vn.infodation.mongodb.mflix.domain;

import lombok.Data;

/** Embedded value object - always read with the movie, never queried on its own. */
@Data
public class Awards {
    private Integer wins;
    private Integer nominations;
    private String text;
}
