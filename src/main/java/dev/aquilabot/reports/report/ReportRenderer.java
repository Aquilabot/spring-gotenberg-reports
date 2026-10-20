package dev.aquilabot.reports.report;

import dev.aquilabot.reports.gotenberg.HtmlDocument;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Turns a {@link Report} into the HTML files Gotenberg prints. Rendering is plain Thymeleaf
 * outside any web request, so it works the same in a controller, a background job or a test.
 */
@Component
public class ReportRenderer {

    static final String ASSETS = "classpath:report-assets/*";
    private static final List<String> ASSET_TYPES = List.of(".css", ".woff2", ".svg", ".png");

    private final ITemplateEngine templates;
    private final List<HtmlDocument.Asset> assets;

    public ReportRenderer(ITemplateEngine templates) {
        this.templates = templates;
        this.assets = loadAssets();
    }

    public HtmlDocument render(Report report) {
        return new HtmlDocument(
                html(report, null),
                templates.process("reports/page-header", context(report, null)),
                templates.process("reports/page-footer", context(report, null)),
                assets);
    }

    /** The main page only, with a {@code <base>} so a browser can load the assets from {@code assetBase}. */
    public String html(Report report, String assetBase) {
        return templates.process("reports/" + report.template(), context(report, assetBase));
    }

    private static Context context(Report report, String assetBase) {
        return new Context(Locale.US, Map.of(
                "report", report,
                "fmt", Formats.INSTANCE,
                "assetBase", assetBase == null ? "" : assetBase));
    }

    private static List<HtmlDocument.Asset> loadAssets() {
        try {
            List<HtmlDocument.Asset> loaded = new ArrayList<>();
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources(ASSETS)) {
                String name = resource.getFilename();
                if (name != null && ASSET_TYPES.stream().anyMatch(name::endsWith)) {
                    try (InputStream in = resource.getInputStream()) {
                        loaded.add(new HtmlDocument.Asset(name, in.readAllBytes()));
                    }
                }
            }
            loaded.sort(Comparator.comparing(HtmlDocument.Asset::filename));
            return List.copyOf(loaded);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load report assets", e);
        }
    }
}
