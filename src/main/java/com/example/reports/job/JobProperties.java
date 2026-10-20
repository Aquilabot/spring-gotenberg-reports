package com.example.reports.job;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param concurrency    default number of sections converted at the same time
 * @param maxConcurrency upper bound a client may ask for
 * @param maxSections    upper bound for the number of accounts in one job
 * @param timeout        a job still running after this is cancelled
 * @param heartbeat      interval of SSE keep-alive comments; a failed write means the client left
 * @param retention      how long a finished PDF stays downloadable
 */
@ConfigurationProperties("reports.combined")
public record JobProperties(
        @DefaultValue("4") int concurrency,
        @DefaultValue("8") int maxConcurrency,
        @DefaultValue("200") int maxSections,
        @DefaultValue("2m") Duration timeout,
        @DefaultValue("5s") Duration heartbeat,
        @DefaultValue("15m") Duration retention) {
}
