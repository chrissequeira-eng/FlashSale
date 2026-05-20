package com.flashsale.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * AppConfig - defines Spring beans used across the application.
 *
 * RestTemplate is Spring's built-in HTTP client.
 * We declare it as a @Bean so Spring manages it (one instance, reusable).
 */
@Configuration
public class AppConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
