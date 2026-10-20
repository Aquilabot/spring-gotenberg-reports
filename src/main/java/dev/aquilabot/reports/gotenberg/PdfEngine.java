package dev.aquilabot.reports.gotenberg;

import java.nio.file.Path;
import java.util.List;

/** The two PDF operations the application needs. {@link GotenbergClient} is the real one. */
public interface PdfEngine {

    PdfStream convertHtml(HtmlDocument document);

    /** Merges the files in list order. */
    PdfStream merge(List<Path> pdfs);
}
