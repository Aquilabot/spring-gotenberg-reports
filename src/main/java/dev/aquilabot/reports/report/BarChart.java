package dev.aquilabot.reports.report;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Geometry for a horizontal SVG bar chart, one bar per labelled value. */
public record BarChart(int width, int height, List<Bar> bars) {

    private static final int LABEL_WIDTH = 150;
    private static final int VALUE_WIDTH = 90;
    private static final int ROW = 18;

    public record Bar(String label, String value, double y, double length) {
    }

    public static BarChart of(List<String> labels, List<BigDecimal> values, int width) {
        double max = values.stream().mapToDouble(BigDecimal::doubleValue).max().orElse(1);
        double room = width - LABEL_WIDTH - VALUE_WIDTH;
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            double length = max <= 0 ? 0 : Math.max(0, room * values.get(i).doubleValue() / max);
            bars.add(new Bar(labels.get(i), Formats.INSTANCE.money(values.get(i)), i * ROW, length));
        }
        return new BarChart(width, values.size() * ROW, bars);
    }

    public int labelWidth() {
        return LABEL_WIDTH;
    }
}
