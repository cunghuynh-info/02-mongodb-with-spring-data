package vn.infodation.mongodb.security.service;

import java.util.Optional;

import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Phase 7.1 - who Spring Data should write into {@code @CreatedBy}.
 * <p>
 * Anonymous has to map to empty rather than to the string "anonymousUser": an audit column whose
 * value is a framework placeholder reads as though somebody called {@code anonymousUser} did it,
 * and the honest answer is that nobody did.
 */
@Component
public class SecurityAuditorAware implements AuditorAware<String> {

    @Override
    public Optional<String> getCurrentAuditor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        return Optional.ofNullable(authentication.getName());
    }
}
