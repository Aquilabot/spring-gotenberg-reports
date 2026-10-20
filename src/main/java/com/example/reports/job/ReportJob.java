package com.example.reports.job;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/** Handle on a running combined report. Cancelling is idempotent and safe from any thread. */
public final class ReportJob {

    private final String id;
    private final AtomicReference<String> cancelReason = new AtomicReference<>();
    private volatile Future<?> execution;

    ReportJob(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** Stops the job; in-flight Gotenberg calls are interrupted. The first reason wins. */
    public void cancel(String reason) {
        if (cancelReason.compareAndSet(null, reason) && execution != null) {
            execution.cancel(true);
        }
    }

    public boolean isDone() {
        return execution != null && execution.isDone();
    }

    String cancelReason() {
        return cancelReason.get();
    }

    void attach(Future<?> execution) {
        this.execution = execution;
        if (cancelReason.get() != null) {
            execution.cancel(true);
        }
    }
}
