package com.example.reports.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.reports.gotenberg.HtmlDocument;
import com.example.reports.sample.Invoice;
import com.example.reports.sample.SampleData;
import com.example.reports.sample.Statement;
import org.junit.jupiter.api.Test;

class ReportRendererTest {

    private final ReportRenderer renderer = TestRenderers.renderer();
    private final SampleData data = new SampleData();

    @Test
    void invoiceContainsCustomerLinesAndTotal() {
        Invoice invoice = data.invoice(7);

        HtmlDocument document = renderer.render(invoice);

        assertThat(document.indexHtml())
                .contains(invoice.customer().name())
                .contains(Formats.INSTANCE.money(invoice.total()))
                .contains("href=\"report.css\"")
                .doesNotContain("<base");
        assertThat(document.indexHtml().split("<tr>", -1)).hasSizeGreaterThan(invoice.lines().size());
    }

    @Test
    void headerAndFooterCarryTitleAndPageNumberPlaceholders() {
        HtmlDocument document = renderer.render(data.statement(3));

        assertThat(document.headerHtml()).contains("Account statement XX00 DEMO 0000 0003");
        assertThat(document.footerHtml()).contains("class=\"pageNumber\"", "class=\"totalPages\"");
    }

    @Test
    void statementEmbedsSvgChartAndShipsFontsAndStylesheet() {
        Statement statement = data.statement(3);

        HtmlDocument document = renderer.render(statement);

        assertThat(document.indexHtml()).contains("<svg", "<polyline class=\"line\" points=\"");
        assertThat(document.assets()).extracting(HtmlDocument.Asset::filename)
                .containsExactly("NotoSans-Bold.woff2", "NotoSans-Regular.woff2", "report.css");
    }

    @Test
    void previewAddsBaseHrefForBrowsers() {
        assertThat(renderer.html(data.invoice(1), "/report-assets/")).contains("<base href=\"/report-assets/\">");
    }
}
