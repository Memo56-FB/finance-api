package dev.memofb.finance.savings;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import dev.memofb.finance.auth.SecurityConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SavingsPotController.class)
@Import(SecurityConfiguration.class)
@WithMockUser(username = SavingsPotControllerTest.OWNER, roles = {})
class SavingsPotControllerTest {

    static final String OWNER = "owner@example.com";
    static final String BASE = "/api/savings-pots";
    static final String VALID = "{\"name\":\"Emergency fund\",\"targetAmount\":100.00,\"color\":\"#AABBCC\"}";
    static final SavingsPotResponse POT = new SavingsPotResponse(1, "Emergency fund", new BigDecimal("100.00"),
            new BigDecimal("0.00"), "#AABBCC");

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @MockitoBean
    SavingsPotService pots;

    @MockitoBean
    UserDetailsService users;

    @Test
    void createsFromTheSessionAndReturnsOnlyThePublicFields() throws Exception {
        given(pots.create(eq(OWNER), any())).willReturn(POT);
        var body = "{\"name\":\"  Emergency fund  \",\"targetAmount\":100.00,\"color\":\"#aabbcc\","
                + "\"username\":\"other@example.com\",\"balance\":999.00,\"deletedAt\":\"2020-01-01T00:00:00Z\"}";
        var result = mvc.perform(post(BASE).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(header().string(HttpHeaders.LOCATION, BASE + "/1"))
                .andExpect(jsonPath("$.balance").value(0)).andExpect(jsonPath("$.name").value("Emergency fund"))
                .andExpect(jsonPath("$.color").value("#AABBCC")).andReturn();
        assertThat(json.readTree(result.getResponse().getContentAsString()).size()).isEqualTo(5);
        verify(pots).create(OWNER, new SavingsPotRequest("Emergency fund", new BigDecimal("100.00"), "#AABBCC"));
    }

    @Test
    void listsGetsAndUpdatesUsingTheSessionOwner() throws Exception {
        given(pots.list(OWNER, null)).willReturn(new SavingsPotListResponse(List.of(POT), BigDecimal.ZERO));
        given(pots.get(OWNER, 1)).willReturn(POT);
        given(pots.update(eq(OWNER), eq(1L), any())).willReturn(POT);
        mvc.perform(get(BASE)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(1))
                .andExpect(jsonPath("$.totalBalance").value(0));
        mvc.perform(get(BASE + "/1")).andExpect(status().isOk()).andExpect(content().json(json.writeValueAsString(POT)));
        mvc.perform(put(BASE + "/1").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(1));
        verify(pots).update(OWNER, 1, new SavingsPotRequest("Emergency fund", new BigDecimal("100.00"), "#AABBCC"));
    }

    @ParameterizedTest
    @MethodSource("invalidMetadata")
    void rejectsInvalidMetadataForBothWriteMethods(String field, Map<String, Object> body) throws Exception {
        for (var method : new HttpMethod[] {HttpMethod.POST, HttpMethod.PUT}) {
            var path = method == HttpMethod.POST ? BASE : BASE + "/1";
            var response = mvc.perform(request(method, path).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(body)))
                    .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.errors[*].field").value(hasItem(field))).andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("rejectedValue", "Exception", "SELECT", "INSERT");
        }
        verifyNoInteractions(pots);
    }

    static Stream<Arguments> invalidMetadata() {
        return Stream.of(
                Arguments.of("name", Map.of("targetAmount", 1, "color", "#AABBCC")),
                Arguments.of("targetAmount", Map.of("name", "Valid", "color", "#AABBCC")),
                Arguments.of("color", Map.of("name", "Valid", "targetAmount", 1)),
                Arguments.of("name", Map.of("name", " ", "targetAmount", 1, "color", "#AABBCC")),
                Arguments.of("targetAmount", Map.of("name", "Valid", "targetAmount", "0.001", "color", "#AABBCC")),
                Arguments.of("color", Map.of("name", "Valid", "targetAmount", 1, "color", "sensitive-invalid-color")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{\"name\":\"sensitive-body\",broken}"})
    void rejectsUnreadableJsonWithoutEchoingIt(String body) throws Exception {
        mvc.perform(post(BASE).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sensitive-body"))));
        verifyNoInteractions(pots);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "not-a-number", "9223372036854775808"})
    void rejectsInvalidIdsWithoutEchoingThem(String id) throws Exception {
        mvc.perform(get(BASE + "/" + id)).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("id"));
        verifyNoInteractions(pots);
    }

    @Test
    void missingOrUnownedPotsReturnASafeNotFoundProblem() throws Exception {
        given(pots.get(OWNER, 99)).willThrow(new SavingsPotService.NotFoundException());
        mvc.perform(get(BASE + "/99")).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404)).andExpect(jsonPath("$.detail").value("Savings pot not found"));
    }

    @Test
    void anonymousReadsAreUnauthorizedAndDoNotRedirect() throws Exception {
        for (var path : new String[] {BASE, BASE + "/1"}) {
            mvc.perform(get(path).with(anonymous())).andExpect(status().isUnauthorized())
                    .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        }
        verifyNoInteractions(pots);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT"})
    void requiresSessionAndCsrfForMetadataWrites(String methodName) throws Exception {
        var method = HttpMethod.valueOf(methodName);
        var path = method == HttpMethod.POST ? BASE : BASE + "/1";
        mvc.perform(request(method, path).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isForbidden());
        mvc.perform(request(method, path).with(csrf().useInvalidToken()).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isForbidden());
        mvc.perform(request(method, path).with(anonymous()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(pots);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE"})
    void preflightAllowsOnlyTheConfiguredOrigin(String method) throws Exception {
        mvc.perform(options(BASE + "/1").with(anonymous()).header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,x-csrf-token"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
        mvc.perform(options(BASE + "/1").with(anonymous()).header(HttpHeaders.ORIGIN, "https://untrusted.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method))
                .andExpect(status().isForbidden());
    }

    @Test
    void depositsAndWithdrawalsPassOnlyTheValidatedAmountAndSessionOwner() throws Exception {
        given(pots.deposit(OWNER, 1, new BigDecimal("25.15"))).willReturn(POT);
        given(pots.withdraw(OWNER, 1, new BigDecimal("10.00"))).willReturn(POT);
        mvc.perform(post(BASE + "/1/deposits").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":25.15}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(1));
        mvc.perform(post(BASE + "/1/withdrawals").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.00}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(1));
        verify(pots).deposit(OWNER, 1, new BigDecimal("25.15"));
        verify(pots).withdraw(OWNER, 1, new BigDecimal("10.00"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"amount\":null}", "{\"amount\":0}", "{\"amount\":-1}",
            "{\"amount\":0.001}", "{\"amount\":100000000000000000.00}", "{\"amount\":\"sensitive-invalid-money\"}",
            "{\"amount\":\"NaN\"}", "{\"amount\":NaN}", "{\"amount\":\"Infinity\"}", "null", "[]", ""})
    void rejectsInvalidMoneyBeforeCallingTheService(String body) throws Exception {
        for (var action : new String[] {"deposits", "withdrawals"}) {
            var response = mvc.perform(post(BASE + "/1/" + action).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("sensitive-invalid-money", "Exception", "rejectedValue");
        }
        verifyNoInteractions(pots);
    }

    @ParameterizedTest
    @ValueSource(strings = {"deposits", "withdrawals"})
    void moneyWritesRequireSessionAndCsrfAndExposeSafeConflicts(String action) throws Exception {
        var path = BASE + "/1/" + action;
        var amount = "{\"amount\":1.00}";
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(amount)).andExpect(status().isForbidden());
        mvc.perform(post(path).with(csrf().useInvalidToken()).contentType(MediaType.APPLICATION_JSON).content(amount))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).with(anonymous()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(amount))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(pots);

        if (action.equals("deposits")) {
            given(pots.deposit(eq(OWNER), eq(1L), any())).willThrow(new SavingsPotService.ConflictException("Balance limit"));
        } else {
            given(pots.withdraw(eq(OWNER), eq(1L), any())).willThrow(new SavingsPotService.ConflictException("Insufficient balance"));
        }
        mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(amount))
                .andExpect(status().isConflict()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409)).andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    void deletesAndRestoresUsingTheSessionAndReturnsTheDeadline() throws Exception {
        var now = Instant.parse("2026-11-14T00:00:00Z");
        given(pots.delete(OWNER, 1)).willReturn(new SavingsPotDeletionResponse(1, now, now.plusSeconds(10)));
        given(pots.restore(OWNER, 1)).willReturn(POT);
        mvc.perform(delete(BASE + "/1").with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.deletedAt").value("2026-11-14T00:00:00Z"))
                .andExpect(jsonPath("$.restoreUntil").value("2026-11-14T00:00:10Z"));
        mvc.perform(post(BASE + "/1/restore").with(csrf())).andExpect(status().isOk())
                .andExpect(content().json(json.writeValueAsString(POT)));
        verify(pots).delete(OWNER, 1);
        verify(pots).restore(OWNER, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETE", "RESTORE"})
    void deletionAndRestorationRequireSessionCsrfAndOwnership(String action) throws Exception {
        var method = action.equals("DELETE") ? HttpMethod.DELETE : HttpMethod.POST;
        var path = BASE + "/1" + (action.equals("DELETE") ? "" : "/restore");
        mvc.perform(request(method, path)).andExpect(status().isForbidden());
        mvc.perform(request(method, path).with(csrf().useInvalidToken())).andExpect(status().isForbidden());
        mvc.perform(request(method, path).with(anonymous()).with(csrf())).andExpect(status().isUnauthorized());
        verifyNoInteractions(pots);
        if (action.equals("DELETE")) {
            given(pots.delete(OWNER, 1)).willThrow(new SavingsPotService.NotFoundException());
        } else {
            given(pots.restore(OWNER, 1)).willThrow(new SavingsPotService.NotFoundException());
        }
        mvc.perform(request(method, path).with(csrf())).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Savings pot not found"));
    }

    @Test
    void expiredRestoreReturnsAConflictProblem() throws Exception {
        given(pots.restore(OWNER, 1)).willThrow(new SavingsPotService.ConflictException("The savings pot restoration period has expired"));
        mvc.perform(post(BASE + "/1/restore").with(csrf())).andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409));
    }
}
