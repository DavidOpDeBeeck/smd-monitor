package app.dodb.smd.monitor.hub.catalog;

import app.dodb.smd.monitor.hub.span.Stage;

import static java.util.Objects.requireNonNull;

// The failures a stage raised with an exception type: every failed span counts under its own stage, whether it raised the failure or a child did.
public record RaisedFailure(Stage stage, String failureType) {

    public RaisedFailure {
        requireNonNull(stage);
        requireNonNull(failureType);
    }
}
