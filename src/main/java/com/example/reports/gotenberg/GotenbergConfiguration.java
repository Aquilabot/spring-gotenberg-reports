package com.example.reports.gotenberg;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class GotenbergConfiguration {

    @Bean
    GotenbergClient gotenbergClient(RestClient.Builder builder, GotenbergProperties properties, MeterRegistry meters) {
        return new GotenbergClient(restClient(builder, properties), properties.retry(), meters);
    }

    @Bean
    HealthIndicator gotenbergHealthIndicator(RestClient.Builder builder, GotenbergProperties properties) {
        RestClient client = restClient(builder, properties);
        return () -> {
            try {
                String body = client.get().uri("/health").retrieve().body(String.class);
                return Health.up().withDetail("url", properties.url().toString()).withDetail("gotenberg", body).build();
            } catch (RuntimeException e) {
                return Health.down(e).withDetail("url", properties.url().toString()).build();
            }
        };
    }

    /**
     * The JDK HttpClient is used on purpose: a blocked call is aborted when the calling thread is
     * interrupted, which is how a cancelled report job stops its in-flight conversions.
     */
    private static RestClient restClient(RestClient.Builder builder, GotenbergProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return builder.clone()
                .baseUrl(properties.url().toString())
                .requestFactory(requestFactory)
                .build();
    }
}
