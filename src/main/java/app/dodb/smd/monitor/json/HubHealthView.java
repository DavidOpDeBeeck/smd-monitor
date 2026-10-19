package app.dodb.smd.monitor.json;

import app.dodb.smd.monitor.hub.health.ApplicationHealth;
import app.dodb.smd.monitor.hub.health.HealthView;
import app.dodb.smd.monitor.hub.health.ProductUse;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static app.dodb.smd.monitor.json.FrameView.epochMillis;

// The health of the hub (FR7): what each product takes of the memory budget against its limit, the history its counts cover against the
// configured one and whether seconds fold sooner under memory pressure, the configured link horizon, the spans of applications beyond its
// limit, and the health of every application. The limit of the specimens is what the budget leaves them now.
public record HubHealthView(long budgetBytes,
                            ProductView queues,
                            ProductView counting,
                            ProductView join,
                            ProductView specimens,
                            long historySeconds,
                            long effectiveHistorySeconds,
                            boolean foldsSooner,
                            long linkHorizonSeconds,
                            long refusedByApplicationLimit,
                            List<ApplicationHealthView> applications) {

    static HubHealthView from(HealthView health) {
        var memory = health.memory();
        return new HubHealthView(memory.budget(), ProductView.from(memory.queues()), ProductView.from(memory.counting()), ProductView.from(memory.join()),
            ProductView.from(memory.specimens()), health.history().toSeconds(), health.effectiveHistory().toSeconds(), health.foldsSooner(),
            health.linkHorizon().toSeconds(), health.refusedByApplicationLimit(),
            health.applications().stream().map(ApplicationHealthView::from).toList());
    }

    public record ProductView(long bytes, long limitBytes) {

        static ProductView from(ProductUse use) {
            return new ProductView(use.bytes(), use.limit());
        }
    }

    // The counts by reason since the application appeared; the heaviest shedding of its batches in the last second; what it takes of counting,
    // and of the join and the specimens against its fair share of each. It retains every invocation that started after complete-since, which is
    // null while the hub dropped or shed none of it; the oldest it retains started at oldest-retained, null while it retains none.
    public record ApplicationHealthView(String name,
                                        Map<String, Long> reasons,
                                        String shedding,
                                        long countingBytes,
                                        ProductView join,
                                        ProductView specimens,
                                        int retained,
                                        Double completeSinceMillis,
                                        Double oldestRetainedMillis,
                                        long effectiveHorizonSeconds,
                                        int slowestK,
                                        int failuresK) {

        static ApplicationHealthView from(ApplicationHealth health) {
            var reasons = new TreeMap<String, Long>();
            health.reasons().forEach((reason, count) -> reasons.put(reason.name(), count));
            return new ApplicationHealthView(health.application().value(), reasons, health.shedding().name(), health.countingBytes(),
                ProductView.from(health.join()), ProductView.from(health.specimens()), health.retained(), health.completeSince().map(FrameView::epochMillis).orElse(null),
                health.oldestRetained().map(FrameView::epochMillis).orElse(null),
                health.effectiveHorizon().toSeconds(), health.slowestK(), health.failuresK());
        }
    }
}
