package vn.infodation.mongodb.security.web;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Phase 3.5 - 401 for "you are nobody yet".
 * <p>
 * Reached when an anonymous caller hits a rule that needs an identity, or presents a token that
 * does not decode. The default bearer-token entry point answers with a {@code WWW-Authenticate}
 * header and an empty body; this one keeps the header off and returns the same JSON every other
 * error in the app returns, because the only client is an HTTP-file and a test suite, neither of
 * which is going to negotiate a challenge.
 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final JsonErrorWriter writer;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        writer.write(response, HttpStatus.UNAUTHORIZED, "authentication required");
    }
}
