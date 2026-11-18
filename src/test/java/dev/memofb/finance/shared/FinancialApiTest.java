package dev.memofb.finance.shared;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.awaitility.Awaitility.await;

class FinancialApiTest extends FinancialTestSupport {
    @Autowired FinancialLock ownerLock;
    @Autowired PlatformTransactionManager transactionManager;

    @ParameterizedTest @ValueSource(strings={"", " ", "\t"})
    void rejectsBlankNames(String name) throws Exception {
        http(post("/api/categories").contentType("application/json").content(json.writeValueAsString(Map.of("name",name))),400);
        assertThat(read("/api/categories").get("items").size()).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"{\"frequency\":\"MONTHLY\",\"startDay\":0}","{\"frequency\":\"MONTHLY\",\"startDay\":32}","{\"frequency\":\"WEEKLY\",\"startDay\":4}","{}"})
    void invalidPreferencesLeavePreviousConfiguration(String body) throws Exception {
        write("/api/financial-summary/settings",Map.of("frequency","SEMIMONTHLY","startDay",4));
        http(put("/api/financial-summary/settings").contentType("application/json").content(body),400);
        assertThat(read("/api/financial-summary/settings").get("startDay").asInt()).isEqualTo(4);
    }

    @Test
    void ownerLocksDoNotBlockAnotherUser() throws Exception {
        var tx=new TransactionTemplate(transactionManager);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var held=new CountDownLatch(1);var release=new CountDownLatch(1);
            var first=workers.submit(()->tx.execute(s->{ownerLock.acquire(OWNER);held.countDown();try { release.await(10,TimeUnit.SECONDS); }catch(InterruptedException e){throw new RuntimeException(e);}return true;}));
            try {
                assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
                assertThat(workers.submit(()->tx.execute(s->{ownerLock.acquire(OTHER);return true;})).get(5,TimeUnit.SECONDS)).isTrue();
            } finally { release.countDown(); }
            assertThat(first.get(5,TimeUnit.SECONDS)).isTrue();
        }
    }


}
