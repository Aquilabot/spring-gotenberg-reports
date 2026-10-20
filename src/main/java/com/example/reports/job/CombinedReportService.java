package com.example.reports.job;

import com.example.reports.gotenberg.PdfEngine;
import com.example.reports.gotenberg.PdfStream;
import com.example.reports.report.Report;
import com.example.reports.report.ReportRenderer;
import com.example.reports.sample.PortfolioSummary;
import com.example.reports.sample.SampleData;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

/**
 * Builds one PDF out of a portfolio summary plus one statement per account.
 *
 * <p>Every section is rendered and converted on its own virtual thread; a semaphore caps how many
 * talk to Gotenberg at once. Sections finish in any order, the merge keeps the original order.
 * The job is cancelled when its listener fails (the browser went away), when {@link ReportJob#cancel}
 * is called, or when {@link JobProperties#timeout()} expires. Cancelling interrupts the threads, which
 * aborts in-flight HTTP calls.
 */
@Service
public class CombinedReportService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CombinedReportService.class);

    private final SampleData data;
    private final ReportRenderer renderer;
    private final PdfEngine engine;
    private final ResultStore store;
    private final JobProperties properties;
    private final TaskScheduler scheduler;
    private final MeterRegistry meters;
    private final ExecutorService jobs = Executors.newVirtualThreadPerTaskExecutor();

    public CombinedReportService(SampleData data, ReportRenderer renderer, PdfEngine engine, ResultStore store,
                                 JobProperties properties, TaskScheduler scheduler, MeterRegistry meters) {
        this.data = data;
        this.renderer = renderer;
        this.engine = engine;
        this.store = store;
        this.properties = properties;
        this.scheduler = scheduler;
        this.meters = meters;
    }

    /**
     * Starts a job and returns at once. {@code listener} receives every {@link JobEvent}; it is
     * called from job threads, one event at a time. If it throws, the job is cancelled.
     */
    public ReportJob start(int accounts, int concurrency, Consumer<JobEvent> listener) {
        if (accounts < 1 || accounts > properties.maxSections()) {
            throw new IllegalArgumentException("accounts must be between 1 and " + properties.maxSections());
        }
        if (concurrency < 1 || concurrency > properties.maxConcurrency()) {
            throw new IllegalArgumentException("concurrency must be between 1 and " + properties.maxConcurrency());
        }
        ReportJob job = new ReportJob(UUID.randomUUID().toString());
        Run run = new Run(job, accounts, concurrency, listener);
        run.emit(new JobEvent.Queued(job.id(), accounts + 1));
        ScheduledFuture<?> timeout = scheduler.schedule(
                () -> job.cancel("timed out after %.1f s".formatted(properties.timeout().toMillis() / 1000.0)),
                Instant.now().plus(properties.timeout()));
        job.attach(jobs.submit(() -> run.execute(timeout)));
        return job;
    }

    @Override
    public void close() {
        jobs.shutdownNow();
    }

    /** State of one job execution. */
    private final class Run {

        private final ReportJob job;
        private final int accounts;
        private final int concurrency;
        private final Consumer<JobEvent> listener;
        private volatile boolean listenerGone;

        Run(ReportJob job, int accounts, int concurrency, Consumer<JobEvent> listener) {
            this.job = job;
            this.accounts = accounts;
            this.concurrency = concurrency;
            this.listener = listener;
        }

        void execute(ScheduledFuture<?> timeout) {
            long started = System.nanoTime();
            String outcome = "success";
            Path work = null;
            try (var ignored = MDC.putCloseable("jobId", job.id())) {
                log.atInfo().addKeyValue("accounts", accounts).addKeyValue("concurrency", concurrency)
                        .log("Combined report started");
                try {
                    work = store.workDirectory(job.id());
                    List<Path> parts = renderSections(work);
                    emit(new JobEvent.Merging(parts.size()));
                    Path merged = work.resolve("merged.pdf");
                    long bytes;
                    try (PdfStream pdf = engine.merge(parts)) {
                        bytes = pdf.saveTo(merged);
                    }
                    Files.move(merged, store.resultFile(job.id()), StandardCopyOption.REPLACE_EXISTING);
                    emit(new JobEvent.Done("/api/combined-reports/" + job.id() + "/pdf", bytes, millisSince(started)));
                } catch (Exception e) {
                    // An interrupt surfaces as InterruptedException, IOException or a wrapped HTTP error,
                    // so the cancel reason is the reliable signal.
                    Thread.interrupted();
                    String reason = job.cancelReason();
                    outcome = reason == null ? "failed" : "cancelled";
                    if (reason == null) {
                        log.atError().setCause(e instanceof ExecutionException ? e.getCause() : e)
                                .log("Combined report failed");
                    }
                    emit(new JobEvent.Failed(reason != null ? reason : describe(e)));
                } finally {
                    timeout.cancel(false);
                    if (work != null) {
                        ResultStore.deleteRecursively(work);
                    }
                    Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
                    Timer.builder("reports.combined").description("Combined report jobs, end to end")
                            .tag("outcome", outcome).register(meters).record(elapsed);
                    log.atInfo().addKeyValue("outcome", outcome).addKeyValue("elapsedMs", elapsed.toMillis())
                            .addKeyValue("reason", job.cancelReason() == null ? "" : job.cancelReason())
                            .log("Combined report finished");
                }
            }
        }

        private List<Path> renderSections(Path work) throws InterruptedException, ExecutionException {
            PortfolioSummary summary = data.summary(accounts);
            List<Report> sections = new ArrayList<>(accounts + 1);
            sections.add(summary);
            sections.addAll(summary.statements());

            Semaphore permits = new Semaphore(concurrency);
            Path[] parts = new Path[sections.size()];
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                CompletionService<Integer> completion = new ExecutorCompletionService<>(pool);
                List<Future<Integer>> futures = new ArrayList<>();
                for (int i = 0; i < sections.size(); i++) {
                    int index = i;
                    futures.add(completion.submit(() -> {
                        permits.acquire();
                        try (var ignored = MDC.putCloseable("jobId", job.id())) {
                            parts[index] = convert(sections.get(index), work.resolve("%05d.pdf".formatted(index)));
                            return index;
                        } finally {
                            permits.release();
                        }
                    }));
                }
                try {
                    for (int completed = 1; completed <= sections.size(); completed++) {
                        int index = completion.take().get();
                        emit(new JobEvent.Rendering(completed, sections.size(), sections.get(index).title()));
                    }
                } finally {
                    futures.forEach(f -> f.cancel(true));
                }
            }
            return Arrays.asList(parts);
        }

        private Path convert(Report report, Path target) throws IOException {
            long started = System.nanoTime();
            try (PdfStream pdf = engine.convertHtml(renderer.render(report))) {
                pdf.saveTo(target);
            }
            log.atDebug().addKeyValue("section", report.title()).addKeyValue("elapsedMs", millisSince(started))
                    .log("Section rendered");
            return target;
        }

        void emit(JobEvent event) {
            if (listenerGone) {
                return;
            }
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                listenerGone = true;
                log.atInfo().addKeyValue("event", event.name())
                        .log("Listener gone, cancelling job");
                job.cancel("client disconnected");
            }
        }
    }

    private static long millisSince(long nanos) {
        return Duration.ofNanos(System.nanoTime() - nanos).toMillis();
    }

    private static String describe(Exception e) {
        Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
