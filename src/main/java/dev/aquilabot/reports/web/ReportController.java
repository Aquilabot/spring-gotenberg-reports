package dev.aquilabot.reports.web;

import dev.aquilabot.reports.gotenberg.PdfEngine;
import dev.aquilabot.reports.gotenberg.PdfStream;
import dev.aquilabot.reports.report.Report;
import dev.aquilabot.reports.report.ReportRenderer;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
class ReportController {

    private final SampleReports reports;
    private final ReportRenderer renderer;
    private final PdfEngine engine;

    ReportController(SampleReports reports, ReportRenderer renderer, PdfEngine engine) {
        this.reports = reports;
        this.renderer = renderer;
        this.engine = engine;
    }

    /**
     * Streams one PDF straight from Gotenberg to the client. Gotenberg is called before the response
     * is committed, so a failed conversion still turns into a proper error response.
     */
    @GetMapping("/api/reports/{type}/{id}")
    ResponseEntity<StreamingResponseBody> pdf(@PathVariable String type, @PathVariable int id,
                                              @RequestParam(defaultValue = "false") boolean download) {
        Report report = reports.find(type, id);
        PdfStream pdf = engine.convertHtml(renderer.render(report));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition((download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(report.filename()).build());
        headers.setCacheControl(CacheControl.noStore());
        if (pdf.contentLength() >= 0) {
            headers.setContentLength(pdf.contentLength());
        }
        return ResponseEntity.ok().headers(headers).body(pdf::transferTo);
    }

    /** The HTML Gotenberg would print, for working on templates in a normal browser. */
    @GetMapping(path = "/preview/{type}/{id}", produces = MediaType.TEXT_HTML_VALUE)
    String preview(@PathVariable String type, @PathVariable int id) {
        return renderer.html(reports.find(type, id), "/report-assets/");
    }
}
