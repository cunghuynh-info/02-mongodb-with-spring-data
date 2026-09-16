# 09 - Spring Security

Spring Boot 4.1.1 manages **Spring Security 7.1.1**. That is far enough ahead of the material
online that several of the things below are wrong everywhere else, not just unfamiliar.

Plan: [docs/plans/IMPLEMENT-SPRING-SECURITY.md](../plans/IMPLEMENT-SPRING-SECURITY.md).

---

## What was built

Stateless bearer JWT, self-issued with `NimbusJwtEncoder` and verified by `NimbusJwtDecoder`,
both from `spring-boot-starter-security-oauth2-resource-server`. No third-party JWT library:
the starter already ships Nimbus, and the resource server validates `exp`, `nbf` and `iss`
without being asked.

Identity is `sample_mflix.users`, mapped as-is. Refresh tokens are opaque rows under a TTL
index. Authorization is one `SecurityFilterChain` for routes plus method security where the
decision needs the document.

---

## The findings

### 1. The sample hashes break the stock password encoder, and the error moved

`sample_mflix.users` stores bare BCrypt - `$2b$...`, no `{id}` prefix. A
`DelegatingPasswordEncoder` reads that prefix to pick a delegate, finds none, and throws. Every
login is a 500, for data that is perfectly valid BCrypt.

The fix is one line, `setDefaultPasswordEncoderForMatches(new BCryptPasswordEncoder())`, so
unprefixed hashes go to BCrypt while anything this application writes still gets `{bcrypt}`.

The message is the part worth recording. Every tutorial quotes:

```
There is no PasswordEncoder mapped for the id "null"
```

Security 7.1 says something else entirely:

```
Given that there is no default password encoder configured, each password must have a password
encoding prefix. Please either prefix this password with '{noop}' or set a default password
encoder in `DelegatingPasswordEncoder`.
```

Same exception, same cause, better advice - and searching for the old wording finds nothing.
Pinned in `PasswordEncoderTest`, which is the only reason anyone will notice if it moves again.

### 2. Boot 4 already registers `SecurityEvaluationContextExtension`

`@Query("{ 'email' : ?#{authentication.name} }")` needs that bean to see the authentication, so
the plan said to declare it. Declaring it is not a harmless duplicate - bean-definition
overriding is off by default, so the context fails to start:

```
BeanDefinitionOverrideException: Invalid bean definition with name
'securityEvaluationContextExtension' defined in class path resource
[org/springframework/boot/security/autoconfigure/SecurityAutoConfiguration$SecurityDataConfiguration.class]
```

Boot registers it from `SecurityAutoConfiguration.SecurityDataConfiguration` as soon as
`spring-security-data` is on the classpath. Adding the dependency *is* the wiring; there is no
configuration step at all. `MethodSecurityConfig` now documents the absence, because an empty
config class invites someone to helpfully add the bean back.

Found by the slice test rather than by starting the app, which is an argument for having one.

### 3. Ownership: three ways, and only one of them is any good

`DELETE /api/movies/{id}/comments/{commentId}` implements the same rule three times, selectable
with `?strategy=`. They all refuse to delete someone else's comment. They are not equivalent.

| strategy | Mongo round trips | atomic | someone else's id | an id that does not exist |
|---|---|---|---|---|
| `post-authorize` | 2 + delete | no | **403** | **404** |
| `bean` | 2 + delete | no | 403 | 403 |
| `query` | **1** | **yes** | 404 | 404 |

Two things fall out of that table.

**The check-first strategies have a window.** They read the comment, decide, and then delete -
and the document can change owner in between. It is a small window and a contrived attack, but
it exists because the decision and the action are separate operations. Putting ownership in the
predicate removes it by construction:

```java
mongoTemplate.remove(
        Query.query(Criteria.where("_id").is(commentId).and("email").is(email)),
        Comment.class)
```

Nothing to race: the server deletes the document only if it is still yours.

**`@PostAuthorize` leaks existence through the status code.** It answers 403 for a comment that
exists and 404 for one that does not, so a caller with no rights at all can walk a list of ids
and learn which are real. The authorization is correct and the endpoint is still an oracle. The
query version cannot tell the two cases apart even in principle - the delete matched nothing,
and that is all it knows.

Asserted in `CommentOwnershipIT`, including the leak, so the difference is a test rather than a
paragraph.

### 4. `@PostFilter` quietly breaks paging

`@PostFilter("filterObject.email == authentication.name")` over a page of comments filters after
the query has already been paged. Ask for 20 and get however many of *that particular 20* were
yours:

| | page size requested | rows returned | total |
|---|---|---|---|
| `Criteria` in the query | 4 | 2 | 2, correct |
| `@PostFilter` | 4 | 2 | the server counted 4 |

Page 2 then re-reads rows page 1 already discarded, and the total is a number of rows the caller
is not allowed to see. It also reads every document first, including the ones it then throws
away - the filtering is real, the confidentiality is not, because those documents were in this
JVM's heap.

Two smaller traps in the same method:

- The returned collection must be **mutable**. `@PostFilter` removes elements through the
  collection's own iterator, so `Stream.toList()` or `List.of(...)` throws
  `UnsupportedOperationException` from inside the framework.
- SpEL *does* resolve record accessors, so `filterObject.email` works on a `record CommentView`.
  Checked rather than assumed.

### 5. `principal` is a `Jwt`, not a `UserDetails`

In `@PreAuthorize` and in the `SecurityEvaluationContextExtension` SpEL, `principal.username` is
the idiom everyone writes. Under a bearer token the principal is a `Jwt`, which has no
`username` property, so the expression fails at runtime - on the authenticated path only, the
first time somebody actually calls the query.

`authentication.name` means the same thing under both: the `sub` claim for a JWT, and
`getUsername()` for a `UserDetails`. It also works under `@WithMockUser`, which matters because
a test using the wrong one passes and production does not.

### 6. Method security is invisible when you call it from next door

`@PreAuthorize` and `@PostAuthorize` are applied by a proxy, so an annotated method called from
another method of the same bean bypasses them entirely. No warning, no log line - the
authorization simply is not there.

The tidy refactor is exactly the one that breaks it: routing the three delete strategies through
a private dispatcher would read better and would disable every annotation on the class. The
annotated methods therefore live on `CommentSecurity` and are only ever called from
`CommentDeletionService`, where the proxy is unavoidable.

### 7. The TTL index is cleanup, not enforcement

`expireAfterSeconds: 0` on `expiresAt` means "expire at the time in this field". Mongo's reaper
runs about once a minute, so an expired refresh token stays **readable for up to ~60 seconds**
after it dies. Trusting the index to have removed it makes that minute a free extension on every
expiry, so `TokenService` checks `expiresAt` itself.

`RefreshTokenIT` inserts an already-expired row and asserts it is refused while still present -
the state Mongo genuinely leaves it in, not a hypothetical.

### 8. Filter-chain errors never reach `@RestControllerAdvice`

A 401 or 403 raised by the chain happens before the `DispatcherServlet`, so
`GlobalExceptionHandler` never sees it. Left alone that gives a client two error shapes: the
app's JSON for everything a controller throws, and an empty body with a `WWW-Authenticate`
header for everything else. `ApiErrors` is now shared between the advice and the two chain
handlers.

The related trap: `AccessDeniedException` from *method* security is thrown inside the controller
and **does** reach the advice, so it needs its own handler or a future catch-all turns it into a
500.

### 9. Anonymous is 401, wrong-role is 403, and neither is our choice

`ExceptionTranslationFilter` asks the `AuthenticationTrustResolver` whether the current
authentication is anonymous and routes to the entry point if it is, the denied handler if it is
not. So the same rule produces 401 for a caller with no token and 403 for a caller with the
wrong role - which is the conventional behaviour, and worth knowing is not configurable from the
rule.

### 10. Rule order is the whole game, and getting it wrong is silent

`MovieController` and `PagingController` both sit on `/api/movies`, so `/api/movies/explain`,
`/api/movies/keyset/explain` and `/api/movies/paging-benchmark` have to be matched **before**
the broad `GET /api/movies/**` permit. Rules are evaluated in declaration order and the first
match wins; put them after and those three endpoints are simply public, with nothing to
indicate it.

This is the one row of the matrix that cannot be spotted by reading the config, so it has three
explicit rows in `SecurityMatrixTest`.

### 11. `ProviderManager` publishes no events unless you tell it to

`ProviderManager` is not `ApplicationEventPublisherAware`, so an `AuthenticationManager` built
as a `@Bean` fires no `AuthenticationSuccessEvent` or failure event at all. The Phase 7.3
listener sat there recording an empty audit log, which looks exactly like "nobody has logged in
yet". `manager.setAuthenticationEventPublisher(new DefaultAuthenticationEventPublisher(events))`
is the missing line.

### 12. Auditing has holes exactly where the atomic updates are

`@CreatedBy` is written by an entity callback, so it fires for `MongoTemplate` saves and
repository writes - and not at all for an update expressed as `$inc` or `$addToSet`. Phase 2.4's
vote and genre endpoints go straight to the server without an entity ever existing, so no
annotation on `Movie` could observe them.

An audit trail built only from these annotations is therefore missing precisely the writes that
use the interesting operators.

---

## API changes this forced

`POST /api/movies/{id}/comments` used to take `name` and `email` in the request body. That is
the author's identity, chosen by whoever was posting, so anyone could sign a comment with
anyone else's address - and ownership of the comment now hangs off that exact field. Both are
taken from the token; the body carries only `text`. The denormalised copy on the document is
unchanged.

---

## Boot 4 and Security 7, in short

Things that are simply different from every 5.x/6.x example:

1. The lambda DSL is the only DSL. `and()` and the non-lambda `HttpSecurity` overloads were
   removed in 7.0.
2. `DaoAuthenticationProvider` has no no-arg constructor and no `setUserDetailsService`; the
   `UserDetailsService` goes in the constructor.
3. `AntPathRequestMatcher` and `MvcRequestMatcher` are gone. String `requestMatchers("/api/**")`
   still works; `PathPatternRequestMatcher.pathPattern(method, pattern)` is the explicit form.
4. Declaring one `SecurityFilterChain` bean switches Boot's own chain off completely, so
   anything not listed is unprotected unless the last rule is `denyAll()`.
5. Starter names: `spring-boot-starter-security-oauth2-resource-server` and
   `spring-boot-starter-security-test`.
6. `spring-security-data` carries no version in Boot's own `dependencyManagement` - it comes
   from the imported `spring-security-bom` (7.1.1).
7. Jackson 3: the `ObjectMapper` is `tools.jackson.databind.ObjectMapper`, not
   `com.fasterxml.jackson.databind`.
8. `TestRestTemplate` is gone; `@MockBean` is gone in favour of `@MockitoBean`; `@WebMvcTest`
   moved to `org.springframework.boot.webmvc.test.autoconfigure`.

---

## What was deliberately not built

- **OAuth2 login** and an **authorization server**: one service, no browser front end.
- **Cookie sessions in Mongo** (`spring-session-data-mongodb`): the interesting half, a session
  collection under a TTL index, is what Phase 5 already does.
- **An access-token denylist**: it buys immediate revocation at the cost of a database read on
  every request. Logout revokes the refresh token, and the access token stays valid until it
  expires - stated plainly in `RefreshTokenIT` rather than papered over.
- **Lockout** after repeated failures: the events are recorded in `lab_auth_events`, the
  counting is not. It makes the suite slow and flaky for a lab that is not exposed to anything.
