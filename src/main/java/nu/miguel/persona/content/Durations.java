package nu.miguel.persona.content;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Durations {
    private static final Pattern SIMPLE = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(ms|s|m|h)");
    private Durations() {}
    public static Duration parse(Object raw) {
        if (raw instanceof Number n) return Duration.ofMillis(Math.round(n.doubleValue() * 1000));
        Matcher m = SIMPLE.matcher(String.valueOf(raw));
        if (!m.matches()) throw new IllegalArgumentException("invalid duration '" + raw + "'");
        double number = Double.parseDouble(m.group(1));
        double millis = number * switch (m.group(2)) { case "ms" -> 1; case "s" -> 1000;
            case "m" -> 60_000; case "h" -> 3_600_000; default -> throw new AssertionError(); };
        return Duration.ofMillis(Math.round(millis));
    }
}
