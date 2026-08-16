package nu.miguel.persona.content;

import java.util.List;

public final class ContentException extends Exception {
    private final List<String> errors;
    public ContentException(List<String> errors) {
        super(String.join(System.lineSeparator(), errors));
        this.errors = List.copyOf(errors);
    }
    public List<String> errors() { return errors; }
}
