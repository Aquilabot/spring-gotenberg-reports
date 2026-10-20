package com.example.reports.report;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Geometry for a small SVG line chart. The template only places the strings computed here, so the
 * chart needs no JavaScript and prints the same in every browser.
 */
public record LineChart(int width, int height, String line, String area, List<Label> yLabels, List<Label> xLabels) {

    private static final int LEFT = 64;
    private static final int RIGHT = 8;
    private static final int TOP = 8;
    private static final int BOTTOM = 22;

    public record Label(double x, double y, String text) {
    }

    /**
     * @param values  y values, evenly spaced along the x axis
     * @param xLabels label text for the first, middle and last point
     */
    public static LineChart of(List<BigDecimal> values, List<String> xLabels, int width, int height) {
        if (values.size() < 2) {
            throw new IllegalArgumentException("A line chart needs at least two points");
        }
        double min = values.stream().mapToDouble(BigDecimal::doubleValue).min().orElseThrow();
        double max = values.stream().mapToDouble(BigDecimal::doubleValue).max().orElseThrow();
        double span = max - min == 0 ? 1 : max - min;
        double plotWidth = width - LEFT - RIGHT;
        double plotHeight = height - TOP - BOTTOM;
        double baseline = TOP + plotHeight;

        StringBuilder line = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            double x = LEFT + plotWidth * i / (values.size() - 1);
            double y = TOP + plotHeight * (max - values.get(i).doubleValue()) / span;
            line.append(i == 0 ? "" : " ").append(fmt(x)).append(',').append(fmt(y));
        }
        String area = fmt(LEFT) + "," + fmt(baseline) + " " + line + " " + fmt(LEFT + plotWidth) + "," + fmt(baseline);

        List<Label> y = new ArrayList<>();
        for (int i = 0; i <= 2; i++) {
            double value = max - span * i / 2;
            y.add(new Label(LEFT - 6, TOP + plotHeight * i / 2 + 3, Formats.INSTANCE.money(BigDecimal.valueOf(value))));
        }
        List<Label> x = new ArrayList<>();
        for (int i = 0; i < xLabels.size(); i++) {
            double position = xLabels.size() == 1 ? 0 : (double) i / (xLabels.size() - 1);
            x.add(new Label(LEFT + plotWidth * position, height - 6, xLabels.get(i)));
        }
        return new LineChart(width, height, line.toString(), area, y, x);
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
