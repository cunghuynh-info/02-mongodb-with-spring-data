# Implement Spring Security

Goal: put Spring Security in front of the existing 40-odd endpoints as small, runnable slices
in the same shape as [docs/PLAN.md](../PLAN.md) - each one ending in an endpoint or a rule plus
a test that proves the behaviour, not just that the context starts.

Where there is a choice, the slice that also exercises **MongoDB** wins: the identity store is a
Mongo collection, refresh tokens expire through a TTL index, ownership checks run as `Criteria`
inside the query rather than as a filter in Java, and the audit fields are written by Spring
Data auditing.

Stack already in place: Spring Boot 4.1.1 / Java 17, `spring-boot-starter-webmvc`,
`spring-boot-starter-data-mongodb`, Lombok, and the `compose.yaml` lab (single-node replica set
+ mongot + seeded Atlas sample data).

Spring Boot 4.1.1 manages **Spring Security 7.1.1** (through the imported `spring-security-bom`).
That is a major version ahead of most material online - the removals are listed under
[Gotchas](#gotchas-spring-security-7-and-this-codebase) and are worth reading before writing any
config class.

---

> **Status: implemented.** Phases 0 to 9 are done except for the five items left unticked below,
> each of which says why. Findings and measurements are in
> [docs/notes/09-security.md](../notes/09-security.md).
>
> `./mvnw test` is green: 46 tests, no Docker, including the whole route matrix.
> **`./mvnw verify` has not been run** - Docker Desktop was not available on the machine this was
> built on, so `AuthenticationFlowIT`, `RefreshTokenIT`, `CommentOwnershipIT` and `MyCommentsIT`
> are written but unexecuted. Run it before trusting them.
>
> Four things the plan got wrong, all corrected in place below and written up in the notes:
>
> 1. **4.4 needs no bean.** Boot 4 registers `SecurityEvaluationContextExtension` itself. Adding
>    it fails the context with `BeanDefinitionOverrideException`.
> 2. **Gotcha 5's error message changed in Security 7.1.** The wording every tutorial quotes no
>    longer appears anywhere.
> 3. **0.3's "403" is wrong.** Anonymous gets 401; 403 is for an authenticated caller with the
>    wrong role, and `ExceptionTranslationFilter` decides which, not us.
> 4. **8.3 cannot use `TestRestTemplate`** - Boot 4 removed it. The end-to-end test is MockMvc
>    over the real filter chain and real tokens instead.

---

## Decisions, made once

| Decision | Choice | Why not the other thing |
|---|---|---|
| Session model | Stateless, bearer JWT | The API is already stateless and `http/requests.http` is the client; cookies would drag in CSRF and a session store for no lesson |
| Token library | `NimbusJwtEncoder` / `NimbusJwtDecoder` from `spring-security-oauth2-jose` | jjwt and java-jwt are third-party and hand-rolled validation; the resource-server starter already ships Nimbus and validates `exp` / `iss` / `aud` for free |
| Identity store | `sample_mflix.users` | Real data with real BCrypt hashes; a fresh `app_users` collection would teach nothing the sample data cannot |
| Password encoder | `DelegatingPasswordEncoder` with a BCrypt fallback for unprefixed hashes | The sample hashes are bare `$2b$...` with no `{bcrypt}` prefix - see gotcha 5 |
| Authorization style | Default-deny chain rules for coarse routes, `@PreAuthorize` for anything needing the document | URL rules cannot see who owns a comment |
| Refresh tokens | Mongo documents under a TTL index | Expiry as a database concern is the Mongo-flavoured half of the exercise; `lab_sessions` and its `sessions_ttl` index already exist from Phase 3.8 |

**Authorities.** Three roles, stored as a `roles` array on the user document, mapped to
`ROLE_ADMIN` / `ROLE_ANALYST` / `ROLE_USER`. The sample user documents have no `roles` field at
all, so the mapper must treat absent as `[USER]`.

---

## Target package layout

```
vn.infodation.mongodb
└── security/
    ├── SecurityConfig.java           the SecurityFilterChain, CORS, entry points
    ├── JwtConfig.java                keypair, JwtEncoder, JwtDecoder, JwtAuthenticationConverter
    ├── MethodSecurityConfig.java     @EnableMethodSecurity (the extension is Boot's - gotcha 13)
    ├── domain/
    │   ├── UserAccount.java          @Document("users") on sample_mflix
    │   └── RefreshToken.java         @Document("lab_refresh_tokens"), TTL on expiresAt
    ├── repository/
    │   ├── UserAccountRepository.java
    │   └── RefreshTokenRepository.java
    ├── service/
    │   ├── MongoUserDetailsService.java
    │   ├── TokenService.java         mint / parse / rotate / revoke
    │   ├── LabUserSeeder.java        property-guarded, known credentials
    │   ├── SecurityAuditorAware.java who @CreatedBy names
    │   └── AuthenticationEventListener.java  the lab_auth_events log
    └── web/
        ├── AuthController.java       /api/auth/**
        └── dto/                      LoginRequest, TokenResponse, MeResponse
```

Nothing moves. The only edits to existing files are `pom.xml`, `IndexConfig` (two indexes),
`CommentController` / `CommentService` (take the author from the principal), `application.yaml`,
`README.md` and `http/requests.http`.

---

## Phase 0 - lock the door first

- [x] **0.1 Dependencies.** Add to `pom.xml`, all version-managed by the Boot parent:
      `spring-boot-starter-security`, `spring-boot-starter-security-oauth2-resource-server`
      (Boot 4 renamed it from `spring-boot-starter-oauth2-resource-server`; both are in the BOM,
      prefer the new name), `spring-boot-starter-validation`, `spring-security-data`
      (groupId `org.springframework.security`, no `<version>` - the security BOM manages it), and
      `spring-boot-starter-security-test` at `test` scope.
- [ ] **0.2 Prove the default lock. Skipped.** This was a look-at-it step with nothing to keep,
      and it needs a running Mongo, which was not available. Nothing downstream depends on it.
- [x] **0.3 One explicit chain.** A single `SecurityFilterChain` bean: `csrf` disabled (stateless
      bearer API), `sessionManagement` STATELESS, `httpBasic` / `formLogin` off, and
      `authorizeHttpRequests(a -> a.anyRequest().denyAll())`. Everything is refused now, on
      purpose - the rules get added in 3.3 route by route, so nothing is ever public by omission.
      **Corrected:** an anonymous caller gets **401**, not the 403 this plan claimed.
      `ExceptionTranslationFilter` asks the trust resolver and sends anonymous requests to the
      entry point; 403 is what an authenticated caller with the wrong role gets.
- [x] **0.4 Confirm the existing suite still passes.** The current ITs are service- and
      repository-level (`grep -rl MockMvc src/test` finds nothing), so the filter chain is not in
      their path - `mvn verify` must stay green with no test edits. If it does not, the chain is
      being applied somewhere it should not be.

**Done when:** `GET /api/movies` is refused with no `WWW-Authenticate` header, and `mvn verify`
is green without touching an existing test. (`mvn test` is green and no existing test was
touched; `mvn verify` still needs a run with Docker up.)

## Phase 1 - identity, out of Mongo

- [x] **1.1 Map the real users.** `UserAccount` as `@Document(collection = "users")` on
      `sample_mflix`: `ObjectId id`, `name`, `email`, `password`, plus `List<String> roles`,
      `boolean enabled` and `Instant createdAt` that the sample documents do not carry. Confirm
      the shape first - `db.users.findOne()` - rather than trusting this plan.
      **Unverified:** no Mongo was reachable, so the mapping was written from the documented
      shape of `sample_mflix.users` and not checked against a real document. The absent-field
      handling means a surprise degrades to "an ordinary enabled user" rather than a failure, but
      run that `findOne()` before relying on it.
- [x] **1.2 Repository.** `UserAccountRepository extends MongoRepository<UserAccount, ObjectId>`
      with `Optional<UserAccount> findByEmail(String email)`.
- [x] **1.3 Unique index.** Add `users_email_unique` to `IndexConfig` next to the existing ones,
      unique on `email`. The sample collection already holds a few hundred users; a duplicate
      surfaces here rather than at the first double registration.
- [x] **1.4 `MongoUserDetailsService`.** Return a `User` with `ROLE_`-prefixed authorities, absent
      `roles` defaulting to `USER`, absent `enabled` defaulting to true. Throw
      `UsernameNotFoundException` - never return null - or `DaoAuthenticationProvider` cannot tell
      the difference between "no such user" and a bug.
- [x] **1.5 The password encoder.** `PasswordEncoderFactories.createDelegatingPasswordEncoder()`
      **plus** `setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder())`, so the bare
      `$2b$` hashes already in the sample data match while anything this app writes gets a
      `{bcrypt}` prefix. See gotcha 5 for the exception you get without it.
- [x] **1.6 Known credentials.** `LabUserSeeder`, an `ApplicationRunner` that upserts three users
      whose passwords you actually know: `admin@lab.local`, `analyst@lab.local`, `user@lab.local`.
      The sample hashes are hashes of unknown plaintext, so without this there is no way to log in
      at all. Upsert by email, never delete.
      **Changed:** guarded by `@ConditionalOnProperty(lab.security.seed.enabled)` only, not also
      by `@Profile({"dev","test"})`. With the profile guard, `./mvnw spring-boot:run` - the way
      the README says to start the app - activates no profile and seeds nothing, so the lab ships
      unusable. The property defaults to false in code and is set true in `application.yaml`, so
      a deployment that does not ship that file still gets nothing.

**Done when:** an IT loads a seeded user through `MongoUserDetailsService`, asserts the
authorities, and asserts `passwordEncoder.matches` succeeds against **both** a seeded
`{bcrypt}`-prefixed hash and a raw `$2b$` hash copied out of the sample data.

## Phase 2 - authenticate and mint

- [x] **2.1 `AuthenticationManager`.** `new ProviderManager(new DaoAuthenticationProvider(userDetailsService))`
      with the encoder set, exposed as a bean so `AuthController` can call it. The 7.x constructor
      takes the `UserDetailsService` (gotcha 2).
- [x] **2.2 Signing key.** An RSA keypair. For the lab, generate at startup **but** read a fixed
      PEM from `lab.security.jwt.private-key` when present, so a devtools restart does not
      invalidate the token sitting in `http/requests.http` (gotcha 9). Never commit a key; the
      `.env.example` pattern already in the repo is where it gets documented.
- [x] **2.3 `JwtEncoder` / `JwtDecoder`.** `NimbusJwtEncoder` over an `ImmutableJWKSet`,
      `NimbusJwtDecoder.withPublicKey(...)`. Claims: `iss` (the app), `sub` (email), `iat` / `exp`
      (15 minutes), and `roles` as a string array. No `authorities` claim - naming it `roles`
      keeps the converter in 3.2 honest about the prefix.
- [x] **2.4 `POST /api/auth/login`.** `{email, password}` in; `{accessToken, refreshToken,
      expiresIn, roles}` out. Bad credentials return 401 in the same JSON shape
      `GlobalExceptionHandler` produces - map `BadCredentialsException` there, and never leak
      whether the email exists.
- [x] **2.5 `POST /api/auth/register`.** Validated (`@Valid`, email format, password length),
      encodes the password, assigns `["USER"]`, and returns 409 from the duplicate-key error
      raised by the 1.3 index rather than checking-then-inserting - the check-then-insert race is
      the point.
- [x] **2.6 `GET /api/auth/me`.** Echoes the authenticated principal. This is the endpoint every
      later phase debugs against.

**Done when:** a `@SpringBootTest(webEnvironment = RANDOM_PORT)` IT logs in as the seeded admin,
decodes the returned JWT, asserts `sub` and `roles`, and asserts that a wrong password returns
401 with the same body shape as every other error in this app.

## Phase 3 - resource server and the route matrix

- [x] **3.1 Turn on the resource server.** `oauth2ResourceServer(o -> o.jwt(...))` on the chain.
- [x] **3.2 Authority mapping.** A `JwtAuthenticationConverter` holding a
      `JwtGrantedAuthoritiesConverter` with authorities-claim `roles` and authority-prefix
      `ROLE_`, so `hasRole('ADMIN')` matches a `roles: ["ADMIN"]` claim.
- [x] **3.3 The rules.** Replace `denyAll()` with the matrix below, keeping `anyRequest().denyAll()`
      as the last line so a route added later is denied until someone decides otherwise.
      **Changed:** `GET /api/search/index` is admin too, not just the `POST`. The plan left the
      read public while making `GET /api/indexes/**` admin, and both expose index metadata - one
      Atlas Search, one Mongo. There was no reason for them to differ.

| Method | Path | Rule | Why |
|---|---|---|---|
| POST | `/api/auth/login`, `/api/auth/register`, `/api/auth/refresh` | `permitAll` | the way in |
| GET | `/api/movies/**`, `/api/search/**`, `/api/stats/**` | `permitAll` | the catalogue is the public half of the app |
| GET | `/api/movies/*/comments/**` | `permitAll` | reading comments needs no account |
| POST | `/api/movies/*/comments` | `authenticated` | Phase 4 takes the author from the principal |
| DELETE | `/api/movies/*/comments/*` | `authenticated` + ownership | new endpoint, added in 4.3 |
| POST, DELETE | `/api/movies/*/votes`, `/api/movies/*/genres` | `hasRole('ADMIN')` | these mutate the catalogue |
| GET | `/api/analytics/balances`, `/api/analytics/transfers` | `hasAnyRole('ANALYST','ADMIN')` | account data |
| POST | `/api/analytics/transfer**`, `/api/analytics/bulk/**` | `hasRole('ADMIN')` | moves money, writes in bulk |
| any | `/api/cdc/**` | `hasRole('ADMIN')` | starts and stops a background listener |
| any | `/api/indexes/**`, `/api/search/index` | `hasRole('ADMIN')` | index management is a schema change |
| GET | `/api/movies/explain`, `/api/movies/keyset/explain`, `/api/movies/paging-benchmark` | `hasRole('ADMIN')` | explain output describes the indexes; the benchmark is a load generator |
| any | anything else | `denyAll` | default-deny |

- [x] **3.4 Watch the path collision.** `MovieController` and `PagingController` both sit on
      `/api/movies`, so `/api/movies/explain` and `/api/movies/keyset/explain` have to be matched
      **before** the broad `GET /api/movies/**` permit - rules are evaluated in declaration order
      and the first match wins. This is the one row in the matrix that is easy to get silently
      wrong, so it gets its own test.
- [x] **3.5 Error shape.** A `RestAuthenticationEntryPoint` (401) and `RestAccessDeniedHandler`
      (403) producing the same `{timestamp, status, error, message}` body as
      `GlobalExceptionHandler`. Filter-chain exceptions never reach `@RestControllerAdvice`
      (gotcha 6), so factor the body builder out of the advice and share it rather than
      duplicating the JSON shape in three places.

**Done when:** a MockMvc test walks the matrix - anonymous, `USER`, `ANALYST`, `ADMIN` against one
endpoint from each row - asserting 200 / 401 / 403 for each, including that
`GET /api/movies/explain` is 403 for an anonymous caller while `GET /api/movies/573a...` is 200.

## Phase 4 - method security and document ownership

- [x] **4.1 `@EnableMethodSecurity`** on a config class, defaults (pre/post enabled).
- [x] **4.2 Author from the principal.** `POST /api/movies/{id}/comments` currently takes `name`
      and `email` from the request body, which lets anyone comment as anyone. Take both from the
      authenticated user, keep only `text` in the body, and drop those fields from `NewComment`.
      The denormalised `name` / `email` on `Comment` stay exactly as they are; they are just no
      longer client-supplied.
- [x] **4.3 Ownership, three ways.** Add `DELETE /api/movies/{id}/comments/{commentId}` and
      implement the check three times, keeping all three side by side the way Phase 1 keeps the
      four read paths:
      - `@PostAuthorize("returnObject.email == authentication.name")` - reads the document, then
        decides. One extra round trip, and it leaks existence through timing.
      - `@PreAuthorize("@commentSecurity.canDelete(#commentId, authentication)")` - a bean method,
        readable, still an extra read.
      - ownership pushed into the query: `remove(query(where("_id").is(id).and("email").is(name)))`,
        404 when `getDeletedCount() == 0`. One round trip, atomic, no TOCTOU window.

      Record the trade in `docs/notes/`: the third is the right default, and the first two are
      what most codebases actually ship.
- [x] **4.4 `SecurityEvaluationContextExtension`.** Add a "my comments" repository query using
      `@Query("{ 'email' : ?#{authentication.name} }")`. Use `authentication.name`, **not**
      `principal.username` - under a bearer token the principal is a `Jwt`, not a `UserDetails`
      (gotcha 8).
      **Corrected:** do *not* declare the bean. Boot 4 registers it already (gotcha 13), and a
      second definition fails the context outright. The `spring-security-data` dependency from
      0.1 is the entire wiring.
- [x] **4.5 `@PostFilter`, and when not to.** Demonstrate `@PostFilter` over a list of comments,
      then measure it against the same predicate pushed into the `Criteria`. Post-filtering a
      paged result silently returns fewer rows than the requested page size - that is the finding
      worth writing down.

**Done when:** a test shows user A cannot delete user B's comment through any of the three paths,
the query-level variant issues exactly one Mongo operation, and `@PostFilter` over a `Pageable`
result returns fewer rows than `size` while the `Criteria` version does not.

## Phase 5 - refresh tokens, expiring in the database

- [x] **5.1 `RefreshToken` document.** `lab_refresh_tokens`: `id` (an opaque 256-bit random, not a
      JWT), `userEmail`, `issuedAt`, `expiresAt`, `revokedAt`, `replacedBy`, plus `userAgent` and
      `ip` for the audit trail. Note the existing `lab_sessions` collection and its `sessions_ttl`
      index from Phase 3.8 - the same shape, which is why that index is already in `IndexConfig`.
- [x] **5.2 TTL index.** `expireAfterSeconds: 0` on `expiresAt`, added to `IndexConfig`, so Mongo
      deletes expired tokens itself. Write down what the phase actually teaches: the TTL monitor
      runs about once a minute, so an expired token stays *readable* for up to ~60 s and the
      application must check `expiresAt` anyway. The index is cleanup, not enforcement.
- [x] **5.3 `POST /api/auth/refresh`.** Rotation: validate, mark the old token revoked with
      `replacedBy`, issue a new pair. Do it in one `findAndModify` so two concurrent refreshes
      cannot both succeed.
- [x] **5.4 Reuse detection.** A refresh presented after it was already rotated means the token
      leaked: revoke the whole chain for that user and return 401. That is the reason to store
      `replacedBy` rather than deleting the old row.
- [x] **5.5 `POST /api/auth/logout`.** Revokes the presented refresh token. The access token stays
      valid until `exp` - say so in the notes rather than pretending otherwise; a stateless JWT
      cannot be un-issued without a denylist.
- [ ] **5.6 (optional) Access-token denylist.** A `jti` claim plus a denylist collection under a
      TTL matching the access-token lifetime, checked in a small `OncePerRequestFilter`. It buys
      immediate revocation at the cost of a read per request - implement it, measure it, and let
      the measurement decide.
      **Not done.** Optional, and it trades a database read on every request for immediate
      revocation - not a trade worth making for a lab. The cost is stated plainly in
      `RefreshTokenIT` instead: logout revokes the refresh token, the access token runs out.

**Done when:** an IT logs in, refreshes, asserts the old refresh token is now rejected, replays it,
and asserts the whole chain is revoked - plus a test that inserts an already-expired token and
shows the application rejects it before the TTL monitor has had a chance to run.

## Phase 6 - the things that are wrong by default

- [x] **6.1 CORS.** A `CorsConfigurationSource` bean with an explicit origin list read from
      configuration (empty by default), and `cors(withDefaults())` on the chain. Never
      `allowedOrigins("*")` together with `allowCredentials(true)` - the browser rejects it anyway.
- [x] **6.2 CSRF, decided rather than disabled.** CSRF is off because this is stateless bearer auth
      with no cookie. Put that reasoning in `SecurityConfig` as a comment, with a note on what
      would have to change if the cookie variant listed under "out of scope" is ever built.
- [x] **6.3 Headers.** The defaults are mostly right. Add HSTS only behind TLS, and a
      `Content-Security-Policy` if anything is ever served as HTML.
- [ ] **6.4 Actuator, if it is added.** If it arrives: `/actuator/health` `permitAll` with
      `show-details: when-authorized`, everything else `hasRole('ADMIN')`. The default of exposing
      only health is safe; setting `management.endpoints.web.exposure.include=*` is where it goes
      wrong.
      **Not applicable:** actuator is still not a dependency, and adding one to secure it would be
      scope this task did not ask for. The `denyAll()` catch-all covers `/actuator/**` today.
- [x] **6.5 Secrets.** `MONGO_URI` already carries `root:example`. Add the JWT key and the seeded
      passwords to `.env.example` as placeholders, and confirm `.gitignore` covers `.env` - it
      does today. A test that fails when a key literal appears in `application.yaml` is cheap.

**Done when:** a cross-origin preflight from a configured origin gets the right headers and one
from an unconfigured origin does not, both asserted.

## Phase 7 - auditing

- [x] **7.1 `@EnableMongoAuditing`** with an `AuditorAware<String>` reading `SecurityContextHolder`
      and returning `Optional.empty()` for anonymous - which is what makes `@CreatedBy` nullable,
      and why the seeder has to tolerate it.
- [x] **7.2 Audit fields on writes.** `@CreatedBy` / `@CreatedDate` / `@LastModifiedBy` on
      `Comment` and `RefreshToken`. Note the interaction with what is already here: the audit
      callbacks fire on `MongoTemplate` and repository saves, but **not** on the raw `$inc` and
      `$addToSet` updates in `MovieService`, which bypass entity callbacks entirely. Worth a line
      in the notes.
- [x] **7.3 Authentication events.** An `ApplicationListener` for `AuthenticationSuccessEvent` and
      `AbstractAuthenticationFailureEvent` writing to a `lab_auth_events` collection, capped or
      under a TTL.
- [ ] **7.4 (optional) Lockout.** Count failures per email over a window from 7.3 and reject past a
      threshold. Keep it behind a property; on by default it makes the suite slower and flakier.
      **Not done.** Optional. The events are recorded; the counting is not.

**Done when:** a comment created over HTTP carries `createdBy` equal to the caller's email, and a
failed login leaves exactly one document in `lab_auth_events`.

## Phase 8 - testing security properly

- [x] **8.1 Slice tests.** `@WebMvcTest` + `@Import(SecurityConfig.class)` with mocked services,
      using `@WithMockUser(roles = "ADMIN")` and
      `SecurityMockMvcRequestPostProcessors.jwt().authorities(...)` for the bearer path. Fast, no
      Docker, and the right home for the whole Phase 3 matrix.
- [ ] **8.2 A `@WithLabUser` annotation. Not built - it would have wrapped nothing.**
      `SecurityMockMvcRequestPostProcessors.jwt()` already produces a real `JwtAuthenticationToken`
      with a real `Jwt` principal, which was the entire point of the annotation. A meta-annotation
      around it would add a name and a second place for the principal shape to drift.
- [x] **8.3 End to end.** An IT on top of `AbstractMongoIntegrationTest` that registers and logs
      in over HTTP, carries the bearer token, and walks register → me → comment → refresh →
      logout. This is the test that catches the converter and matcher mistakes the slices miss.
      **Changed:** MockMvc over the real filter chain, not `RANDOM_PORT`. Boot 4 removed
      `TestRestTemplate`, and the thing being tested - minting, the `Authorization` header,
      `NimbusJwtDecoder`, the authorities converter - is all exercised either way; only the
      servlet container is not.
- [x] **8.4 A negative test per rule, not per endpoint.** Anonymous, wrong role, a garbled token,
      an expired refresh token, a replayed one.
      **Partial:** no `alg: none` case and no backdated *access* token. The expired case is
      covered for refresh tokens, where it also proves the TTL-lag point; for access tokens it
      would only be re-testing Nimbus.
- [x] **8.5 Keep `mvn test` Docker-free.** Slice tests are `*Test` under surefire; anything
      touching the container stays `*IT` under failsafe, exactly as the existing suite splits them.

**Done when:** `mvn test` runs the whole authorization matrix with no Docker, and `mvn verify`
adds the end-to-end flow.

## Phase 9 - wrap-up

- [x] **9.1 `http/requests.http`.** An auth block at the top that logs in and stashes the token
      with a response handler (`client.global.set("token", response.body.accessToken)`), then
      `Authorization: Bearer {{token}}` on the protected requests. The file has to stay runnable
      top to bottom, which is how it is used today.
- [x] **9.2 `docs/notes/09-security.md`.** The measurements and the surprises, in the style of the
      other eight: the three ownership checks and their round-trip counts, the TTL monitor lag,
      `@PostFilter` breaking paging, and whatever Security 7 turns out to have moved.
- [x] **9.3 `README.md`.** The seeded lab credentials, how to get a token, and the route matrix.
- [x] **9.4 `docs/PLAN.md`** gets a pointer to this file as Phase 10.

---

## Deliberately out of scope

Each is a reasonable next step; none is needed to secure this app.

- **OAuth2 login** (`spring-boot-starter-security-oauth2-client`) - a Google or GitHub login for a
  lab with no browser front end is ceremony.
- **An authorization server** (`spring-boot-starter-security-oauth2-authorization-server`) -
  self-issued tokens are honest for a single-service API. Revisit if a second service appears.
- **Cookie sessions in Mongo** (`spring-session-data-mongodb`, version-managed by the
  `spring-session-bom` Boot already imports) - the interesting half, a session collection under a
  TTL index, is already covered by Phase 5.
- **WebAuthn / passkeys** - Security 7 ships `spring-security-webauthn`, and it needs a browser.
- **Rate limiting** - belongs at the gateway, not in the filter chain.

---

## Gotchas: Spring Security 7 and this codebase

Checked against this repo's dependency tree and the Boot 4.1.1 BOM, not remembered from 5.x
tutorials.

1. **The lambda DSL is the only DSL.** `and()` and every non-lambda `HttpSecurity` overload were
   removed in 7.0. Any snippet chaining `.csrf().disable().authorizeRequests()` will not compile.
2. **`DaoAuthenticationProvider` has no no-arg constructor.** Use
   `new DaoAuthenticationProvider(userDetailsService)`; `setUserDetailsService` is gone.
3. **`AntPathRequestMatcher` and `MvcRequestMatcher` are gone.** String
   `requestMatchers("/api/**")` still works; for anything explicit use
   `PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/login")`.
4. **Declaring one `SecurityFilterChain` bean switches Boot's auto-configured chain off entirely.**
   Anything not listed is unprotected by omission unless the last rule is `denyAll()`. That is why
   Phase 0.3 starts from deny-everything instead of adding rules to a permissive default.
5. **The sample hashes carry no `{id}` prefix.** A plain `createDelegatingPasswordEncoder()`
   throws `IllegalArgumentException` on the first login against `sample_mflix.users`. Fix with
   `setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder())`.
   **Corrected:** the message is no longer `There is no PasswordEncoder mapped for the id "null"`,
   which is what every tutorial still quotes. Security 7.1 says *"Given that there is no default
   password encoder configured, each password must have a password encoding prefix..."* - same
   exception, same cause, and searching for the old wording finds nothing. Pinned in
   `PasswordEncoderTest`.
6. **Filter-chain exceptions never reach `@RestControllerAdvice`.** `GlobalExceptionHandler` will
   not see a 401 or a 403; `AuthenticationEntryPoint` and `AccessDeniedHandler` write those bodies,
   which is why 3.5 shares the body builder rather than duplicating the shape.
7. **`AccessDeniedException` from *method* security is different.** It is thrown inside the
   controller, so it *does* reach the advice - and a broad `Exception` handler added later would
   turn it into the wrong status. Handle it explicitly.
8. **`principal` is a `Jwt`, not a `UserDetails`.** In `@PreAuthorize` and in
   `SecurityEvaluationContextExtension` SpEL, `principal.username` fails under bearer auth. Use
   `authentication.name` (the `sub` claim), which works under both `@WithMockUser` and a real token.
9. **devtools is on the classpath.** A restart regenerates an in-memory keypair and invalidates the
   token pasted into `requests.http`. Hold the dev key in configuration (2.2).
10. **Boot 4 property names.** Connection config is `spring.mongodb.uri`, not
    `spring.data.mongodb.uri` - the Phase 2 lesson, which applies again to every new test class.
11. **Boot 4 starter names.** `spring-boot-starter-security-oauth2-resource-server` and
    `spring-boot-starter-security-test`. The older `spring-boot-starter-oauth2-*` spellings still
    resolve, but the project already uses the split Boot 4 names everywhere else.
12. **`spring-security-data` has no entry in the Boot BOM's own `dependencyManagement`.** It is
    managed through the imported `spring-security-bom` (7.1.1), so declare it without a `<version>`.
13. **Boot 4 already registers `SecurityEvaluationContextExtension`** from
    `SecurityAutoConfiguration.SecurityDataConfiguration`, as soon as `spring-security-data` is on
    the classpath. Declaring it as well is not a harmless duplicate - bean-definition overriding is
    off by default, so the context fails to start with a `BeanDefinitionOverrideException` naming
    both definitions. Adding the dependency *is* the wiring.
14. **Jackson 3.** Boot 4 moved to `tools.jackson.databind.ObjectMapper`; the
    `com.fasterxml.jackson` packages are not on the classpath. This repo's `JacksonBsonConfig`
    already used the new package, which is the giveaway.
15. **The test API moved too.** `TestRestTemplate` is gone, `@MockBean` is replaced by
    `@MockitoBean` (`org.springframework.test.context.bean.override.mockito`), and `@WebMvcTest`
    lives in `org.springframework.boot.webmvc.test.autoconfigure`.
16. **`ProviderManager` publishes no events on its own.** It is not
    `ApplicationEventPublisherAware`, so an `AuthenticationManager` declared as a `@Bean` fires no
    success or failure event and Phase 7.3's listener records nothing - which is indistinguishable
    from nobody having logged in. Set the publisher explicitly.

---

## Order and effort

Phases 0 to 3 are the backbone: after them the app is genuinely secured, and they are worth doing
in order in one sitting. Phase 4 is where the interesting MongoDB content starts and is the one to
spend time on. Phase 5 is independent of 4 and can come first if TTL indexes are the more
appealing half. Phases 6 and 7 are small. Phase 8 should be written alongside each phase rather
than saved for the end - the matrix test from 3.3 is what makes every later refactor safe.
