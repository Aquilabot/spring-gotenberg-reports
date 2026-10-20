package dev.aquilabot.reports.report;

/**
 * A document that can be rendered to PDF. The record itself is the template model: templates read
 * it as {@code report}.
 */
public interface Report {

    /** Template name below {@code templates/reports/}, without the {@code .html} suffix. */
    String template();

    /** Human-readable title, used in the HTML title and the running header. */
    String title();

    /** File name offered when the PDF is downloaded. */
    String filename();
}
