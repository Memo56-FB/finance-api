package dev.memofb.finance.savings;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.datasource.password=integration-test-only", "finance.recurring-payments.scheduling-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class SavingsPotServiceTest {

    static final String OWNER = "owner@example.com";
    static final String OTHER = "other@example.com";
    static final Instant NOW = Instant.parse("2026-11-14T00:00:00.123456Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired
    SavingsPotService service;

    @Autowired
    SavingsPotRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    PasswordEncoder passwords;

    @MockitoBean
    Clock clock;

    @BeforeEach
    void fixtures() {
        when(clock.instant()).thenReturn(NOW);
        jdbc.update("DELETE FROM transactions");
        jdbc.update("DELETE FROM savings_pots");
        jdbc.update("DELETE FROM users");
        for (var email : new String[] {OWNER, OTHER}) {
            jdbc.update("INSERT INTO users (username, password, enabled) VALUES (?, 'test-only', true)", email);
        }
    }

    @Test
    void createsMultiplePotsAndListsOnlyActiveOwnedBalances() {
        assertThat(service.list(OWNER).items()).isEmpty();
        assertThat(service.list(OWNER).totalBalance()).isZero();
        var first = create(OWNER);
        var second = create(OWNER);
        var deleted = create(OWNER);
        var other = create(OTHER);
        assertThat(first.balance()).isZero();
        repository.updateBalance(OWNER, first.id(), new BigDecimal("100.10"));
        repository.updateBalance(OWNER, second.id(), new BigDecimal("20.20"));
        repository.updateBalance(OWNER, deleted.id(), new BigDecimal("50.00"));
        repository.updateDeletedAt(OWNER, deleted.id(), Instant.parse("2026-11-14T00:00:00Z"));
        repository.updateBalance(OTHER, other.id(), new BigDecimal("700.00"));

        var list = service.list(OWNER);
        assertThat(list.items()).extracting(SavingsPotResponse::id).containsExactly(first.id(), second.id());
        assertThat(list.totalBalance()).isEqualByComparingTo("120.30");
        assertThatThrownBy(() -> service.get(OTHER, first.id())).isInstanceOf(SavingsPotService.NotFoundException.class);
        assertThatThrownBy(() -> service.get(OWNER, deleted.id())).isInstanceOf(SavingsPotService.NotFoundException.class);
    }

    @Test
    void editsOnlyMetadataAndAllowsTheTargetBelowTheBalance() {
        var pot = create(OWNER);
        repository.updateBalance(OWNER, pot.id(), new BigDecimal("100.00"));
        var request = new SavingsPotRequest("  Viaje a México  ", new BigDecimal("50.00"), "#aabbcc");
        var edited = service.update(OWNER, pot.id(), request);
        assertThat(edited.id()).isEqualTo(pot.id());
        assertThat(edited.name()).isEqualTo("Viaje a México");
        assertThat(edited.color()).isEqualTo("#AABBCC");
        assertThat(edited.targetAmount()).isEqualByComparingTo("50.00");
        assertThat(edited.balance()).isEqualByComparingTo("100.00");
        assertThatThrownBy(() -> service.update(OTHER, pot.id(), request))
                .isInstanceOf(SavingsPotService.NotFoundException.class);
        repository.updateDeletedAt(OWNER, pot.id(), Instant.parse("2026-11-14T00:00:00Z"));
        assertThatThrownBy(() -> service.update(OWNER, pot.id(), request))
                .isInstanceOf(SavingsPotService.NotFoundException.class);
    }

    @Test
    void totalsCanExceedTheIndividualBalanceLimit() {
        var max = new BigDecimal("99999999999999999.99");
        for (int index = 0; index < 2; index++) {
            repository.updateBalance(OWNER, create(OWNER).id(), max);
        }
        assertThat(service.list(OWNER).totalBalance()).isEqualByComparingTo(max.multiply(BigDecimal.TWO));
    }

    @Test
    void depositsAndWithdrawalsAreExactAndMayExceedTheTarget() {
        var pot = create(OWNER);
        service.deposit(OWNER, pot.id(), new BigDecimal("0.10"));
        assertThat(service.deposit(OWNER, pot.id(), new BigDecimal("0.20")).balance()).isEqualByComparingTo("0.30");
        assertThat(service.deposit(OWNER, pot.id(), new BigDecimal("200.00")).balance()).isEqualByComparingTo("200.30");
        assertThat(service.withdraw(OWNER, pot.id(), new BigDecimal("20.20")).balance()).isEqualByComparingTo("180.10");
        assertThat(service.list(OWNER).totalBalance()).isEqualByComparingTo("180.10");
        assertThat(service.withdraw(OWNER, pot.id(), new BigDecimal("180.10")).balance()).isZero();
    }

    @Test
    void rejectsInsufficientFundsOverflowAndInaccessiblePotsWithoutChangingBalances() {
        var pot = create(OWNER);
        assertThatThrownBy(() -> service.withdraw(OWNER, pot.id(), BigDecimal.ONE))
                .isInstanceOf(SavingsPotService.ConflictException.class);
        assertThat(service.get(OWNER, pot.id()).balance()).isZero();
        var max = new BigDecimal("99999999999999999.99");
        repository.updateBalance(OWNER, pot.id(), max);
        assertThatThrownBy(() -> service.deposit(OWNER, pot.id(), new BigDecimal("0.01")))
                .isInstanceOf(SavingsPotService.ConflictException.class);
        assertThat(service.get(OWNER, pot.id()).balance()).isEqualByComparingTo(max);
        assertThatThrownBy(() -> service.deposit(OTHER, pot.id(), BigDecimal.ONE))
                .isInstanceOf(SavingsPotService.NotFoundException.class);
        assertThatThrownBy(() -> service.withdraw(OTHER, pot.id(), BigDecimal.ONE))
                .isInstanceOf(SavingsPotService.NotFoundException.class);
        repository.updateDeletedAt(OWNER, pot.id(), Instant.parse("2026-11-14T00:00:00Z"));
        assertThatThrownBy(() -> service.deposit(OWNER, pot.id(), BigDecimal.ONE))
                .isInstanceOf(SavingsPotService.NotFoundException.class);
        assertThatThrownBy(() -> service.withdraw(OWNER, pot.id(), BigDecimal.ONE))
                .isInstanceOf(SavingsPotService.NotFoundException.class);
        assertThat(repository.lock(OWNER, pot.id()).orElseThrow().balance()).isEqualByComparingTo(max);
    }

    @Test
    void concurrentDepositsKeepBothChanges() throws Exception {
        var id = create(OWNER).id();
        concurrently(() -> service.deposit(OWNER, id, new BigDecimal("10.00")),
                () -> service.deposit(OWNER, id, new BigDecimal("20.00")));
        assertThat(service.get(OWNER, id).balance()).isEqualByComparingTo("30.00");
    }

    @Test
    void concurrentWithdrawalsCannotSpendTheSameFundsTwice() throws Exception {
        var id = create(OWNER).id();
        service.deposit(OWNER, id, new BigDecimal("100.00"));
        Callable<String> withdraw = () -> {
            try {
                service.withdraw(OWNER, id, new BigDecimal("80.00"));
                return "success";
            } catch (SavingsPotService.ConflictException exception) {
                return "conflict";
            }
        };
        assertThat(concurrently(withdraw, withdraw)).containsExactlyInAnyOrder("success", "conflict");
        assertThat(service.get(OWNER, id).balance()).isEqualByComparingTo("20.00");
    }

    @Test
    void deletionPreservesMoneyAndMetadataAndRestoreRecoversTheOriginalContribution() {
        var pot = create(OWNER);
        var before = service.deposit(OWNER, pot.id(), new BigDecimal("150.00"));
        var deletion = service.delete(OWNER, pot.id());
        assertThat(deletion.deletedAt()).isEqualTo(NOW);
        assertThat(deletion.restoreUntil()).isEqualTo(NOW.plusSeconds(10));
        assertThat(service.list(OWNER).items()).isEmpty();
        assertThat(service.list(OWNER).totalBalance()).isZero();
        assertThatThrownBy(() -> service.get(OWNER, pot.id())).isInstanceOf(SavingsPotService.NotFoundException.class);
        assertThat(repository.lock(OWNER, pot.id()).orElseThrow().balance()).isEqualByComparingTo("150.00");

        when(clock.instant()).thenReturn(NOW.plusSeconds(5));
        assertThat(service.delete(OWNER, pot.id())).isEqualTo(deletion);
        when(clock.instant()).thenReturn(NOW.plusSeconds(9));
        assertThat(service.restore(OWNER, pot.id())).isEqualTo(before);
        assertThat(service.restore(OWNER, pot.id())).isEqualTo(before);
        assertThat(service.list(OWNER).totalBalance()).isEqualByComparingTo("150.00");
        assertThatThrownBy(() -> service.delete(OTHER, pot.id())).isInstanceOf(SavingsPotService.NotFoundException.class);
        assertThatThrownBy(() -> service.restore(OTHER, pot.id())).isInstanceOf(SavingsPotService.NotFoundException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {10, 11})
    void restoreExpiresAtExactlyTenSecondsAndKeepsThePersistedDeadlineAfterServiceRecreation(long seconds) {
        var id = create(OWNER).id();
        service.deposit(OWNER, id, BigDecimal.TEN);
        var deletion = service.delete(OWNER, id);
        when(clock.instant()).thenReturn(NOW.plusSeconds(seconds));
        var recreated = new SavingsPotService(repository, clock,
                new dev.memofb.finance.shared.FinancialLock(jdbc),
                new dev.memofb.finance.shared.BusinessCalendar(clock, "America/Mexico_City"),
                new dev.memofb.finance.transactions.TransactionRepository(jdbc));
        var transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.execute(status -> recreated.restore(OWNER, id)))
                .isInstanceOf(SavingsPotService.ConflictException.class);
        assertThat(repository.lock(OWNER, id).orElseThrow().deletedAt()).isEqualTo(deletion.deletedAt());
        assertThat(repository.lock(OWNER, id).orElseThrow().balance()).isEqualByComparingTo("10.00");
        assertThat(service.list(OWNER).totalBalance()).isZero();
    }

    @Test
    void repeatedConcurrentRestoresDoNotMultiplyTheBalance() throws Exception {
        var id = create(OWNER).id();
        var before = service.deposit(OWNER, id, BigDecimal.TEN);
        service.delete(OWNER, id);
        assertThat(concurrently(() -> service.restore(OWNER, id), () -> service.restore(OWNER, id)))
                .containsExactly(before, before);
        assertThat(service.list(OWNER).totalBalance()).isEqualByComparingTo("10.00");
    }

    @Test
    void deletionAndDepositHaveASerialOutcomeWithoutLosingMoney() throws Exception {
        var id = create(OWNER).id();
        service.deposit(OWNER, id, BigDecimal.TEN);
        var results = concurrently(() -> {
            try {
                service.deposit(OWNER, id, new BigDecimal("5.00"));
                return "deposited";
            } catch (SavingsPotService.NotFoundException exception) {
                return "already-deleted";
            }
        }, () -> {
            service.delete(OWNER, id);
            return "deleted";
        });
        assertThat(results.get(1)).isEqualTo("deleted");
        assertThat(repository.lock(OWNER, id).orElseThrow().balance())
                .isEqualByComparingTo(results.get(0).equals("deposited") ? "15.00" : "10.00");
        assertThat(service.list(OWNER).totalBalance()).isZero();
        assertThatThrownBy(() -> service.deposit(OWNER, id, BigDecimal.ONE))
                .isInstanceOf(SavingsPotService.NotFoundException.class);
    }

    @Test
    void restoreChecksTheClockOnlyAfterItObtainsTheRowLock() throws Exception {
        var id = create(OWNER).id();
        service.delete(OWNER, id);
        clearInvocations(clock);
        var transaction = new TransactionTemplate(transactionManager);
        try (var executor = Executors.newSingleThreadExecutor()) {
            try {
                var future = transaction.execute(status -> {
                    repository.lock(OWNER, id).orElseThrow();
                    var waiting = executor.submit(() -> transaction.execute(worker -> {
                        jdbc.execute("SET LOCAL application_name = 'savings-restore-test'");
                        return service.restore(OWNER, id);
                    }));
                    await().pollInSameThread().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("""
                            SELECT EXISTS (
                                SELECT 1 FROM pg_locks locks JOIN pg_stat_activity activity ON activity.pid = locks.pid
                                WHERE activity.application_name = 'savings-restore-test' AND NOT locks.granted
                            )
                            """, Boolean.class));
                    verify(clock, never()).instant();
                    when(clock.instant()).thenReturn(NOW.plusSeconds(10));
                    return waiting;
                });
                assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(java.util.concurrent.ExecutionException.class)
                        .hasCauseInstanceOf(SavingsPotService.ConflictException.class);
            } finally {
                executor.shutdownNow();
            }
        }
        assertThat(repository.lock(OWNER, id).orElseThrow().deletedAt()).isEqualTo(NOW);
    }

    @Test
    void fullHttpFlowUsesRealLoginCsrfAndKeepsOtherUsersOut() throws Exception {
        jdbc.update("UPDATE users SET password = ?", passwords.encode("integration-test-passphrase"));
        var owner = login(OWNER);
        var other = login(OTHER);
        var base = "/api/savings-pots";
        var metadata = "{\"name\":\"  Emergency fund  \",\"targetAmount\":100.00,\"color\":\"#aabbcc\","
                + "\"username\":\"other@example.com\",\"balance\":999.00}";
        var created = mvc.perform(as(owner, post(base).contentType(MediaType.APPLICATION_JSON).content(metadata)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.balance").value(0))
                .andExpect(jsonPath("$.name").value("Emergency fund")).andReturn();
        var id = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        var path = base + "/" + id;
        assertThat(created.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo(path);
        mvc.perform(as(owner, get(base))).andExpect(status().isOk()).andExpect(jsonPath("$.totalBalance").value(0));
        mvc.perform(as(owner, get(path))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(as(other, get(base))).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalBalance").value(0));
        var amount = "{\"amount\":1.00}";
        var attempts = List.of(get(path),
                put(path).contentType(MediaType.APPLICATION_JSON).content(metadata),
                post(path + "/deposits").contentType(MediaType.APPLICATION_JSON).content(amount),
                post(path + "/withdrawals").contentType(MediaType.APPLICATION_JSON).content(amount),
                delete(path), post(path + "/restore"));
        for (var attempt : attempts) {
            mvc.perform(as(other, attempt)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Savings pot not found"));
        }
        assertThat(service.get(OWNER, id).balance()).isZero();
        var updated = "{\"name\":\"Updated\",\"targetAmount\":2.00,\"color\":\"#000000\"}";
        mvc.perform(as(owner, put(path).contentType(MediaType.APPLICATION_JSON).content(updated)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Updated"))
                .andExpect(jsonPath("$.balance").value(0));
        mvc.perform(as(owner, get(base))).andExpect(jsonPath("$.totalBalance").value(0));
        mvc.perform(as(owner, post(path + "/deposits").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":25.15}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(25.15));
        mvc.perform(as(owner, get(base))).andExpect(jsonPath("$.totalBalance").value(25.15));
        mvc.perform(as(owner, post(path + "/withdrawals").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10.00}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(15.15));
        mvc.perform(as(owner, get(base))).andExpect(jsonPath("$.totalBalance").value(15.15));
        mvc.perform(as(owner, delete(path))).andExpect(status().isOk())
                .andExpect(jsonPath("$.restoreUntil").value(NOW.plusSeconds(10).toString()));
        mvc.perform(as(owner, get(path))).andExpect(status().isNotFound());
        mvc.perform(as(owner, get(base))).andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.totalBalance").value(0));
        when(clock.instant()).thenReturn(NOW.plusSeconds(9));
        mvc.perform(as(owner, post(path + "/restore"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated")).andExpect(jsonPath("$.balance").value(15.15));
        mvc.perform(as(owner, get(base))).andExpect(jsonPath("$.totalBalance").value(15.15));
        mvc.perform(as(other, get(base))).andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.totalBalance").value(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM savings_pots", Long.class)).isEqualTo(1);
    }

    private Browser login(String email) throws Exception {
        var anonymous = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) anonymous.getRequest().getSession(false);
        var token = json.readTree(anonymous.getResponse().getContentAsString()).get("token").asText();
        mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN", token)
                        .param("email", email).param("password", "integration-test-passphrase"))
                .andExpect(status().isNoContent()).andExpect(header().doesNotExist(HttpHeaders.LOCATION));
        var fresh = mvc.perform(get("/api/auth/csrf").session(session)).andExpect(status().isOk()).andReturn();
        return new Browser(session, json.readTree(fresh.getResponse().getContentAsString()).get("token").asText());
    }

    private MockHttpServletRequestBuilder as(Browser browser, MockHttpServletRequestBuilder request) {
        return request.session(browser.session()).header("X-CSRF-TOKEN", browser.token());
    }

    private record Browser(MockHttpSession session, String token) {
    }

    private <T> List<T> concurrently(Callable<T> first, Callable<T> second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = Stream.of(first, second).map(action -> executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return action.call();
            })).toList();
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                return List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS));
            } finally {
                start.countDown();
                executor.shutdownNow();
            }
        }
    }

    private SavingsPotResponse create(String owner) {
        return service.create(owner, new SavingsPotRequest("Same name", new BigDecimal("100.00"), "#FFFFFF"));
    }
}
