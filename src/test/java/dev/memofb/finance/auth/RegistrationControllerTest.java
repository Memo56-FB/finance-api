package dev.memofb.finance.auth;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import(SecurityConfiguration.class)
class RegistrationControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @MockitoBean
    private RegistrationService registration;

    @MockitoBean
    private UserDetailsService users;

    @Test
    void registersAnAnonymousUserAndReturnsOnlyNameAndEmail() throws Exception {
        given(registration.register(any())).willReturn(new RegistrationService.RegisteredUser("María", "maria@example.com"));

        var result = mvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "  María  ", "email", "  MARIA@EXAMPLE.COM  ", "password", "abcdefgh"))))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"name\":\"María\",\"email\":\"maria@example.com\"}"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andReturn();
        assertThat(json.readTree(result.getResponse().getContentAsString()).size()).isEqualTo(2);
        verify(registration).register(new RegistrationRequest("María", "maria@example.com", "abcdefgh"));
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void reportsInvalidFieldsWithoutExposingRejectedValues(String field, Map<String, String> body) throws Exception {
        var result = mvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.errors[*].field").value(hasItem(field)))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty())
                .andReturn();
        var response = result.getResponse().getContentAsString();
        assertThat(response).doesNotContain("rejectedValue", "passwordWithinByteLimit", "Exception", "{bcrypt}");
        body.values().stream().filter(value -> value != null && !value.isBlank())
                .forEach(value -> assertThat(response).doesNotContain(value));
        verifyNoInteractions(registration);
    }

    static Stream<Arguments> invalidBodies() {
        var absent = Stream.of("name", "email", "password").flatMap(field -> Stream.of("omitted", "null", "blank")
                .map(kind -> {
                    var body = new HashMap<>(Map.of("name", "María", "email", "maria@example.com", "password", "abcdefgh"));
                    if (kind.equals("omitted")) {
                        body.remove(field);
                    } else {
                        body.put(field, kind.equals("null") ? null : " ".repeat(8));
                    }
                    return Arguments.of(field, body);
                }));
        var invalid = Stream.of(
                Arguments.of("name", Map.of("name", "M".repeat(101), "email", "maria@example.com", "password", "abcdefgh")),
                Arguments.of("email", Map.of("name", "María", "email", "invalid-email", "password", "abcdefgh")),
                Arguments.of("password", Map.of("name", "María", "email", "maria@example.com", "password", "secret7")),
                Arguments.of("password", Map.of("name", "María", "email", "maria@example.com", "password", "a".repeat(73))),
                Arguments.of("password", Map.of("name", "María", "email", "maria@example.com", "password", "é".repeat(37))));
        return Stream.concat(absent, invalid);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{\"password\":\"sensitive-value\",broken}", "[]"})
    void rejectsUnreadableBodiesWithoutEchoingThem(String body) throws Exception {
        var result = mvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("sensitive-value", "Exception", "JsonParse");
        verifyNoInteractions(registration);
    }

    @Test
    void mapsDuplicateEmailToASafeConflict() throws Exception {
        given(registration.register(any())).willThrow(new RegistrationService.EmailAlreadyRegisteredException());
        var result = mvc.perform(post("/api/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "María", "email", "maria@example.com", "password", "abcdefgh"))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("maria@example.com", "abcdefgh", "María", "SQL", "Exception", "{bcrypt}");
    }
}
