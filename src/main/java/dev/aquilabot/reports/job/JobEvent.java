package dev.aquilabot.reports.job;

/**
 * Progress of a combined report job. Each event is sent to the browser as one Server-Sent Event
 * whose {@code event:} field is {@link #name()} and whose {@code data:} field is the record as JSON.
 */
public sealed interface JobEvent {

    String name();

    /** The job was accepted; {@code sections} PDFs will be rendered. */
    record Queued(String jobId, int sections) implements JobEvent {
        public String name() {
            return "queued";
        }
    }

    /** {@code completed} of {@code total} sections are rendered; {@code section} just finished. */
    record Rendering(int completed, int total, String section) implements JobEvent {
        public String name() {
            return "rendering";
        }
    }

    /** All sections are rendered and Gotenberg is merging them. */
    record Merging(int sections) implements JobEvent {
        public String name() {
            return "merging";
        }
    }

    /** The merged PDF can be downloaded from {@code downloadUrl}. */
    record Done(String downloadUrl, long bytes, long elapsedMillis) implements JobEvent {
        public String name() {
            return "done";
        }
    }

    /**
     * The job stopped without a PDF. Named {@code failed} rather than {@code error} because
     * EventSource already uses {@code error} for connection problems.
     */
    record Failed(String reason) implements JobEvent {
        public String name() {
            return "failed";
        }
    }

    default boolean terminal() {
        return this instanceof Done || this instanceof Failed;
    }
}
