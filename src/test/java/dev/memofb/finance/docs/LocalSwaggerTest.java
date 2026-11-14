package dev.memofb.finance.docs;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.datasource.password=integration-test-only",
        "finance.bootstrap.email=swagger@example.com", "finance.bootstrap.password=swagger-test-passphrase"})
@ActiveProfiles("local")
@AutoConfigureMockMvc
@Testcontainers
class LocalSwaggerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Test
    void exposesUiAndGeneratedContractsWithoutOpeningPrivateServices() throws Exception {
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, "/swagger-ui/index.html"));
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/swagger-ui/swagger-ui-bundle.js")).andExpect(status().isOk());
        var initializer = mvc.perform(get("/swagger-ui/swagger-initializer.js")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(initializer).contains("requestInterceptor: async", "../api/auth/csrf", "csrf.headerName");
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/v3/api-docs"));
        var document = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
        var paths = json.readTree(document).get("paths");
        assertThat(paths.get("/api/auth/login").get("post").get("requestBody").get("content")
                .get("application/x-www-form-urlencoded").get("schema").get("properties").has("email")).isTrue();
        assertThat(paths.get("/api/auth/login").get("post").get("responses").has("204")).isTrue();
        assertThat(paths.get("/api/auth/logout").get("post").get("responses").has("204")).isTrue();
        assertThat(paths.get("/api/auth/register").has("post")).isTrue();
        assertThat(paths.get("/api/savings-pots").has("get")).isTrue();
        assertThat(paths.get("/api/savings-pots/{id}").has("delete")).isTrue();
        assertThat(paths.get("/api/savings-pots/{id}/deposits").has("post")).isTrue();
        assertThat(paths.get("/api/savings-pots/{id}/withdrawals").has("post")).isTrue();
        assertThat(paths.get("/api/savings-pots/{id}/restore").has("post")).isTrue();
        assertThat(document).doesNotContain("swagger-test-passphrase", "{bcrypt}");
        mvc.perform(get("/api/savings-pots")).andExpect(status().isUnauthorized());
    }

    @Test
    void freshCsrfTokensWorkBeforeAndAfterLoginAndLogout() throws Exception {
        var first = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) first.getRequest().getSession(false);
        var token = json.readTree(first.getResponse().getContentAsString()).get("token").asText();
        mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN", token)
                        .param("email", "swagger@example.com").param("password", "swagger-test-passphrase"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/savings-pots").session(session).header("X-CSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        var fresh = mvc.perform(get("/api/auth/csrf").session(session)).andExpect(status().isOk()).andReturn();
        token = json.readTree(fresh.getResponse().getContentAsString()).get("token").asText();
        mvc.perform(post("/api/savings-pots").session(session).header("X-CSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Swagger test\",\"targetAmount\":100.00,\"color\":\"#AABBCC\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-TOKEN", token))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk());
        mvc.perform(get("/api/savings-pots")).andExpect(status().isUnauthorized());
    }
}
