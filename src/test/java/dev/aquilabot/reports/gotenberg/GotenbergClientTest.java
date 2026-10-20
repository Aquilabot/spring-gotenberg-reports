package dev.aquilabot.reports.gotenberg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

/** Runs the client against a tiny in-process HTTP server that plays Gotenberg. */
class GotenbergClientTest {

    private static final byte[] PDF = "%PDF-1.7 fake".getBytes(StandardCharsets.US_ASCII);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Queue<String> requestBodies = new ConcurrentLinkedQueue<>();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsMultipartFormAndStreamsPdfBack() throws IOException {
        GotenbergClient client = clientFor((exchange, attempt) -> respond(exchange, 200, PDF));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        client.convertHtml(document()).transferTo(out);

        assertThat(out.toByteArray()).isEqualTo(PDF);
        assertThat(requestBodies.peek())
                .contains("filename=\"index.html\"", "filename=\"footer.html\"", "filename=\"report.css\"")
                .contains("name=\"preferCssPageSize\"")
                .doesNotContain("filename=\"header.html\"");
        assertThat(meters.get("gotenberg.requests").tags("operation", "convert-html", "outcome", "success").timer().count())
                .isEqualTo(1);
    }

    @Test
    void retriesServerErrorsWithBackoff() throws IOException {
        GotenbergClient client = clientFor((exchange, attempt) -> {
            if (attempt < 3) {
                respond(exchange, 503, "Service Unavailable".getBytes(StandardCharsets.UTF_8));
            } else {
                respond(exchange, 200, PDF);
            }
        });

        try (PdfStream pdf = client.convertHtml(document())) {
            assertThat(pdf.body().readAllBytes()).isEqualTo(PDF);
        }
        assertThat(requestBodies).hasSize(3);
        assertThat(meters.get("gotenberg.requests").tag("outcome", "server_error").timer().count()).isEqualTo(2);
    }

    @Test
    void doesNotRetryClientErrorsAndKeepsGotenbergMessage() {
        GotenbergClient client = clientFor((exchange, attempt) ->
                respond(exchange, 400, "Invalid form data: form file 'index.html' is required".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> client.convertHtml(document()))
                .isInstanceOfSatisfying(GotenbergException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.isRetryable()).isFalse();
                    assertThat(e.getMessage()).contains("index.html' is required");
                });
        assertThat(requestBodies).hasSize(1);
    }

    @Test
    void givesUpAfterMaxRetriesOnTimeouts() {
        GotenbergClient client = clientFor((exchange, attempt) -> {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, PDF);
        });

        assertThatThrownBy(() -> client.convertHtml(document()))
                .isInstanceOfSatisfying(GotenbergException.class, e -> assertThat(e.status()).isZero());
        assertThat(requestBodies).hasSize(3);
    }

    @Test
    void mergeNamesFilesInOrder(@TempDir Path dir) throws IOException {
        GotenbergClient client = clientFor((exchange, attempt) -> respond(exchange, 200, PDF));
        Path a = Files.write(dir.resolve("b-first.pdf"), PDF);
        Path b = Files.write(dir.resolve("a-second.pdf"), PDF);

        client.merge(List.of(a, b)).close();

        String body = requestBodies.peek();
        assertThat(body.indexOf("filename=\"00000.pdf\"")).isPositive().isLessThan(body.indexOf("filename=\"00001.pdf\""));
    }

    private static HtmlDocument document() {
        return new HtmlDocument("<html><body>hi</body></html>", null, "<html><body>footer</body></html>",
                List.of(new HtmlDocument.Asset("report.css", "body{}".getBytes(StandardCharsets.UTF_8))));
    }

    interface Handler {
        void handle(HttpExchange exchange, int attempt) throws IOException;
    }

    private GotenbergClient clientFor(Handler handler) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            handler.handle(exchange, attempts.incrementAndGet());
        });
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        var properties = new GotenbergProperties(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(1), Duration.ofMillis(300),
                new GotenbergProperties.Retry(2, Duration.ofMillis(10), 2.0, Duration.ofMillis(50)));
        return new GotenbergConfiguration().gotenbergClient(RestClient.builder(), properties, meters);
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
