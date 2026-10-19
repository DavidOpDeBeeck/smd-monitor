package app.dodb.smd.monitor.json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

// The version of the wire format and the build of the page the hub serves (W6). Every frame and answer names both; a page that differs in
// either reloads. The page's build writes its ID beside it; without one, as when the page is served by its development server, the hub names
// the development build, which a page ignores.
public final class Protocol {

    public static final int VERSION = 1;
    public static final String DEVELOPMENT = "development";
    private static final String BUILD_ID = "smd-monitor/build-id";
    private static final String BUILD = readBuild();

    private Protocol() {
    }

    public static String build() {
        return BUILD;
    }

    private static String readBuild() {
        try (var stream = Protocol.class.getClassLoader().getResourceAsStream(BUILD_ID)) {
            return stream == null ? DEVELOPMENT : new String(stream.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException failure) {
            throw new UncheckedIOException("Unable to read the build ID of the SMD monitor page from " + BUILD_ID, failure);
        }
    }
}
