package dev.memofb.finance.auth;

import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
    private JdbcUserDetailsManager users;

    @Autowired
    private PasswordEncoder passwords;

    @BeforeEach
    void createUserInPostgres() {
        if (users.userExists(EMAIL)) {
            users.deleteUser(EMAIL);
        }
        users.createUser(User.withUsername(EMAIL).password(passwords.encode(PASSWORD)).roles("USER").build());
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
        users.updateUser(User.withUserDetails(users.loadUserByUsername(EMAIL)).disabled(true).build());
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
}
