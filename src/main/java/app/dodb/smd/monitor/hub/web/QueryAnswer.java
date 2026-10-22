package app.dodb.smd.monitor.hub.web;

import app.dodb.smd.monitor.json.Protocol;

import java.util.LinkedHashMap;
import java.util.Map;

// An answer of the query API as the server sends it; the content type, Cache-Control, ETag, and body are null when it has none. Every answer names
// the protocol and the build (W6).
public record QueryAnswer(int status, String contentType, String cacheControl, String eTag, String body) {

    public static final String PROTOCOL_HEADER = "X-Smd-Monitor-Protocol";
    public static final String BUILD_HEADER = "X-Smd-Monitor-Build";
    private static final String JSON = "application/json";
    private static final String TEXT = "text/plain";
    private static final String NO_STORE = "no-store";
    // The page may keep the answer, and asks again with its ETag.
    private static final String NO_CACHE = "no-cache";

    public Map<String, String> headers() {
        var headers = new LinkedHashMap<String, String>();
        if (contentType != null) {
            headers.put("Content-Type", contentType);
        }
        headers.put(PROTOCOL_HEADER, String.valueOf(Protocol.VERSION));
        headers.put(BUILD_HEADER, Protocol.build());
        if (cacheControl != null) {
            headers.put("Cache-Control", cacheControl);
        }
        if (eTag != null) {
            headers.put("ETag", eTag);
        }
        return headers;
    }

    static QueryAnswer json(String body) {
        return new QueryAnswer(200, JSON, NO_STORE, null, body);
    }

    static QueryAnswer versioned(String body, String version) {
        return new QueryAnswer(200, JSON, NO_CACHE, '"' + version + '"', body);
    }

    static QueryAnswer notFound() {
        return new QueryAnswer(404, null, NO_STORE, null, null);
    }

    // A query naming what the hub cannot read, with the reason.
    public static QueryAnswer invalid(String reason) {
        return new QueryAnswer(400, TEXT, NO_STORE, null, reason);
    }

    static QueryAnswer busy(String reason) {
        return new QueryAnswer(429, TEXT, NO_STORE, null, reason);
    }

    // 304 when the page holds this answer with the same ETag already.
    QueryAnswer unlessHeld(String ifNoneMatch) {
        return eTag != null && eTag.equals(ifNoneMatch) ? new QueryAnswer(304, null, cacheControl, eTag, null) : this;
    }
}
