package vn.infodation.mongodb.common;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The one place the error body is shaped.
 * <p>
 * Pulled out of {@link GlobalExceptionHandler} in Phase 3.5: a 401 or a 403 raised inside the
 * security filter chain never reaches {@code @RestControllerAdvice}, because the advice only
 * sees exceptions thrown by the {@code DispatcherServlet} - and the filter chain runs before
 * it. Those two statuses are written by an {@code AuthenticationEntryPoint} and an
 * {@code AccessDeniedHandler} instead, and they share this builder so a client parsing errors
 * sees one shape rather than two.
 */
public final class ApiErrors {

    private ApiErrors() {
    }

    public static Map<String, Object> body(HttpStatus status, String message) {
        return Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                // Map.of rejects nulls, and an AuthenticationException can carry no message.
                "message", message == null ? status.getReasonPhrase() : message);
    }

    public static ResponseEntity<Map<String, Object>> response(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(body(status, message));
    }
}
