package nu.miguel.persona.content;

import java.util.regex.Pattern;

public final class Ids {
    private static final Pattern ID = Pattern.compile("[a-z0-9._-]+:[a-z0-9/._-]+");
    private Ids() {}
    public static String require(String value, String context) {
        if (value == null || !ID.matcher(value).matches())
            throw new IllegalArgumentException(context + " must be a lowercase namespaced ID");
        return value;
    }
}
