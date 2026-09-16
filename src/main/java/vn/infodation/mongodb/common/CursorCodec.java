package vn.infodation.mongodb.common;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.bson.types.ObjectId;

/**
 * Phase 4.6 - the keyset position, encoded so clients cannot build one by hand.
 * <p>
 * Not encryption and not tamper-proof; the point is that {@code year} and {@code _id} stop
 * being part of the published API. Change the sort key later and only this class moves - a
 * client that had learned to pass {@code ?afterYear=1999} would have to be migrated.
 */
public final class CursorCodec {

    private static final String SEPARATOR = "|";

    private CursorCodec() {
    }

    public record Position(Integer year, ObjectId id) {
    }

    public static String encode(Integer year, ObjectId id) {
        String raw = (year == null ? "" : year.toString()) + SEPARATOR + id.toHexString();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Position decode(String cursor) {
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("malformed cursor");
        }

        String[] parts = raw.split("\\" + SEPARATOR, 2);
        if (parts.length != 2 || !ObjectId.isValid(parts[1])) {
            throw new IllegalArgumentException("malformed cursor");
        }
        Integer year = parts[0].isEmpty() ? null : Integer.valueOf(parts[0]);
        return new Position(year, new ObjectId(parts[1]));
    }
}
