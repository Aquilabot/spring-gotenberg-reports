package com.example.reports;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.reports.report.Formats;
import com.example.reports.sample.Invoice;
import com.example.reports.sample.SampleData;
import com.example.reports.sample.Statement;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end tests against a real Gotenberg container. Skipped when Docker is not available.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "reports.combined.heartbeat=200ms")
@Testcontainers(disabledWithoutDocker = true)
class ReportsApplicationIT {

    @Container
    static final GenericContainer<?> gotenberg = new GenericContainer<>("gotenberg/gotenberg:8")
            .withExposedPorts(3000)
            .waitingFor(Wait.forHttp("/health").forStatusCode(200));

    @DynamicPropertySource
    static void gotenbergUrl(DynamicPropertyRegistry registry) {
        registry.add("gotenberg.url", () -> "http://" + gotenberg.getHost() + ":" + gotenberg.getMappedPort(3000));
    }

    private static final Pattern EVENT = Pattern.compile("event:(\\w+)\\ndata:(.*)");

    private final HttpClient http = HttpClient.newHttpClient();
    private final SampleData data = new SampleData();
    private final Formats fmt = Formats.INSTANCE;

    @LocalServerPort
    int port;

    @Test
    void invoiceIsAPdfWithHeadersFontsAndValues() throws Exception {
        Invoice invoice = data.invoice(7);

        HttpResponse<byte[]> response = get("/api/reports/invoice/7?download=true");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/pdf");
        assertThat(response.headers().firstValue("Content-Disposition"))
                .hasValue("attachment; filename=\"invoice-INV-2026-0007.pdf\"");
        assertThat(response.headers().firstValueAsLong("Content-Length")).hasValue(response.body().length);
        try (PDDocument pdf = Loader.loadPDF(response.body())) {
            assertThat(text(pdf)).contains("INV-2026-0007", invoice.customer().name(), fmt.money(invoice.total()));
            assertThat(fontNames(pdf)).anyMatch(name -> name.endsWith("NotoSans-Regular"))
                    .anyMatch(name -> name.endsWith("NotoSans-Bold"));
        }
    }

    @Test
    void statementSpansPagesWithRunningFooter() throws Exception {
        Statement statement = data.statement(3);

        HttpResponse<byte[]> response = get("/api/reports/statement/3");

        assertThat(response.headers().firstValue("Content-Disposition")).hasValueSatisfying(v -> assertThat(v).startsWith("inline"));
        try (PDDocument pdf = Loader.loadPDF(response.body())) {
            int pages = pdf.getNumberOfPages();
            assertThat(pages).isGreaterThan(1);
            assertThat(text(pdf)).contains(
                    "Page 1 of " + pages,
                    "Page " + pages + " of " + pages,
                    statement.accountNumber(),
                    fmt.money(statement.closingBalance()));
        }
    }

    @Test
    void combinedReportStreamsProgressAndMergesInOrder() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/combined-reports/stream?accounts=4&concurrency=2"))
                .header("Accept", "text/event-stream").build();
        String stream = http.send(request, HttpResponse.BodyHandlers.ofString()).body();

        List<String> names = new ArrayList<>();
        String doneData = null;
        Matcher matcher = EVENT.matcher(stream);
        while (matcher.find()) {
            names.add(matcher.group(1));
            if (matcher.group(1).equals("done")) {
                doneData = matcher.group(2);
            }
        }
        assertThat(names).containsExactly("queued", "rendering", "rendering", "rendering", "rendering", "rendering",
                "merging", "done");

        String url = doneData.replaceAll(".*\"downloadUrl\":\"([^\"]+)\".*", "$1");
        HttpResponse<byte[]> pdfResponse = get(url);
        assertThat(pdfResponse.headers().firstValue("Content-Disposition"))
                .hasValue("attachment; filename=\"combined-report-2026-09.pdf\"");
        try (PDDocument pdf = Loader.loadPDF(pdfResponse.body())) {
            assertThat(pdf.getNumberOfPages()).isGreaterThanOrEqualTo(5);
            assertThat(pageText(pdf, 1)).contains("Portfolio summary", fmt.money(data.summary(4).totalClosing()));
            assertThat(pageText(pdf, 2)).contains(data.statement(1).accountNumber());
            assertThat(pageText(pdf, pdf.getNumberOfPages()))
                    .contains(data.statement(4).accountNumber(), fmt.money(data.statement(4).closingBalance()));
        }
    }

    @Test
    void closingTheStreamCancelsTheJob() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("GET /api/combined-reports/stream?accounts=60&concurrency=1 HTTP/1.1\r\n"
                    + "Host: localhost\r\nAccept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null && !line.contains("event:queued")) {
                // skip the response headers
            }
        }

        long deadline = System.currentTimeMillis() + 20_000;
        String metric = "";
        while (System.currentTimeMillis() < deadline && !metric.contains("\"COUNT\",\"value\":1.0")) {
            Thread.sleep(200);
            metric = new String(get("/actuator/metrics/reports.combined?tag=outcome:cancelled").body(), StandardCharsets.UTF_8);
        }
        assertThat(metric).contains("\"COUNT\",\"value\":1.0");
    }

    @Test
    void healthIncludesGotenberg() throws Exception {
        String health = new String(get("/actuator/health").body(), StandardCharsets.UTF_8);

        assertThat(health).containsPattern("\"gotenberg\":\\{\"details\":\\{[^}]*},\"status\":\"UP\"}");
    }

    @Test
    void unknownReportTypeIsNotFound() throws Exception {
        assertThat(get("/api/reports/payslip/1").statusCode()).isEqualTo(404);
        assertThat(get("/api/reports/invoice/0").statusCode()).isEqualTo(400);
    }

    private HttpResponse<byte[]> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String text(PDDocument pdf) throws IOException {
        return new PDFTextStripper().getText(pdf);
    }

    private static String pageText(PDDocument pdf, int page) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return stripper.getText(pdf);
    }

    private static Set<String> fontNames(PDDocument pdf) throws IOException {
        Set<String> names = new HashSet<>();
        for (PDPage page : pdf.getPages()) {
            PDResources resources = page.getResources();
            for (COSName name : resources.getFontNames()) {
                names.add(resources.getFont(name).getName());
            }
        }
        return names;
    }
}
