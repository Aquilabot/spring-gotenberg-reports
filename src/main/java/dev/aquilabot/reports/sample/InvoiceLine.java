package dev.aquilabot.reports.sample;

import java.math.BigDecimal;

public record InvoiceLine(String sku, String description, int quantity, BigDecimal unitPrice) {

    public BigDecimal amount() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
