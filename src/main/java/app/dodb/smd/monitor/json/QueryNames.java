package app.dodb.smd.monitor.json;

import app.dodb.smd.monitor.hub.ApplicationType;
import app.dodb.smd.monitor.hub.FailureCause;
import app.dodb.smd.monitor.hub.TraceOrder;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.MessageKind;
import app.dodb.smd.monitor.hub.span.MessageType;
import app.dodb.smd.monitor.hub.span.Outcome;
import app.dodb.smd.monitor.hub.span.Stage;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

// What the page names in its queries, as frontend/src/lib/format.ts composes them: a type as application::KIND:class, with the application
// percent-encoded so that it holds no ':', '|', or '@'; a failure cause as a type|failure type|dispatch, or |handling:processing group for an
// event; a kind, a status, and a trace order by the constant's name. A name the hub cannot read fails with an IllegalArgumentException stating
// the expected form.
public final class QueryNames {

    private QueryNames() {
    }

    public static ApplicationType type(String type) {
        var application = type.indexOf("::");
        var kind = type.indexOf(':', application + 2);
        if (application < 1 || kind < 0) {
            throw new IllegalArgumentException("A type is named as application::KIND:class, such as tickets::COMMAND:app.OpenTicket; got '" + type + "'");
        }
        return new ApplicationType(new ApplicationName(decoded(type.substring(0, application), type)),
            new MessageType(kind(type.substring(application + 2, kind)), type.substring(kind + 1)));
    }

    public static FailureCause.Step failureCause(String cause) {
        var type = cause.indexOf('|');
        var stage = cause.indexOf('|', type + 1);
        if (type < 0 || stage < 0) {
            throw new IllegalArgumentException("A failure cause is named as application::KIND:class|failure type|dispatch or |handling:group, "
                + "such as tickets::COMMAND:app.OpenTicket|java.lang.IllegalStateException|dispatch; got '" + cause + "'");
        }
        var applicationType = type(cause.substring(0, type));
        var failureType = cause.substring(type + 1, stage);
        var named = cause.substring(stage + 1);
        Stage parsed;
        if (named.equals("dispatch")) {
            parsed = new Stage.Dispatch(applicationType.type());
        } else if (named.startsWith("handling:") && applicationType.type().kind() == MessageKind.EVENT) {
            parsed = new Stage.Handling(applicationType.type(), named.substring("handling:".length()));
        } else {
            throw new IllegalArgumentException("A failure cause names its stage as dispatch, or as handling:group for an event; got '" + named + "' in '"
                + cause + "'");
        }
        return new FailureCause.Step(applicationType.application(), parsed, failureType);
    }

    public static MessageKind kind(String kind) {
        return constant(MessageKind.class, kind, "kind");
    }

    public static Outcome.Status status(String status) {
        return constant(Outcome.Status.class, status, "status");
    }

    public static TraceOrder order(String order) {
        return constant(TraceOrder.class, order.toUpperCase(Locale.ROOT), "trace order");
    }

    // As encodeURIComponent encodes, which writes a '+' as %2B, so a '+' is itself rather than a space.
    private static String decoded(String application, String type) {
        try {
            return URLDecoder.decode(application.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("A type names its application percent-encoded, such as Order%20Service::COMMAND:app.PlaceOrder; got '" + type
                + "'", invalid);
        }
    }

    private static <E extends Enum<E>> E constant(Class<E> type, String name, String meaning) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Unknown " + meaning + " '" + name + "'; expected one of " + List.of(type.getEnumConstants()), failure);
        }
    }
}
