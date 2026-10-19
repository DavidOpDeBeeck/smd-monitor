package app.dodb.smd.monitor.hub.span;

import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

public record ApplicationName(String value) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    public ApplicationName {
        requireNonNull(value);
        if (!NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("ApplicationName requires 1 to 100 letters, digits, '.', '_', or '-', starting with a letter or digit; got '"
                + value + "'");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
