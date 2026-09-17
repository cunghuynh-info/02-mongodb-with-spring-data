package vn.infodation.mongodb.security.web;

import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import vn.infodation.mongodb.common.ConflictException;
import vn.infodation.mongodb.security.Roles;
import vn.infodation.mongodb.security.domain.RefreshToken;
import vn.infodation.mongodb.security.domain.UserAccount;
import vn.infodation.mongodb.security.repository.UserAccountRepository;
import vn.infodation.mongodb.security.service.TokenService;
import vn.infodation.mongodb.security.web.dto.AuthDtos.LoginRequest;
import vn.infodation.mongodb.security.web.dto.AuthDtos.MeResponse;
import vn.infodation.mongodb.security.web.dto.AuthDtos.RefreshRequest;
import vn.infodation.mongodb.security.web.dto.AuthDtos.RegisterRequest;
import vn.infodation.mongodb.security.web.dto.AuthDtos.TokenResponse;

/** Phase 2 and Phase 5 - everything that hands out or takes back a token. */
@Tag(name = "0 - Auth", description = "Log in, register, refresh, log out. Start here: everything else needs the token this hands out.")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    /** Phase 2.4. A wrong password comes back as 401 from {@code GlobalExceptionHandler}. */
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password()));

        return issue(authentication.getName(), rolesOf(authentication), http);
    }

    /**
     * Phase 2.5 - insert first, ask questions later.
     * <p>
     * Checking {@code existsByEmail} and then inserting is two operations with a gap in the
     * middle, and two registrations for the same address can both pass the check. The unique
     * index is the only thing that can actually decide, so the duplicate-key error it raises is
     * the answer rather than an inconvenience.
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        UserAccount account = UserAccount.builder()
                .name(request.name())
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .roles(List.of(Roles.USER))
                .enabled(true)
                .createdAt(Instant.now())
                .build();

        try {
            users.insert(account);
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("an account already exists for " + request.email());
        }

        return issue(account.getEmail(), account.rolesOrDefault(), http);
    }

    /** Phase 5.3 - rotation. The presented token is dead by the time this returns. */
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
        RefreshToken rotated = tokenService.rotate(
                request.refreshToken(), http.getHeader("User-Agent"), http.getRemoteAddr());

        UserAccount account = users.findByEmail(rotated.getUserEmail())
                // Deleted between issuing and refreshing: the token is valid, the account is not.
                .orElseThrow(() -> new AccountGoneException(rotated.getUserEmail()));

        TokenService.AccessToken access =
                tokenService.mintAccessToken(account.getEmail(), account.rolesOrDefault());

        return TokenResponse.bearer(access.value(), rotated.getId(),
                access.expiresInSeconds(), account.rolesOrDefault());
    }

    /**
     * Phase 5.5 - revokes the refresh token only. The access token keeps working until it
     * expires, which is the honest cost of not looking the token up on every request.
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        tokenService.revoke(request.refreshToken());
    }

    /** Phase 2.6 - what the server thinks you are. The endpoint every later phase debugs against. */
    @GetMapping("/me")
    public MeResponse me(Authentication authentication) {
        return new MeResponse(authentication.getName(), rolesOf(authentication));
    }

    private TokenResponse issue(String email, List<String> roles, HttpServletRequest http) {
        TokenService.AccessToken access = tokenService.mintAccessToken(email, roles);
        RefreshToken refresh = tokenService.issueRefreshToken(
                email, http.getHeader("User-Agent"), http.getRemoteAddr());

        return TokenResponse.bearer(access.value(), refresh.getId(), access.expiresInSeconds(), roles);
    }

    /** Authorities carry the {@code ROLE_} prefix; the claim and the API do not. */
    private static List<String> rolesOf(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(authority -> authority.startsWith("ROLE_") ? authority.substring(5) : authority)
                .toList();
    }

    /** An {@code AuthenticationException} so it lands on the 401 handler, not the 500 path. */
    static class AccountGoneException extends AuthenticationException {
        AccountGoneException(String email) {
            super("no account for " + email);
        }
    }
}
