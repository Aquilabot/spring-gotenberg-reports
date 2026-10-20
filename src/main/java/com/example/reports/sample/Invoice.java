package com.example.reports.sample;

import com.example.reports.report.Report;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

public record Invoice(String number, LocalDate issued, LocalDate due, Party seller, Party customer,
                      List<InvoiceLine> lines, BigDecimal taxRate) implements Report {

    public BigDecimal subtotal() {
        return lines.stream().map(InvoiceLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal tax() {
        return subtotal().multiply(taxRate).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal total() {
        return subtotal().add(tax());
    }

    public String taxPercent() {
        return taxRate.movePointRight(2).stripTrailingZeros().toPlainString() + "%";
    }

    @Override
    public String template() {
        return "invoice";
    }

    @Override
    public String title() {
        return "Invoice " + number;
    }

    @Override
    public String filename() {
        return "invoice-" + number + ".pdf";
    }
}
