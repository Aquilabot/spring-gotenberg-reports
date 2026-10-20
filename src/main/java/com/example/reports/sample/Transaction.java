package com.example.reports.sample;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One statement line. {@code balance} is the running balance after this transaction. */
public record Transaction(LocalDate date, String description, String reference, BigDecimal amount, BigDecimal balance) {

    public boolean credit() {
        return amount.signum() > 0;
    }
}
