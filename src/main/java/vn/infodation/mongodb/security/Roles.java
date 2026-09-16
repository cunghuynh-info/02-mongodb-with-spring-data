package vn.infodation.mongodb.security;

import java.util.List;

/**
 * The three roles, unprefixed.
 * <p>
 * Stored on the user document and carried in the {@code roles} claim exactly as written here.
 * The {@code ROLE_} prefix is Spring Security's convention for {@code hasRole()} and is added by
 * {@code JwtGrantedAuthoritiesConverter} on the way in - putting it in the database instead
 * would leak a framework detail into the data and break every {@code hasRole('ADMIN')} check
 * the first time someone wrote it without the prefix.
 */
public final class Roles {

    public static final String ADMIN = "ADMIN";
    public static final String ANALYST = "ANALYST";
    public static final String USER = "USER";

    /** What a user document with no {@code roles} field gets - every sample user, that is. */
    public static final List<String> DEFAULT = List.of(USER);

    private Roles() {
    }
}
