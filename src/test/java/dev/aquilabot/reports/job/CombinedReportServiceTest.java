package dev.aquilabot.reports.job;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aquilabot.reports.gotenberg.GotenbergException;
import dev.aquilabot.reports.gotenberg.HtmlDocument;
import dev.aquilabot.reports.gotenberg.PdfEngine;
import dev.aquilabot.reports.gotenberg.PdfStream;
import dev.aquilabot.reports.report.TestRenderers;
import dev.aquilabot.reports.sample.SampleData;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class CombinedReportServiceTest {

    @TempDir
    Path dir;

    private final FakeEngine engine = new FakeEngine();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final BlockingQueue<JobEvent> events = new LinkedBlockingQueue<>();
    private CombinedReportService service;

    @AfterEach
    void stop() {
        if (service != null) {
            service.close();
        }
        scheduler.shutdown();
    }

    @Test
    void reportsProgressAndMergesSectionsInOriginalOrder() throws Exception {
        engine.delay = Duration.ofMillis(20);
        ReportJob job = service(Duration.ofSeconds(10)).start(3, 3, events::add);

        List<JobEvent> received = awaitTerminal();

        assertThat(received.getFirst()).isEqualTo(new JobEvent.Queued(job.id(), 4));
        assertThat(received).filteredOn(JobEvent.Rendering.class::isInstance)
                .extracting(e -> ((JobEvent.Rendering) e).completed())
                .containsExactly(1, 2, 3, 4);
        assertThat(received.get(received.size() - 2)).isEqualTo(new JobEvent.Merging(4));
        JobEvent.Done done = (JobEvent.Done) received.getLast();
        assertThat(done.downloadUrl()).isEqualTo("/api/combined-reports/" + job.id() + "/pdf");
        job.cancel("client disconnected after the last event");
        assertThat(job.cancelReason()).as("cancel after the final event is ignored").isNull();

        String merged = Files.readString(dir.resolve(job.id() + ".pdf"));
        assertThat(merged.lines()).containsExactly(
                "Portfolio summary 2026-09",
                "Account statement XX00 DEMO 0000 0001",
                "Account statement XX00 DEMO 0000 0002",
                "Account statement XX00 DEMO 0000 0003");
        assertThat(Files.list(dir)).as("work directory removed").containsExactly(dir.resolve(job.id() + ".pdf"));
    }

    @Test
    void neverExceedsTheConcurrencyLimit() throws Exception {
        engine.delay = Duration.ofMillis(30);
        service(Duration.ofSeconds(10)).start(11, 3, events::add);

        assertThat(awaitTerminal().getLast()).isInstanceOf(JobEvent.Done.class);
        assertThat(engine.maxInFlight.get()).isEqualTo(3);
    }

    @Test
    void failingSectionFailsTheJob() throws Exception {
        engine.delay = Duration.ofMillis(10);
        engine.failOn = "0000 0002";
        service(Duration.ofSeconds(10)).start(5, 2, events::add);

        List<JobEvent> received = awaitTerminal();

        assertThat(received).noneMatch(JobEvent.Done.class::isInstance);
        assertThat(((JobEvent.Failed) received.getLast()).reason()).contains("answered 400");
        assertThat(meters.get("reports.combined").tag("outcome", "failed").timer().count()).isEqualTo(1);
    }

    @Test
    void timeoutCancelsInFlightConversions() throws Exception {
        engine.delay = Duration.ofSeconds(30);
        service(Duration.ofMillis(300)).start(4, 2, events::add);

        JobEvent last = awaitTerminal().getLast();

        assertThat(last).isEqualTo(new JobEvent.Failed("timed out after 0.3 s"));
        assertThat(engine.interrupted.get()).isEqualTo(2);
    }

    @Test
    void failingListenerMeansClientLeftAndCancelsTheJob() throws Exception {
        engine.delay = Duration.ofMillis(200);
        Consumer<JobEvent> listener = event -> {
            if (event instanceof JobEvent.Rendering) {
                throw new UncheckedIOException(new IOException("Broken pipe"));
            }
            events.add(event);
        };
        service(Duration.ofSeconds(10)).start(20, 1, listener);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (meters.find("reports.combined").timer() == null && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(meters.get("reports.combined").tag("outcome", "cancelled").timer().count()).isEqualTo(1);
        assertThat(engine.calls.get()).as("remaining sections were not converted").isLessThan(5);
        assertThat(events).hasSize(1).first().isInstanceOf(JobEvent.Queued.class);
    }

    private CombinedReportService service(Duration timeout) {
        scheduler.initialize();
        JobProperties properties = new JobProperties(4, 8, 200, timeout, Duration.ofSeconds(5), Duration.ofMinutes(5));
        service = new CombinedReportService(new SampleData(), TestRenderers.renderer(), engine,
                new ResultStore(dir, properties, Clock.systemUTC()), properties, scheduler, meters);
        return service;
    }

    private List<JobEvent> awaitTerminal() throws InterruptedException {
        List<JobEvent> received = new ArrayList<>();
        while (true) {
            JobEvent event = events.poll(10, TimeUnit.SECONDS);
            assertThat(event).as("next event").isNotNull();
            received.add(event);
            if (event.terminal()) {
                return received;
            }
        }
    }

    /** Pretends to be Gotenberg: a "PDF" is the document title, a merge concatenates them. */
    static final class FakeEngine implements PdfEngine {

        private static final Pattern TITLE = Pattern.compile("<title>([^<]+)</title>");

        volatile Duration delay = Duration.ZERO;
        volatile String failOn;
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final AtomicInteger interrupted = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public PdfStream convertHtml(HtmlDocument document) {
            calls.incrementAndGet();
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(delay);
                Matcher title = TITLE.matcher(document.indexHtml());
                String text = title.find() ? title.group(1) : "?";
                if (failOn != null && text.contains(failOn)) {
                    throw new GotenbergException(400, "Gotenberg answered 400 on /forms/chromium/convert/html: bad", null);
                }
                return stream((text + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                interrupted.incrementAndGet();
                Thread.currentThread().interrupt();
                throw new GotenbergException(0, "interrupted", e);
            } finally {
                inFlight.decrementAndGet();
            }
        }

        @Override
        public PdfStream merge(List<Path> pdfs) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Path pdf : pdfs) {
                try {
                    out.write(Files.readAllBytes(pdf));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return stream(out.toByteArray());
        }

        private static PdfStream stream(byte[] bytes) {
            return new PdfStream(new ByteArrayInputStream(bytes), bytes.length, () -> { });
        }
    }
}
