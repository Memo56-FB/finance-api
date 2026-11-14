package dev.memofb.finance.auth;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.password=integration-test-only")
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class AuthFlowTest {

    private static final String EMAIL = "memo@example.com";
    private static final String PASSWORD = "integration-test-passphrase";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private UserDetailsService users;

    @Autowired
    private PasswordEncoder passwords;

    @Autowired
    private RegistrationService registration;

    @Autowired
    private JdbcTemplate jdbc;

    private static final String REGISTER_EMAIL = "maria@example.com";

    @Test
    void swaggerIsDisabledOutsideTheLocalProfile() throws Exception {
        for (var path : new String[] {"/v3/api-docs", "/v3/api-docs/swagger-config", "/swagger-ui.html", "/swagger-ui/index.html"}) {
            mvc.perform(get(path).with(user(users.loadUserByUsername(EMAIL)))).andExpect(status().isNotFound());
        }
    }

    @BeforeEach
    void createUserInPostgres() {
        jdbc.update("DELETE FROM users WHERE username IN (?, ?)", REGISTER_EMAIL, EMAIL);
        jdbc.update("INSERT INTO users (username, password, enabled) VALUES (?, ?, true)", EMAIL, passwords.encode(PASSWORD));
    }

    @Test
    void persistsTheRegisteredUserWithNameAndHashWithoutRoles() {
        var created = registration.register(new RegistrationRequest("  María José  ", "  MARIA@EXAMPLE.COM  ", "abcdefgh"));

        assertThat(created).isEqualTo(new RegistrationService.RegisteredUser("María José", REGISTER_EMAIL));
        var user = jdbc.queryForMap("SELECT * FROM users WHERE username = ?", REGISTER_EMAIL);
        assertThat(user).containsEntry("name", "María José").containsEntry("enabled", true);
        assertThat(user.get("password")).isNotEqualTo("abcdefgh");
        assertThat(passwords.matches("abcdefgh", (String) user.get("password"))).isTrue();
        assertThat(users.loadUserByUsername(REGISTER_EMAIL).getAuthorities()).isEmpty();
    }

    @Test
    void createsTheLocalUserWithoutRolesAndKeepsItsPasswordOnRestart() throws Exception {
        var configuration = new LocalUserConfiguration();
        configuration.localUser(jdbc, passwords, "  MARIA@EXAMPLE.COM  ", "local-test-passphrase").run(null);
        var before = jdbc.queryForMap("SELECT * FROM users WHERE username = ?", REGISTER_EMAIL);
        assertThat(before).containsEntry("name", null).containsEntry("enabled", true);
        assertThat(passwords.matches("local-test-passphrase", (String) before.get("password"))).isTrue();
        assertThat(users.loadUserByUsername(REGISTER_EMAIL).getAuthorities()).isEmpty();

        configuration.localUser(jdbc, passwords, REGISTER_EMAIL, "different-passphrase").run(null);
        assertThat(jdbc.queryForMap("SELECT * FROM users WHERE username = ?", REGISTER_EMAIL)).isEqualTo(before);
    }

    @Test
    void localBootstrapDoesNotChangeAnExistingDisabledAccount() throws Exception {
        jdbc.update("UPDATE users SET name = ?, enabled = false WHERE username = ?", "Memo", EMAIL);
        var before = jdbc.queryForMap("SELECT * FROM users WHERE username = ?", EMAIL);

        new LocalUserConfiguration().localUser(jdbc, passwords, EMAIL, "different-passphrase").run(null);

        assertThat(jdbc.queryForMap("SELECT * FROM users WHERE username = ?", EMAIL)).isEqualTo(before);
    }

    @Test
    void duplicatesNeverOverwriteExistingOrDisabledAccounts() {
        registration.register(new RegistrationRequest("María", REGISTER_EMAIL, "abcdefgh"));
        for (boolean enabled : new boolean[] {true, false}) {
            jdbc.update("UPDATE users SET enabled = ? WHERE username = ?", enabled, REGISTER_EMAIL);
            var before = jdbc.queryForMap("SELECT * FROM users WHERE username = ?", REGISTER_EMAIL);
            for (String email : new String[] {REGISTER_EMAIL, "  MARIA@EXAMPLE.COM  "}) {
                assertThatThrownBy(() -> registration.register(new RegistrationRequest("Otra", email, "new-password")))
                        .isInstanceOf(RegistrationService.EmailAlreadyRegisteredException.class);
                assertThat(jdbc.queryForMap("SELECT * FROM users WHERE username = ?", REGISTER_EMAIL)).isEqualTo(before);
            }
        }
    }

    @Test
    void leavesNoAccountIfSavingTheNameFails() {
        jdbc.execute("ALTER TABLE users ADD CONSTRAINT test_name_failure CHECK (name IS DISTINCT FROM '__fail__')");
        try {
            assertThatThrownBy(() -> registration.register(new RegistrationRequest("__fail__", REGISTER_EMAIL, "abcdefgh")))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .isNotInstanceOf(RegistrationService.EmailAlreadyRegisteredException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username = ?",
                    Long.class, REGISTER_EMAIL)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE users DROP CONSTRAINT test_name_failure");
        }
    }

    @Test
    void registersWithRealCsrfThenLogsInWithAnEightCharacterPassword() throws Exception {
        var browser = anonymousSession();
        register(browser, "  María José O'Neill  ", "  MARIA@EXAMPLE.COM  ", "abcdefgh")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("María José O'Neill"))
                .andExpect(jsonPath("$.email").value(REGISTER_EMAIL))
                .andExpect(jsonPath("$.password").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT name FROM users WHERE username = ?", String.class, REGISTER_EMAIL))
                .isEqualTo("María José O'Neill");
        mvc.perform(get("/api/auth/me").session(browser.session())).andExpect(status().isUnauthorized());

        mvc.perform(post("/api/auth/login").session(browser.session())
                        .header("X-CSRF-TOKEN", browser.token())
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", "MARIA@EXAMPLE.COM").param("password", "abcdefgh"))
                .andExpect(status().isNoContent());
        var me = mvc.perform(get("/api/auth/me").session(browser.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(REGISTER_EMAIL)).andReturn();
        assertThat(json.readTree(me.getResponse().getContentAsString()).size()).isEqualTo(1);
    }

    @Test
    void preservesSignificantSpacesInPasswords() throws Exception {
        var browser = anonymousSession();
        register(browser, "María", REGISTER_EMAIL, "  abcdefgh  ").andExpect(status().isCreated());
        mvc.perform(post("/api/auth/login").session(browser.session())
                        .header("X-CSRF-TOKEN", browser.token())
                        .param("email", REGISTER_EMAIL).param("password", "abcdefgh"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").session(browser.session())
                        .header("X-CSRF-TOKEN", browser.token())
                        .param("email", REGISTER_EMAIL).param("password", "  abcdefgh  "))
                .andExpect(status().isNoContent());
    }

    @Test
    void rejectsRegistrationWithoutAValidCsrfTokenAndDoesNotPersist() throws Exception {
        var body = json.writeValueAsString(Map.of("name", "María", "email", REGISTER_EMAIL, "password", "abcdefgh"));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username = ?", Long.class, REGISTER_EMAIL)).isZero();
        mvc.perform(post("/api/auth/register").with(csrf().useInvalidToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username = ?", Long.class, REGISTER_EMAIL))
                .isZero();
    }

    @Test
    void permitsRegistrationPreflightOnlyForTheConfiguredOrigin() throws Exception {
        mvc.perform(options("/api/auth/register")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,x-csrf-token"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
        mvc.perform(options("/api/auth/register")
                        .header(HttpHeaders.ORIGIN, "https://untrusted.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void concurrentRegistrationsCreateOnlyOneCompleteAccount() throws Exception {
        var firstBrowser = anonymousSession();
        var secondBrowser = anonymousSession();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return register(firstBrowser, "Primera", REGISTER_EMAIL, "first-password")
                        .andReturn().getResponse().getStatus();
            });
            var second = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return register(secondBrowser, "Segunda", "  MARIA@EXAMPLE.COM  ", "second-password")
                        .andReturn().getResponse().getStatus();
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var firstStatus = first.get(20, TimeUnit.SECONDS);
            var secondStatus = second.get(20, TimeUnit.SECONDS);
            assertThat(new int[] {firstStatus, secondStatus}).containsExactlyInAnyOrder(201, 409);

            var account = jdbc.queryForMap("SELECT * FROM users WHERE username = ?", REGISTER_EMAIL);
            assertThat(account).containsEntry("name", firstStatus == 201 ? "Primera" : "Segunda")
                    .containsEntry("enabled", true);
            var hash = (String) account.get("password");
            assertThat(passwords.matches(firstStatus == 201 ? "first-password" : "second-password", hash)).isTrue();
            assertThat(passwords.matches(firstStatus == 201 ? "second-password" : "first-password", hash)).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE username = ?", Long.class, REGISTER_EMAIL))
                    .isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void loginRotatesTheSessionAndLogoutRequiresAFreshCsrfToken() throws Exception {
        var csrfResult = mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
                .andReturn();
        var session = (MockHttpSession) csrfResult.getRequest().getSession(false);
        var initialSessionId = session.getId();
        var oldToken = json.readTree(csrfResult.getResponse().getContentAsString()).get("token").asText();

        mvc.perform(post("/api/auth/login").session(session)
                        // Simulate the browser sending its existing session ID.
                        .with(request -> {
                            request.setRequestedSessionId(initialSessionId);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("email", EMAIL).param("password", PASSWORD)
                        .header("X-CSRF-TOKEN", oldToken))
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        assertThat(session.getId()).isNotEqualTo(initialSessionId);

        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.password").doesNotExist());

        mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-TOKEN", oldToken))
                .andExpect(status().isForbidden());
        var freshCsrf = mvc.perform(get("/api/auth/csrf").session(session))
                .andExpect(status().isOk()).andReturn();
        var newToken = json.readTree(freshCsrf.getResponse().getContentAsString()).get("token").asText();
        mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-TOKEN", newToken))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsEmailWithoutCaseSensitivity() throws Exception {
        var result = mvc.perform(post("/api/auth/login").with(csrf())
                        .param("email", EMAIL.toUpperCase(Locale.ROOT)).param("password", PASSWORD))
                .andExpect(status().isNoContent()).andReturn();
        mvc.perform(get("/api/auth/me").session((MockHttpSession) result.getRequest().getSession(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL));
    }

    @Test
    void rejectsAnonymousRequestsWithoutRedirectingToAnHtmlLogin() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
    }

    @Test
    void rejectsWrongCredentialsAndUnknownUsersWithTheSameStatus() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf())
                        .param("email", EMAIL).param("password", "wrong-password"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").with(csrf())
                        .param("email", "missing@example.com").param("password", PASSWORD))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsDisabledAccounts() throws Exception {
        jdbc.update("UPDATE users SET enabled = false WHERE username = ?", EMAIL);
        mvc.perform(post("/api/auth/login").with(csrf())
                        .param("email", EMAIL).param("password", PASSWORD))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsLoginWithoutAValidCsrfToken() throws Exception {
        mvc.perform(post("/api/auth/login").param("email", EMAIL).param("password", PASSWORD))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").with(csrf().useInvalidToken())
                        .param("email", EMAIL).param("password", PASSWORD))
                .andExpect(status().isForbidden());
    }

    @Test
    void allowsCredentialedPreflightOnlyFromTheConfiguredFrontend() throws Exception {
        mvc.perform(options("/api/auth/login")
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,x-csrf-token"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
        mvc.perform(options("/api/auth/login")
                        .header(HttpHeaders.ORIGIN, "https://untrusted.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }

    private CsrfSession anonymousSession() throws Exception {
        var result = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        return new CsrfSession((MockHttpSession) result.getRequest().getSession(false),
                json.readTree(result.getResponse().getContentAsString()).get("token").asText());
    }

    private ResultActions register(CsrfSession browser, String name, String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/register").session(browser.session())
                .header("X-CSRF-TOKEN", browser.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", name, "email", email, "password", password))));
    }

    private record CsrfSession(MockHttpSession session, String token) {
    }
}
