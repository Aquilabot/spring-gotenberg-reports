package dev.aquilabot.reports.sample;

import dev.aquilabot.reports.report.BarChart;
import dev.aquilabot.reports.report.Report;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/** Cover page of the combined report: one row per account. */
public record PortfolioSummary(Party bank, YearMonth period, List<Statement> statements) implements Report {

    public BigDecimal totalClosing() {
        return statements.stream().map(Statement::closingBalance).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public int transactionCount() {
        return statements.stream().mapToInt(s -> s.transactions().size()).sum();
    }

    public BarChart closingChart() {
        return BarChart.of(
                statements.stream().map(s -> s.holder().name()).toList(),
                statements.stream().map(Statement::closingBalance).toList(),
                640);
    }

    @Override
    public String template() {
        return "portfolio-summary";
    }

    @Override
    public String title() {
        return "Portfolio summary " + period;
    }

    @Override
    public String filename() {
        return "portfolio-summary-" + period + ".pdf";
    }
}
