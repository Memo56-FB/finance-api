package dev.memofb.finance.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import dev.memofb.finance.FinanceApiApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.web.server.context.ConfigurableWebServerApplicationContext;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PersistentSessionTest {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Test
    void signedInSessionSurvivesInactivityAndAnApiRestartUntilLogout() throws Exception {
        var username = "m".repeat(254);
        var password = "integration-test-passphrase";
        String sessionCookie;
        try (var client = HttpClient.newHttpClient()) {
            try (var first = startApi()) {
                first.getBean(RegistrationService.class).register(new RegistrationRequest(username, password));
                var csrf = send(client, request(first, "csrf").GET());
                assertThat(csrf.statusCode()).isEqualTo(200);
                var anonymousCookie = sessionCookie(csrf);
                var login = send(client, request(first, "login")
                        .header("Cookie", anonymousCookie)
                        .header("X-CSRF-TOKEN", token(first, csrf))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("username=" + username + "&password=" + password)));
                assertThat(login.statusCode()).isEqualTo(204);
                sessionCookie = sessionCookie(login);
                assertThat(sessionCookie).isNotEqualTo(anonymousCookie).matches("JSESSIONID=[0-9a-f-]{36}");
                assertThat(login.headers().allValues("Set-Cookie")).anySatisfy(cookie ->
                        assertThat(cookie).contains("HttpOnly", "Secure", "SameSite=Lax"));
            }
            try (var second = startApi()) {
                var jdbc = second.getBean(JdbcTemplate.class);
                assertThat(jdbc.update("""
                        UPDATE spring_session SET last_access_time = ?
                        WHERE principal_name = ? AND max_inactive_interval < 0
                        """,
                        Instant.now().minus(Duration.ofDays(1)).toEpochMilli(), username)).isEqualTo(1);
                var me = send(client, request(second, "me").header("Cookie", sessionCookie).GET());
                assertThat(me.statusCode()).isEqualTo(200);
                assertThat(second.getBean(JsonMapper.class).readTree(me.body()).get("username").asText())
                        .isEqualTo(username);

                var csrf = send(client, request(second, "csrf").header("Cookie", sessionCookie).GET());
                assertThat(csrf.statusCode()).isEqualTo(200);
                var logout = send(client, request(second, "logout")
                        .header("Cookie", sessionCookie)
                        .header("X-CSRF-TOKEN", token(second, csrf))
                        .POST(HttpRequest.BodyPublishers.noBody()));
                assertThat(logout.statusCode()).isEqualTo(204);
                assertThat(logout.headers().allValues("Set-Cookie")).anySatisfy(cookie ->
                        assertThat(cookie).startsWith("JSESSIONID=;")
                                .containsAnyOf("Max-Age=0", "Expires=Thu, 01 Jan 1970"));
                assertThat(send(client, request(second, "me").header("Cookie", sessionCookie).GET()).statusCode())
                        .isEqualTo(401);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = ?",
                        Long.class, username)).isZero();
            }
        }
    }

    private ConfigurableWebServerApplicationContext startApi() {
        return (ConfigurableWebServerApplicationContext) new SpringApplicationBuilder(FinanceApiApplication.class)
                .initializers(context -> TestPropertyValues.of(Map.of(
                        "server.address", "127.0.0.1",
                        "server.port", "0",
                        "spring.datasource.url", postgres.getJdbcUrl(),
                        "spring.datasource.username", postgres.getUsername(),
                        "spring.datasource.password", postgres.getPassword(),
                        "finance.recurring-payments.scheduling-enabled", "false")).applyTo(context))
                .run();
    }

    private HttpRequest.Builder request(WebServerApplicationContext app, String action) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.getWebServer().getPort()
                + "/api/auth/" + action)).timeout(Duration.ofSeconds(10));
    }

    private HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String sessionCookie(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(cookie -> cookie.startsWith("JSESSIONID="))
                .findFirst().orElseThrow().split(";", 2)[0];
    }

    private String token(WebServerApplicationContext app, HttpResponse<String> response) {
        return app.getBean(JsonMapper.class).readTree(response.body()).get("token").asText();
    }
}
