package vn.infodation.mongodb.security.service;

import java.time.Instant;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import vn.infodation.mongodb.security.LabSecurityProperties;
import vn.infodation.mongodb.security.Roles;
import vn.infodation.mongodb.security.domain.UserAccount;

/**
 * Phase 1.6 - three accounts whose passwords are known.
 * <p>
 * {@code sample_mflix.users} ships real BCrypt hashes of passwords nobody has, so without this
 * there is no way to log in at all: the identity store is genuine but unusable. These three are
 * upserted by email and never delete anything, so the sample users stay exactly as they were.
 * <p>
 * Disable with {@code lab.security.seed.enabled=false}. It is on in {@code application.yaml}
 * because this repository is a local lab whose Mongo credentials are {@code root:example}; the
 * property defaults to <em>off</em> in code so that a deployment which does not ship that file
 * gets nothing.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "lab.security.seed.enabled", havingValue = "true")
@RequiredArgsConstructor
public class LabUserSeeder implements ApplicationRunner {

    public static final String ADMIN_EMAIL = "admin@lab.local";
    public static final String ANALYST_EMAIL = "analyst@lab.local";
    public static final String USER_EMAIL = "user@lab.local";

    private final MongoTemplate mongoTemplate;
    private final PasswordEncoder passwordEncoder;
    private final LabSecurityProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /** Separated from {@link #run} so tests can reseed without restarting the context. */
    public void seed() {
        String password = properties.getSeed().getPassword();

        upsert(ADMIN_EMAIL, "Lab Admin", password, List.of(Roles.ADMIN));
        upsert(ANALYST_EMAIL, "Lab Analyst", password, List.of(Roles.ANALYST));
        upsert(USER_EMAIL, "Lab User", password, List.of(Roles.USER));

        log.warn("seeded lab accounts {} / {} / {} - all with the password from "
                        + "lab.security.seed.password. Turn lab.security.seed.enabled off "
                        + "anywhere this would matter.",
                ADMIN_EMAIL, ANALYST_EMAIL, USER_EMAIL);
    }

    private void upsert(String email, String name, String rawPassword, List<String> roles) {
        mongoTemplate.upsert(
                Query.query(Criteria.where("email").is(email)),
                new Update()
                        .set("name", name)
                        .set("password", passwordEncoder.encode(rawPassword))
                        .set("roles", roles)
                        .set("enabled", true)
                        .setOnInsert("createdAt", Instant.now()),
                UserAccount.class);
    }
}
