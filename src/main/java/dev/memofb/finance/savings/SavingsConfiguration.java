package dev.memofb.finance.savings;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class SavingsConfiguration {

    @Bean
    Clock savingsClock() {
        return Clock.systemUTC();
    }
}
