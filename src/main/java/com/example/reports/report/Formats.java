package com.example.reports.report;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Formatting helpers exposed to templates as {@code fmt}. One fixed locale keeps the output stable,
 * which matters when tests assert on text extracted from the PDF.
 */
public final class Formats {

    public static final Formats INSTANCE = new Formats();

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US);

    private Formats() {
    }

    public String money(BigDecimal amount) {
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        return format.format(amount);
    }

    public String date(LocalDate date) {
        return DATE.format(date);
    }

    public String month(YearMonth month) {
        return MONTH.format(month);
    }
}
