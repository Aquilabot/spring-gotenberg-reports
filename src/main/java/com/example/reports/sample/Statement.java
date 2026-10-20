package com.example.reports.sample;

import com.example.reports.report.Formats;
import com.example.reports.report.LineChart;
import com.example.reports.report.Report;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

public record Statement(String accountNumber, Party holder, Party bank, YearMonth period,
                        BigDecimal openingBalance, List<Transaction> transactions) implements Report {

    public BigDecimal closingBalance() {
        return transactions.isEmpty() ? openingBalance : transactions.getLast().balance();
    }

    public BigDecimal credits() {
        return transactions.stream().map(Transaction::amount).filter(a -> a.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal debits() {
        return transactions.stream().map(Transaction::amount).filter(a -> a.signum() < 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add).negate();
    }

    /** End-of-day balance for every day of the period. */
    public LineChart balanceChart() {
        List<BigDecimal> daily = new ArrayList<>();
        BigDecimal balance = openingBalance;
        int index = 0;
        for (int day = 1; day <= period.lengthOfMonth(); day++) {
            while (index < transactions.size() && transactions.get(index).date().getDayOfMonth() == day) {
                balance = transactions.get(index++).balance();
            }
            daily.add(balance);
        }
        Formats fmt = Formats.INSTANCE;
        List<String> labels = List.of(fmt.date(period.atDay(1)), fmt.date(period.atDay(15)), fmt.date(period.atEndOfMonth()));
        return LineChart.of(daily, labels, 640, 150);
    }

    @Override
    public String template() {
        return "statement";
    }

    @Override
    public String title() {
        return "Account statement " + accountNumber;
    }

    @Override
    public String filename() {
        return "statement-" + accountNumber.replace(" ", "") + "-" + period + ".pdf";
    }
}
