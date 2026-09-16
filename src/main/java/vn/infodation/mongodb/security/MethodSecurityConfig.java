package vn.infodation.mongodb.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Phase 4.1 - {@code @PreAuthorize}, {@code @PostAuthorize} and {@code @PostFilter}.
 * <p>
 * There is deliberately no {@code SecurityEvaluationContextExtension} bean here, although
 * {@code @Query("{ 'email' : ?#{authentication.name} }")} in {@code CommentRepository} needs one.
 * Boot 4 registers it already, from {@code SecurityAutoConfiguration.SecurityDataConfiguration},
 * as soon as {@code spring-security-data} is on the classpath. Declaring it as well is not a
 * harmless duplicate: bean-definition overriding is off by default, so the context fails to
 * start with a {@code BeanDefinitionOverrideException} naming both definitions. The dependency
 * is the whole of the wiring.
 * <p>
 * The SpEL itself still needs care: use {@code authentication.name}, not
 * {@code principal.username}. Under a bearer token the principal is a {@code Jwt}, which has no
 * {@code username} - and that fails at runtime, on the authenticated path only, the first time
 * somebody actually calls the query.
 */
@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {
}
