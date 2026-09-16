package vn.infodation.mongodb.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import vn.infodation.mongodb.security.domain.UserAccount;
import vn.infodation.mongodb.security.repository.UserAccountRepository;

/**
 * Phase 1.4 - the sample documents are missing two fields this application cares about, and what
 * happens then is the whole test.
 */
class MongoUserDetailsServiceTest {

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final MongoUserDetailsService service = new MongoUserDetailsService(users);

    @Test
    void aSampleUserWithNoRolesFieldIsAnOrdinaryUser() {
        // Exactly what sample_mflix.users holds: no roles, no enabled.
        when(users.findByEmail(any())).thenReturn(Optional.of(UserAccount.builder()
                .email("ada@example.com")
                .password("$2b$12$abcdefghijklmnopqrstuv")
                .build()));

        UserDetails details = service.loadUserByUsername("ada@example.com");

        assertThat(details.getUsername()).isEqualTo("ada@example.com");
        assertThat(authorities(details)).containsExactly("ROLE_USER");
        // A null `enabled` has to mean usable, or the entire sample dataset is locked out.
        assertThat(details.isEnabled()).isTrue();
    }

    @Test
    void storedRolesArePrefixedNotDuplicated() {
        when(users.findByEmail(any())).thenReturn(Optional.of(UserAccount.builder()
                .email("admin@lab.local")
                .password("{bcrypt}$2a$10$abcdefghijklmnopqrstuv")
                .roles(List.of("ADMIN", "ANALYST"))
                .enabled(true)
                .build()));

        assertThat(authorities(service.loadUserByUsername("admin@lab.local")))
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_ANALYST");
    }

    @Test
    void disabledAccountsStayDisabled() {
        when(users.findByEmail(any())).thenReturn(Optional.of(UserAccount.builder()
                .email("gone@lab.local")
                .password("{bcrypt}$2a$10$abcdefghijklmnopqrstuv")
                .enabled(false)
                .build()));

        assertThat(service.loadUserByUsername("gone@lab.local").isEnabled()).isFalse();
    }

    @Test
    void anUnknownEmailThrowsRatherThanReturningNull() {
        when(users.findByEmail(any())).thenReturn(Optional.empty());

        // Returning null here turns a wrong email into a 500 by way of
        // InternalAuthenticationServiceException, instead of the 401 it should be.
        assertThatThrownBy(() -> service.loadUserByUsername("nobody@lab.local"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    private static List<String> authorities(UserDetails details) {
        return details.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }
}
