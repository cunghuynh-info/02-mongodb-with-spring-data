package vn.infodation.mongodb.security.web;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Phase 3.5 - 403 for "you are somebody, just not the right somebody".
 * <p>
 * The split between this and {@link RestAuthenticationEntryPoint} is not ours to make:
 * {@code ExceptionTranslationFilter} asks the {@code AuthenticationTrustResolver} whether the
 * current authentication is anonymous and routes to the entry point if it is. So an anonymous
 * call to an admin route is a 401 and a {@code USER} call to the same route is a 403, which is
 * the behaviour the tests assert.
 */
@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final JsonErrorWriter writer;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        writer.write(response, HttpStatus.FORBIDDEN, "access denied");
    }
}
