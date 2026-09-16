package vn.infodation.mongodb.common;

import org.bson.types.ObjectId;

public final class ObjectIds {

    private ObjectIds() {
    }

    /** Turns a path variable into an {@link ObjectId}, or a 400 rather than a 500. */
    public static ObjectId parse(String value) {
        if (!ObjectId.isValid(value)) {
            throw new IllegalArgumentException("'%s' is not a valid ObjectId".formatted(value));
        }
        return new ObjectId(value);
    }

    public static String hex(ObjectId id) {
        return id == null ? null : id.toHexString();
    }
}
