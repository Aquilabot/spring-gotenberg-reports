package com.example.reports.web;

import com.example.reports.report.Report;
import com.example.reports.sample.SampleData;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Maps the {@code {type}/{id}} URL segments to a sample report. */
@Component
class SampleReports {

    private final SampleData data;

    SampleReports(SampleData data) {
        this.data = data;
    }

    Report find(String type, int id) {
        if (id < 1 || id > 9_999) {
            throw new IllegalArgumentException("id must be between 1 and 9999");
        }
        return switch (type) {
            case "invoice" -> data.invoice(id);
            case "statement" -> data.statement(id);
            case "portfolio-summary" -> data.summary(Math.min(id, 200));
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown report type: " + type);
        };
    }
}
