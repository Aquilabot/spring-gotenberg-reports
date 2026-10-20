package com.example.reports.web;

import com.example.reports.job.CombinedReportService;
import com.example.reports.job.JobEvent;
import com.example.reports.job.JobProperties;
import com.example.reports.job.ReportJob;
import com.example.reports.job.ResultStore;
import com.example.reports.sample.SampleData;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/combined-reports")
class CombinedReportController {

    private final CombinedReportService service;
    private final ResultStore store;
    private final JobProperties properties;
    private final TaskScheduler scheduler;

    CombinedReportController(CombinedReportService service, ResultStore store, JobProperties properties,
                             TaskScheduler scheduler) {
        this.service = service;
        this.store = store;
        this.properties = properties;
        this.scheduler = scheduler;
    }

    /**
     * Starts a job and streams its progress. Closing the connection cancels the job: the servlet
     * container only notices a gone client when a write fails, hence the periodic keep-alive comment.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@RequestParam(defaultValue = "12") int accounts, @RequestParam(required = false) Integer concurrency) {
        SseEmitter emitter = new SseEmitter(properties.timeout().plus(Duration.ofSeconds(30)).toMillis());
        ReportJob job = service.start(accounts, concurrency == null ? properties.concurrency() : concurrency,
                event -> send(emitter, event));

        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException e) {
                job.cancel("client disconnected");
            }
        }, Instant.now().plus(properties.heartbeat()), properties.heartbeat());
        Runnable cleanup = () -> {
            heartbeat.cancel(false);
            job.cancel("client disconnected");
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        return emitter;
    }

    @GetMapping("/{jobId}/pdf")
    ResponseEntity<Resource> download(@PathVariable String jobId) {
        return store.find(jobId)
                .map(file -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                                .filename("combined-report-" + SampleData.PERIOD + ".pdf").build().toString())
                        .<Resource>body(new FileSystemResource(file)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No finished report " + jobId));
    }

    private static void send(SseEmitter emitter, JobEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.name()).data(event, MediaType.APPLICATION_JSON));
            if (event.terminal()) {
                emitter.complete();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
