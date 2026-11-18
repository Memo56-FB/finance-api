package dev.memofb.finance.recurring;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(RecurringSchedulingConfiguration.class)
@TestPropertySource(properties={"finance.recurring-payments.scheduling-enabled=true","finance.recurring-payments.interval=PT0.02S"})
class RecurringSchedulingTest {
    @MockitoBean RecurringProcessor processor;
    @Test
    void schedulingActuallyInvokesTheProcessor() {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(()->verify(processor,atLeastOnce()).processDue());
    }
}
