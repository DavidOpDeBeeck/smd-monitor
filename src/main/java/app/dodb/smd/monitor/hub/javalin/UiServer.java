package app.dodb.smd.monitor.hub.javalin;

import app.dodb.smd.monitor.hub.MonitorHub;
import app.dodb.smd.monitor.hub.web.FramePublisher;
import app.dodb.smd.monitor.hub.web.HubQueries;
import app.dodb.smd.monitor.hub.web.MonitorFrames;
import app.dodb.smd.monitor.hub.web.Origins;
import app.dodb.smd.monitor.hub.web.QueryAnswer;
import app.dodb.smd.monitor.hub.web.QueryFilter;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.ForbiddenResponse;
import org.eclipse.jetty.session.NullSessionCacheFactory;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import static io.javalin.http.staticfiles.Location.CLASSPATH;
import static java.util.Objects.requireNonNull;

public class UiServer implements AutoCloseable {

    static final String LIVE = "/live";
    private final FramePublisher publisher;
    private final Javalin javalin;

    UiServer(HubSettings settings, MonitorHub hub) {
        this(settings, hub, new FramePublisher(hub, new MonitorFrames(), settings.maxViewers(), FramePublisher::intervalFor));
    }

    UiServer(HubSettings settings, MonitorHub hub, FramePublisher publisher) {
        this(settings, hub, publisher, new HubQueries(hub));
    }

    UiServer(HubSettings settings, MonitorHub hub, FramePublisher publisher, HubQueries queries) {
        this.publisher = requireNonNull(publisher);
        javalin = Javalin.create(config -> {
            config.concurrency.useVirtualThreads = true;
            config.startup.showJavalinBanner = false;
            config.jetty.host = settings.uiAddress();
            config.jetty.port = settings.uiPort();
            config.staticFiles.add("smd-monitor", CLASSPATH);
            config.routes.exception(IllegalArgumentException.class, (invalid, ctx) -> answer(ctx, QueryAnswer.invalid(invalid.getMessage())));
            config.routes
                .get("/api/tape", ctx -> answer(ctx, queries.tape(filter(ctx), ctx.queryParam("minDurationMillis"), ctx.queryParam("after"), ctx.queryParam("limit"))))
                .get("/api/tape/durations", ctx -> answer(ctx, queries.durations(filter(ctx), ctx.queryParam("minDurationMillis"), ctx.queryParam("buckets"))))
                .get("/api/traces", ctx -> answer(ctx, queries.traces(filter(ctx), ctx.queryParam("chains"), ctx.queryParam("order"), ctx.queryParam("after"),
                    ctx.queryParam("limit"))))
                .get("/api/traces/{sequence}", ctx -> answer(ctx, queries.trace(ctx.pathParam("sequence"))))
                .get("/api/failures", ctx -> answer(ctx, queries.failures(ctx.queryParams("cause"))))
                .get("/api/timeline", ctx -> answer(ctx, queries.timeline(filter(ctx), ctx.queryParam("minDurationMillis"), ctx.queryParam("spanMillis"),
                    ctx.queryParam("limit"))))
                .get("/api/invocations/{sequence}", ctx -> answer(ctx, queries.invocation(ctx.pathParam("sequence"))))
                .get("/api/invocations", ctx -> answer(ctx, queries.invocations(ctx.queryParam("scope"), ctx.queryParam("messageIdPrefix"), ctx.queryParam("limit"))))
                .get("/api/types", ctx -> answer(ctx, queries.type(ctx.queryParam("type"))))
                .get("/api/flow", ctx -> answer(ctx, queries.flow(ctx.queryParam("scope"))))
                .get("/api/handlings", ctx -> answer(ctx, queries.handlings(ctx.queryParam("scope"))));
            config.routes.wsBeforeUpgrade(LIVE, ctx -> {
                if (!Origins.allowed(ctx.header("Origin"), ctx.host(), settings.allowedOrigins())) {
                    throw new ForbiddenResponse();
                }
            });
            config.routes.ws(LIVE, ws -> {
                ws.onConnect(ctx -> {
                    if (!publisher.connect(new JettySink(ctx.session))) {
                        ctx.closeSession();
                    }
                });
                ws.onClose(ctx -> publisher.disconnect(new JettySink(ctx.session)));
            });
            config.jetty.modifyServer(server -> server.addBean(new NullSessionCacheFactory()));
        });
    }

    void start() {
        publisher.start();
        javalin.start();
    }

    int port() {
        return javalin.port();
    }

    @Override
    public void close() {
        javalin.stop();
        publisher.close();
    }

    private static QueryFilter filter(Context ctx) {
        return new QueryFilter(ctx.queryParam("scope"), ctx.queryParams("application"), ctx.queryParams("kind"), ctx.queryParams("type"),
            ctx.queryParams("status"), ctx.queryParam("search"));
    }

    // 304 instead when the page holds the answer already, as the ETag it sends names.
    private static void answer(Context ctx, QueryAnswer full) {
        var answer = full.unlessHeld(ctx.header("If-None-Match"));
        answer.headers().forEach(ctx::header);
        ctx.status(answer.status());
        if (answer.body() != null) {
            ctx.result(answer.body());
        }
    }

    // Sends the publisher's UTF-8 text as it is, a text frame of the core session, so the clients share one encoding of each message; each send
    // wraps the bytes on its own, as the frame reads them, and Jetty deflates a frame on the thread that sends it.
    private record JettySink(Session session) implements FramePublisher.Sink {

        @Override
        public void send(byte[] message, Consumer<Boolean> sent) {
            var core = ((WebSocketSession) session).getCoreSession();
            Thread.startVirtualThread(() -> core.sendFrame(new Frame(OpCode.TEXT, ByteBuffer.wrap(message)),
                Callback.from(() -> sent.accept(true), failure -> sent.accept(false)), false));
        }

        @Override
        public void close() {
            session.close();
        }
    }
}
