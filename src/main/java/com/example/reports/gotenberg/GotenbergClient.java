package com.example.reports.gotenberg;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryState;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Small client for the two Gotenberg routes this project uses.
 *
 * <ul>
 *   <li>{@code POST /forms/chromium/convert/html}: multipart with {@code index.html}, optional
 *       {@code header.html} / {@code footer.html}, and any assets.</li>
 *   <li>{@code POST /forms/pdfengines/merge}: multipart with PDFs, merged in file name order.</li>
 * </ul>
 *
 * Every attempt is timed in the {@code gotenberg.requests} timer (tags: operation, outcome).
 * Retryable failures are retried with exponential backoff; the PDF body is streamed back to the
 * caller, not buffered.
 */
public class GotenbergClient implements PdfEngine {

    static final String CONVERT_HTML = "/forms/chromium/convert/html";
    static final String MERGE = "/forms/pdfengines/merge";

    private static final Logger log = LoggerFactory.getLogger(GotenbergClient.class);
    private static final int MAX_ERROR_BODY = 2048;

    private final RestClient http;
    private final RetryTemplate retry;
    private final MeterRegistry meters;

    public GotenbergClient(RestClient http, GotenbergProperties.Retry retryProperties, MeterRegistry meters) {
        this.http = http;
        this.meters = meters;
        this.retry = new RetryTemplate(RetryPolicy.builder()
                .maxRetries(retryProperties.maxRetries())
                .delay(retryProperties.initialDelay())
                .multiplier(retryProperties.multiplier())
                .maxDelay(retryProperties.maxDelay())
                .predicate(GotenbergClient::isRetryable)
                .build());
        this.retry.setRetryListener(new RetryListener() {
            @Override
            public void beforeRetry(RetryPolicy policy, Retryable<?> retryable, RetryState state) {
                log.atWarn()
                        .addKeyValue("operation", retryable.getName())
                        .addKeyValue("attempt", state.getRetryCount() + 1)
                        .addKeyValue("error", String.valueOf(state.getLastException()))
                        .log("Retrying Gotenberg call");
            }
        });
    }

    @Override
    public PdfStream convertHtml(HtmlDocument document) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        addHtml(form, "index.html", document.indexHtml());
        addHtml(form, "header.html", document.headerHtml());
        addHtml(form, "footer.html", document.footerHtml());
        for (HtmlDocument.Asset asset : document.assets()) {
            form.add("files", file(asset.filename(), asset.content(), null));
        }
        // Page size and margins come from the template's @page rule.
        form.add("preferCssPageSize", "true");
        form.add("printBackground", "true");
        return send("convert-html", CONVERT_HTML, form);
    }

    @Override
    public PdfStream merge(List<Path> pdfs) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        for (int i = 0; i < pdfs.size(); i++) {
            // Gotenberg merges in alphanumeric file name order, so encode the position in the name.
            form.add("files", file("%05d.pdf".formatted(i), new FileSystemResource(pdfs.get(i)), MediaType.APPLICATION_PDF));
        }
        return send("merge", MERGE, form);
    }

    private static void addHtml(MultiValueMap<String, Object> form, String filename, String html) {
        if (html != null) {
            form.add("files", file(filename, html.getBytes(StandardCharsets.UTF_8), MediaType.TEXT_HTML));
        }
    }

    private static HttpEntity<Object> file(String filename, Object content, MediaType type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDispositionFormData("files", filename);
        if (type != null) {
            headers.setContentType(type);
        }
        return new HttpEntity<>(content, headers);
    }

    private PdfStream send(String operation, String path, MultiValueMap<String, Object> form) {
        try {
            return retry.execute(new Retryable<>() {
                @Override
                public PdfStream execute() {
                    return timed(operation, () -> post(path, form));
                }

                @Override
                public String getName() {
                    return operation;
                }
            });
        } catch (RetryException e) {
            if (e.getCause() instanceof GotenbergException ge) {
                throw ge;
            }
            throw new GotenbergException(0, "Gotenberg call '%s' failed".formatted(operation), e.getCause());
        }
    }

    private PdfStream post(String path, MultiValueMap<String, Object> form) {
        try {
            return http.post()
                    .uri(path)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            String detail;
                            try (response) {
                                detail = readLimited(response.getBody());
                            }
                            int status = response.getStatusCode().value();
                            throw new GotenbergException(status,
                                    "Gotenberg answered %d on %s: %s".formatted(status, path, detail), null);
                        }
                        return new PdfStream(response.getBody(), response.getHeaders().getContentLength(), response);
                    }, false);
        } catch (ResourceAccessException e) {
            throw new GotenbergException(0, "Gotenberg unreachable on %s: %s".formatted(path, e.getMessage()), e);
        }
    }

    private PdfStream timed(String operation, java.util.function.Supplier<PdfStream> call) {
        Timer.Sample sample = Timer.start(meters);
        String outcome = "success";
        try {
            return call.get();
        } catch (GotenbergException e) {
            outcome = e.status() == 0 ? "io_error" : e.status() < 500 ? "client_error" : "server_error";
            throw e;
        } finally {
            // Gotenberg sends headers only after the PDF is rendered, so this measures the conversion.
            sample.stop(Timer.builder("gotenberg.requests")
                    .description("Gotenberg calls until response headers")
                    .tag("operation", operation)
                    .tag("outcome", outcome)
                    .register(meters));
        }
    }

    private static boolean isRetryable(Throwable error) {
        return error instanceof GotenbergException ge && ge.isRetryable() && !Thread.currentThread().isInterrupted();
    }

    private static String readLimited(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(MAX_ERROR_BODY);
        return new String(bytes, StandardCharsets.UTF_8).strip();
    }
}
