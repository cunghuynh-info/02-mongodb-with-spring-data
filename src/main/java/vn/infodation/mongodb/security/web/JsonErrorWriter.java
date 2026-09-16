package vn.infodation.mongodb.security.web;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;
import vn.infodation.mongodb.common.ApiErrors;

/**
 * Writes the {@link ApiErrors} body straight to the response.
 * <p>
 * Nothing in the filter chain can return a {@code ResponseEntity} - there is no handler adapter
 * that far out - so the body is serialised by hand. Same shape as every other error, which is
 * the entire point.
 */
@Component
@RequiredArgsConstructor
public class JsonErrorWriter {

    private final ObjectMapper objectMapper;

    public void write(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), ApiErrors.body(status, message));
    }
}
