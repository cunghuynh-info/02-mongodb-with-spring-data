package vn.infodation.mongodb.security;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import lombok.RequiredArgsConstructor;
import vn.infodation.mongodb.security.web.RestAccessDeniedHandler;
import vn.infodation.mongodb.security.web.RestAuthenticationEntryPoint;

/**
 * Phase 0.3 and Phase 3 - the one filter chain.
 * <p>
 * Declaring this bean turns Boot's auto-configured chain off completely, so nothing is protected
 * except what is listed here. The last rule is {@code denyAll()} on purpose: a controller added
 * next month is unreachable until somebody decides what it should be, which is a much better
 * failure than being public because nobody thought about it.
 */
@Configuration
@EnableConfigurationProperties(LabSecurityProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {

        http
                // Nothing is authenticated by a cookie, so there is no ambient authority for a
                // forged cross-site form to ride on: a bearer token has to be attached by script
                // that the same-origin policy already governs. Re-enable this the moment any of
                // it moves into a cookie.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)

                .authorizeHttpRequests(auth -> auth
                        // --- the way in -------------------------------------------------------
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/login", "/api/auth/register", "/api/auth/refresh").permitAll()
                        .requestMatchers("/api/auth/**").authenticated()

                        // --- admin reads that would otherwise be swallowed by the line below ---
                        // Rules are evaluated in declaration order and the first match wins, so
                        // these three have to come before GET /api/movies/**. Getting this wrong
                        // is silent: the endpoints simply stay public.
                        .requestMatchers(HttpMethod.GET,
                                "/api/movies/explain",
                                "/api/movies/keyset/explain",
                                "/api/movies/paging-benchmark").hasRole(Roles.ADMIN)

                        // --- the public catalogue ---------------------------------------------
                        .requestMatchers(HttpMethod.GET,
                                "/api/movies/**", "/api/stats/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/search/index").hasRole(Roles.ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/search/**").permitAll()

                        // --- comments ---------------------------------------------------------
                        // Ownership on the delete cannot be expressed here - a URL rule cannot
                        // see who wrote the document. That check lives in CommentDeletionService.
                        .requestMatchers(HttpMethod.POST, "/api/movies/*/comments").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/movies/*/comments/*").authenticated()
                        .requestMatchers("/api/comments/**").authenticated()

                        // --- catalogue mutation -----------------------------------------------
                        .requestMatchers(HttpMethod.POST,
                                "/api/movies/*/votes", "/api/movies/*/genres").hasRole(Roles.ADMIN)
                        .requestMatchers(HttpMethod.DELETE, "/api/movies/*/genres").hasRole(Roles.ADMIN)

                        // --- analytics --------------------------------------------------------
                        // The reads first, then everything else in that namespace to admin. The
                        // bulk endpoints are GETs that write, so a method-based rule would have
                        // let them through; the catch-all is what covers them.
                        .requestMatchers(HttpMethod.GET,
                                "/api/analytics/balances", "/api/analytics/transfers")
                        .hasAnyRole(Roles.ANALYST, Roles.ADMIN)
                        .requestMatchers("/api/analytics/**").hasRole(Roles.ADMIN)

                        // --- operations -------------------------------------------------------
                        .requestMatchers("/api/cdc/**", "/api/indexes/**").hasRole(Roles.ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/search/index").hasRole(Roles.ADMIN)

                        .anyRequest().denyAll())

                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))

                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));

        return http.build();
    }

    /**
     * Phase 1.5 - the one line that decides whether any of this works against the sample data.
     * <p>
     * {@code sample_mflix.users} stores bare {@code $2b$...} hashes. A plain delegating encoder
     * reads the {@code {id}} prefix to pick a delegate, finds none, and throws
     * {@code IllegalArgumentException: There is no PasswordEncoder mapped for the id "null"} -
     * a 500 on every login, for data that is perfectly valid BCrypt. The default-for-matches is
     * the documented escape hatch: unprefixed hashes go to BCrypt, and anything this application
     * writes still gets a {@code {bcrypt}} prefix so the format stays self-describing.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        String encodingId = "bcrypt";
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();

        DelegatingPasswordEncoder encoder =
                new DelegatingPasswordEncoder(encodingId, Map.of(encodingId, bcrypt));
        encoder.setDefaultPasswordEncoderForMatches(bcrypt);
        return encoder;
    }

    /**
     * Phase 2.1 - the 7.x constructor takes the {@code UserDetailsService}; there is no setter.
     * <p>
     * The event publisher has to be set by hand. {@code ProviderManager} is not
     * {@code ApplicationEventPublisherAware}, so a manager built as a {@code @Bean} publishes
     * nothing and Phase 7.3's listener would sit there recording an empty audit log - which
     * looks exactly like "nobody has logged in yet".
     */
    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder,
                                                       ApplicationEventPublisher events) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);

        ProviderManager manager = new ProviderManager(provider);
        manager.setAuthenticationEventPublisher(new DefaultAuthenticationEventPublisher(events));
        return manager;
    }

    /**
     * Phase 6.1 - no origins configured means no configuration registered, which means no CORS
     * headers and a browser that refuses the call. Wide open is never the default.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(LabSecurityProperties properties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> origins = properties.getAllowedOrigins();
        if (origins.isEmpty()) {
            return source;
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // Bearer tokens travel in a header, not a cookie, so credentials are not needed - and
        // allowCredentials(true) alongside a wildcard origin is rejected by the browser anyway.
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(1800L);

        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
