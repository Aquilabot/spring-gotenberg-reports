package com.example.reports.gotenberg;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param url            base URL of the Gotenberg service
 * @param connectTimeout TCP connect timeout
 * @param readTimeout    time allowed until response headers arrive; Gotenberg only answers once
 *                       the PDF is ready, so keep this above Gotenberg's {@code --api-timeout}
 * @param retry          retry policy for retryable failures
 */
@ConfigurationProperties("gotenberg")
public record GotenbergProperties(
        @DefaultValue("http://localhost:3000") URI url,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("35s") Duration readTimeout,
        @DefaultValue Retry retry) {

    /**
     * @param maxRetries   attempts after the first one
     * @param initialDelay delay before the first retry
     * @param multiplier   growth factor of the delay
     * @param maxDelay     upper bound for a single delay
     */
    public record Retry(
            @DefaultValue("2") int maxRetries,
            @DefaultValue("300ms") Duration initialDelay,
            @DefaultValue("2.0") double multiplier,
            @DefaultValue("3s") Duration maxDelay) {
    }
}
