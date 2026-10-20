package dev.aquilabot.reports.gotenberg;

import java.util.List;

/**
 * Everything Chromium needs to print one document: the main page, optional running header and
 * footer pages, and the files the main page links to (stylesheets, fonts, images).
 *
 * <p>Gotenberg puts all uploaded files in one flat directory, so the HTML must reference assets
 * by bare file name, e.g. {@code href="report.css"}.
 */
public record HtmlDocument(String indexHtml, String headerHtml, String footerHtml, List<Asset> assets) {

    public HtmlDocument {
        assets = List.copyOf(assets);
    }

    public record Asset(String filename, byte[] content) {
    }
}
