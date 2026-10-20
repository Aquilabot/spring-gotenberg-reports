package dev.aquilabot.reports.sample;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;

/**
 * Deterministic, obviously fake data. The same id always yields the same document, which keeps
 * tests and benchmarks repeatable. Names, companies and addresses are invented; e-mail addresses
 * use the reserved {@code .example} top-level domain.
 */
@Component
public class SampleData {

    public static final YearMonth PERIOD = YearMonth.of(2026, 9);

    static final Party BANK = new Party("Fictional Savings Bank", "1 Placeholder Plaza", "Sampleton ZZ 00000",
            "statements@bank.example");
    static final Party SELLER = new Party("Example Widgets Ltd.", "42 Demo Road", "Mockville ZZ 00042",
            "billing@widgets.example");

    private static final String[] FIRST = {"Avery", "Jordan", "Riley", "Morgan", "Casey", "Quinn", "Rowan", "Skyler"};
    private static final String[] LAST = {"Example", "Sample", "Placeholder", "Testcase", "Demo", "Mockley", "Fixture"};
    private static final String[] MERCHANTS = {
            "Corner Grocery (sample)", "Demo Power utility bill", "Bean Placeholder coffee", "Mock Transit pass",
            "Fixture Books", "Example Telecom", "Stub Pharmacy", "Lorem Ipsum Restaurant", "ATM withdrawal"};
    private static final String[][] PRODUCTS = {
            {"WID-100", "Standard widget"}, {"WID-200", "Deluxe widget"}, {"GAD-310", "Gadget adapter"},
            {"SRV-001", "Installation service (hours)"}, {"SUP-12", "Support plan, monthly"},
            {"CAB-050", "Connector cable 0.5 m"}, {"KIT-900", "Starter kit"}};

    public Invoice invoice(int number) {
        Random random = new Random(1_000_003L * number);
        int lineCount = 6 + random.nextInt(18);
        List<InvoiceLine> lines = new ArrayList<>();
        for (int i = 0; i < lineCount; i++) {
            String[] product = PRODUCTS[random.nextInt(PRODUCTS.length)];
            lines.add(new InvoiceLine(product[0], product[1], 1 + random.nextInt(20), money(5 + random.nextInt(400), random)));
        }
        LocalDate issued = PERIOD.atDay(1 + random.nextInt(28));
        return new Invoice("INV-2026-%04d".formatted(number), issued, issued.plusDays(30), SELLER,
                person(random, number), lines, new BigDecimal("0.18"));
    }

    public Statement statement(int account) {
        Random random = new Random(7_919L * account);
        BigDecimal balance = money(4_000 + random.nextInt(8_000), random);
        BigDecimal opening = balance;
        int count = 40 + random.nextInt(80);
        List<Transaction> transactions = new ArrayList<>(count + 1);
        List<Integer> days = IntStream.range(0, count).map(i -> 1 + random.nextInt(PERIOD.lengthOfMonth())).sorted().boxed().toList();
        boolean salaryPaid = false;
        for (int i = 0; i < count; i++) {
            int day = days.get(i);
            if (!salaryPaid && day >= 25) {
                balance = balance.add(new BigDecimal("3250.00"));
                transactions.add(new Transaction(PERIOD.atDay(25), "Salary - Example Corp (fictional)", "SAL-" + account, new BigDecimal("3250.00"), balance));
                salaryPaid = true;
            }
            BigDecimal amount = money(3 + random.nextInt(140), random).negate();
            balance = balance.add(amount);
            String merchant = MERCHANTS[random.nextInt(MERCHANTS.length)];
            transactions.add(new Transaction(PERIOD.atDay(day), merchant, "TX%06d".formatted(random.nextInt(1_000_000)), amount, balance));
        }
        return new Statement(accountNumber(account), person(random, account), BANK, PERIOD, opening, transactions);
    }

    public PortfolioSummary summary(int accounts) {
        return new PortfolioSummary(BANK, PERIOD, IntStream.rangeClosed(1, accounts).mapToObj(this::statement).toList());
    }

    public static String accountNumber(int account) {
        return "XX00 DEMO %04d %04d".formatted(account / 10_000, account % 10_000);
    }

    private static Party person(Random random, int id) {
        String name = FIRST[random.nextInt(FIRST.length)] + " " + LAST[random.nextInt(LAST.length)];
        String handle = name.toLowerCase().replace(' ', '.') + id;
        return new Party(name, (10 + random.nextInt(890)) + " Example Street", "Sampletown ZZ %05d".formatted(id), handle + "@mail.example");
    }

    private static BigDecimal money(int whole, Random random) {
        return BigDecimal.valueOf(whole * 100L + random.nextInt(100), 2).setScale(2, RoundingMode.UNNECESSARY);
    }
}
