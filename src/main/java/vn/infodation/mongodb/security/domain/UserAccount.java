package vn.infodation.mongodb.security.domain;

import java.time.Instant;
import java.util.List;

import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import vn.infodation.mongodb.security.Roles;

/**
 * Phase 1.1 - mapped straight onto {@code sample_mflix.users}, which already holds a few hundred
 * documents shaped {@code {_id, name, email, password}} with real BCrypt hashes.
 * <p>
 * {@code roles}, {@code enabled} and {@code createdAt} are <em>not</em> in the sample documents.
 * Spring Data leaves them null on read rather than failing, which is why every accessor below
 * has a defined answer for absent - a null {@code roles} has to mean "an ordinary user", not an
 * account with no authorities at all, and a null {@code enabled} has to mean usable, or the
 * entire sample dataset would be locked out.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "users")
public class UserAccount {

    @Id
    private ObjectId id;

    private String name;
    private String email;

    /** BCrypt. Bare {@code $2b$...} for the sample rows, {@code {bcrypt}$2a$...} for ours. */
    private String password;

    private List<String> roles;
    private Boolean enabled;
    private Instant createdAt;

    public List<String> rolesOrDefault() {
        return roles == null || roles.isEmpty() ? Roles.DEFAULT : roles;
    }

    public boolean isUsable() {
        return enabled == null || enabled;
    }
}
