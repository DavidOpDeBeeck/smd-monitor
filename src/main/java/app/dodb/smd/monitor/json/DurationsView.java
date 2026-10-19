package app.dodb.smd.monitor.json;

import app.dodb.smd.monitor.hub.DurationHistogram;

import java.util.List;
import java.util.UUID;

public record DurationsView(String hubSession, long sequence, int invocations, int slower, List<Integer> buckets) {

    public static DurationsView from(UUID hubSession, DurationHistogram histogram) {
        return new DurationsView(hubSession.toString(), histogram.sequence(), histogram.invocations(), histogram.slower(), histogram.buckets());
    }
}
