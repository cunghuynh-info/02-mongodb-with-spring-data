package vn.infodation.mongodb.security.web.dto;

import java.util.List;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The request and response shapes for {@code /api/auth}, kept together because they are tiny. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {
    }

    public record RegisterRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8, max = 100) String password) {
    }

    public record RefreshRequest(
            @NotBlank String refreshToken) {
    }

    /**
     * {@code expiresIn} is the access token's lifetime in seconds, which is what an OAuth2 client
     * expects; the refresh token's much longer lifetime is deliberately not advertised.
     */
    public record TokenResponse(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresIn,
            List<String> roles) {

        public static TokenResponse bearer(String accessToken, String refreshToken,
                                           long expiresIn, List<String> roles) {
            return new TokenResponse(accessToken, refreshToken, "Bearer", expiresIn, roles);
        }
    }

    public record MeResponse(String email, List<String> roles) {
    }
}
