package app.dodb.smd.monitor.hub.javalin;

import app.dodb.smd.monitor.hub.MonitorHub;
import app.dodb.smd.monitor.hub.otlp.OtlpAdmission;
import app.dodb.smd.monitor.hub.otlp.OtlpAnswer;
import io.javalin.Javalin;
import io.javalin.http.Context;

import static app.dodb.smd.monitor.hub.otlp.OtlpAdmission.*;

public class OtlpServer implements AutoCloseable {

    private final Javalin javalin;

    OtlpServer(HubSettings settings, MonitorHub hub) {
        var admission = new OtlpAdmission(hub);
        javalin = Javalin.create(config -> {
            config.concurrency.useVirtualThreads = true;
            config.startup.showJavalinBanner = false;
            config.jetty.host = settings.otlpAddress();
            config.jetty.port = settings.otlpPort();
            config.routes.post(PATH, ctx -> export(ctx, admission));
        });
    }

    public void start() {
        javalin.start();
    }

    public int port() {
        return javalin.port();
    }

    @Override
    public void close() {
        javalin.stop();
    }

    private static void export(Context ctx, OtlpAdmission admission) {
        var answer = admission.admit(ctx.bodyInputStream(), ctx.header("Content-Encoding"));
        if (answer instanceof OtlpAnswer.Busy busy) {
            ctx.header("Retry-After", String.valueOf(busy.retryAfterSeconds()));
        }
        ctx.status(answer.status()).contentType(PROTOBUF).result(answer.body());
    }
}
