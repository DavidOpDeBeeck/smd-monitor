package app.dodb.smd.monitor.hub.web;

import java.net.URI;
import java.util.List;

// Which pages may open the WebSocket: a client that names no origin, such as one that is no browser; the page's own origin, at the host and port
// the request names in its Host header; and the listed ones, such as the page's behind a proxy that rewrites the Host header. Pages of other sites
// cannot read the frames.
public final class Origins {

    private Origins() {
    }

    public static boolean allowed(String origin, String host, List<String> allowedOrigins) {
        if (origin == null || allowedOrigins.contains(origin)) {
            return true;
        }
        return host != null && host.equalsIgnoreCase(authority(origin));
    }

    private static String authority(String origin) {
        try {
            return URI.create(origin).getRawAuthority();
        } catch (IllegalArgumentException unreadable) {
            return null;
        }
    }
}
