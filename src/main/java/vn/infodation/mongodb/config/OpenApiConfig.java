package vn.infodation.mongodb.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Swagger UI at {@code /swagger-ui.html}, the OpenAPI document at {@code /v3/api-docs}.
 * <p>
 * springdoc <b>3.x</b>, not 2.x: the 2.x line is built against Boot 3 and does not start on
 * Boot 4. It is version-pinned in {@code pom.xml} because the Boot BOM does not manage it.
 * <p>
 * The bearer scheme is declared once and applied globally, so the <b>Authorize</b> button sends
 * the token to every operation. That is the least friction for testing, with one consequence
 * worth knowing: this application runs an OAuth2 resource server, so an <em>expired</em> token
 * attached to an otherwise public endpoint still fails authentication - the bearer filter tries
 * it and gives up. If public endpoints suddenly start returning 401 in the UI, the token in the
 * Authorize box has aged out; log in again or clear it.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI labOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("MongoDB + Spring Data lab")
                        .version("0.0.1-SNAPSHOT")
                        .description("""
                                Every endpoint from the lab, in phase order. Measurements and the \
                                reasoning behind each one are in `docs/notes/`.

                                **Most write and admin endpoints need a token.** Call \
                                `POST /api/auth/login` with one of the seeded accounts \
                                (`admin@lab.local`, `analyst@lab.local`, `user@lab.local`, \
                                password `lab-password`), copy `accessToken` from the response, \
                                and paste it into **Authorize**. Tokens last 15 minutes.

                                Anonymous callers get 401, a signed-in account with the wrong \
                                role gets 403.
                                """))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste the accessToken from POST /api/auth/login. "
                                        + "Swagger adds the \"Bearer \" prefix itself.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
