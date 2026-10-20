package com.example.reports.sample;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SampleDataTest {

    private final SampleData data = new SampleData();

    @Test
    void sameIdGivesSameDocument() {
        assertThat(data.statement(42)).isEqualTo(data.statement(42));
        assertThat(data.invoice(42)).isEqualTo(data.invoice(42));
    }

    @Test
    void runningBalanceAddsUp() {
        Statement statement = data.statement(5);

        BigDecimal sum = statement.transactions().stream().map(Transaction::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(statement.closingBalance()).isEqualByComparingTo(statement.openingBalance().add(sum));
        assertThat(statement.credits().subtract(statement.debits())).isEqualByComparingTo(sum);
    }

    @Test
    void invoiceTotalIncludesTax() {
        Invoice invoice = data.invoice(3);

        assertThat(invoice.total()).isEqualByComparingTo(invoice.subtotal().add(invoice.tax()));
        assertThat(invoice.taxPercent()).isEqualTo("18%");
    }

    @Test
    void chartHasOnePointPerDay() {
        Statement statement = data.statement(1);

        assertThat(statement.balanceChart().line().split(" ")).hasSize(statement.period().lengthOfMonth());
    }
}
