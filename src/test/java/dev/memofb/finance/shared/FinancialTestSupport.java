package dev.memofb.finance.shared;

import java.time.*;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"spring.datasource.password=integration-test-only","finance.recurring-payments.scheduling-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FinancialTestSupport.Database.class)
public abstract class FinancialTestSupport {
    protected static final String OWNER="owner", OTHER="other";
    protected static final Instant NOW=Instant.parse("2027-03-20T12:00:00Z");
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MockMvc mvc;
    @Autowired protected JsonMapper json;
    @MockitoBean protected Clock clock;

    @TestConfiguration(proxyBeanMethods=false)
    static class Database {
        @Bean @ServiceConnection PostgreSQLContainer postgres() { return new PostgreSQLContainer("postgres:18-alpine"); }
    }
    @BeforeEach
    void financialFixtures() {
        when(clock.instant()).thenReturn(NOW);
        jdbc.execute("TRUNCATE transactions,recurring_payment_occurrences,recurring_payments,budgets,financial_summary_preferences,categories,savings_pots,users RESTART IDENTITY CASCADE");
        for(var owner:new String[]{OWNER,OTHER}) jdbc.update("INSERT INTO users(username,password,enabled) VALUES (?,'test-only',true)",owner);
    }
    protected JsonNode http(MockHttpServletRequestBuilder request,int expected) throws Exception { return httpAs(OWNER,request,expected); }
    protected JsonNode httpAs(String owner,MockHttpServletRequestBuilder request,int expected) throws Exception {
        var result=mvc.perform(request.with(user(owner)).with(csrf())).andExpect(status().is(expected)).andReturn();
        var body=result.getResponse().getContentAsString();return body.isBlank()?null:json.readTree(body);
    }
    protected JsonNode read(String path) throws Exception { return http(get(path),200); }
    protected JsonNode create(String path,Object body) throws Exception { return http(post(path).contentType("application/json").content(json.writeValueAsString(body)),201); }
    protected JsonNode write(String path,Object body) throws Exception { return http(put(path).contentType("application/json").content(json.writeValueAsString(body)),200); }
    protected long category(String name) throws Exception { return create("/api/categories",Map.of("name",name)).get("id").asLong(); }
    protected Map<String,Object> movement(String name,long category,String date,String amount) {
        return Map.of("name",name,"categoryId",category,"transactionDate",date,"amount",new java.math.BigDecimal(amount));
    }
    protected JsonNode transaction(String name,long category,String date,String amount) throws Exception {
        return create("/api/transactions",movement(name,category,date,amount));
    }
    protected long bill(String name,long category,String date,String amount,Integer total) throws Exception {
        var body=new java.util.HashMap<String,Object>();
        body.put("name",name);body.put("categoryId",category);body.put("firstDueDate",date);body.put("amount",new java.math.BigDecimal(amount));
        body.put("type",total==null?"SUBSCRIPTION":"INSTALLMENT");if(total!=null)body.put("totalInstallments",total);
        return create("/api/recurring-payments",body).get("id").asLong();
    }
}
