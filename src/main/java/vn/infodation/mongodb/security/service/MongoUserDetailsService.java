package vn.infodation.mongodb.security.service;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import vn.infodation.mongodb.security.domain.UserAccount;
import vn.infodation.mongodb.security.repository.UserAccountRepository;

/**
 * Phase 1.4 - {@code sample_mflix.users} as the identity store.
 * <p>
 * The email is the username, because that is the only unique human-readable field the sample
 * documents have. {@code .roles(...)} adds the {@code ROLE_} prefix, matching what the JWT
 * converter does on the other side of a login - if these two ever disagree, every
 * {@code hasRole()} check passes at login and fails on the next request, which is a miserable
 * afternoon.
 */
@Service
@RequiredArgsConstructor
public class MongoUserDetailsService implements UserDetailsService {

    private final UserAccountRepository users;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        UserAccount account = users.findByEmail(email)
                // Never return null. DaoAuthenticationProvider treats null as a bug and throws
                // an InternalAuthenticationServiceException, which surfaces as a 500 instead of
                // the 401 a wrong email should produce.
                .orElseThrow(() -> new UsernameNotFoundException("no user for " + email));

        return User.withUsername(account.getEmail())
                .password(account.getPassword())
                .disabled(!account.isUsable())
                .roles(account.rolesOrDefault().toArray(String[]::new))
                .build();
    }
}
