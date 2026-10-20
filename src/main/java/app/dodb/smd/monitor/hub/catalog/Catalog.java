package app.dodb.smd.monitor.hub.catalog;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.monitor.hub.HubConfig;
import app.dodb.smd.monitor.hub.budget.Footprint;
import app.dodb.smd.monitor.hub.span.InstanceName;
import app.dodb.smd.monitor.hub.span.MessageKind;
import app.dodb.smd.monitor.hub.span.MessageType;
import app.dodb.smd.monitor.hub.span.Stage;

import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

// The keys an application counts by, each with a dense ID per kind in the order first seen (A1). An application keeps up to max-types message
// types and max-groups processing groups apart; the dispatches of further types count under the other dispatch of their kind, and their
// handlings, or those of further groups, under the other handling. Each kind of edge keeps up to max-edges apart and counts further ones under
// its other ID. The catalog also holds what the statistics say of the keys: the dispatches that entered traces, each group's delivery, and which handlings
// ran asynchronously. It counts what it assigned to an other key as overflow. Owned by the hub's writer.
public final class Catalog {

    // The other ID of every kind but stages and instances.
    public static final int OTHER = 0;
    // The other handling; the other dispatch of each kind is the kind's ordinal.
    public static final int OTHER_HANDLING = MessageKind.values().length;

    private final int maxTypes;
    private final int maxGroups;
    private final Set<MessageType> types = new HashSet<>();
    private final Set<String> groups = new HashSet<>();
    private final Interned<Stage> stages = new Interned<>(Integer.MAX_VALUE, OTHER_HANDLING + 1);
    private final Interned<CausalLink> links;
    private final Interned<Crossing> crossings;
    private final Interned<GroupLink> groupLinks;
    private final Interned<EventRoute> routes;
    private final Interned<SinkRoute> sinkRoutes;
    private final Interned<RaisedFailure> failures;
    // Admission bounds the instances of an application.
    private final Interned<InstanceName> instances = new Interned<>(Integer.MAX_VALUE, 0);
    // The dispatch stages that entered a trace.
    private final BitSet entryPoints = new BitSet();
    private final Map<String, EventDelivery> deliveries = new HashMap<>();
    private final BitSet asynchronous = new BitSet();
    private long overflowed;

    public Catalog(HubConfig config) {
        requireNonNull(config);
        this.maxTypes = config.maxTypes();
        this.maxGroups = config.maxGroups();
        this.links = new Interned<>(config.maxEdges(), OTHER + 1);
        this.crossings = new Interned<>(config.maxEdges(), OTHER + 1);
        this.groupLinks = new Interned<>(config.maxEdges(), OTHER + 1);
        this.routes = new Interned<>(config.maxEdges(), OTHER + 1);
        this.sinkRoutes = new Interned<>(config.maxEdges(), OTHER + 1);
        this.failures = new Interned<>(config.maxTypes(), OTHER + 1);
    }

    public static int otherDispatch(MessageKind kind) {
        return kind.ordinal();
    }

    public int stage(Stage stage) {
        var other = stage instanceof Stage.Dispatch ? otherDispatch(stage.type().kind()) : OTHER_HANDLING;
        var admitted = admitted(types, stage.type(), maxTypes)
            && (!(stage instanceof Stage.Handling handling) || admitted(groups, handling.processingGroup(), maxGroups));
        if (!admitted) {
            overflowed++;
            return other;
        }
        return counted(stages.idOf(stage, other), other);
    }

    public int link(CausalLink link) {
        return counted(links.idOf(link, OTHER), OTHER);
    }

    public int crossing(Crossing crossing) {
        return counted(crossings.idOf(crossing, OTHER), OTHER);
    }

    public int groupLink(GroupLink link) {
        return counted(groupLinks.idOf(link, OTHER), OTHER);
    }

    public int route(EventRoute route) {
        return counted(routes.idOf(route, OTHER), OTHER);
    }

    public int sinkRoute(SinkRoute route) {
        return counted(sinkRoutes.idOf(route, OTHER), OTHER);
    }

    public int failure(RaisedFailure failure) {
        return counted(failures.idOf(failure, OTHER), OTHER);
    }

    public int instance(InstanceName instance) {
        return instances.idOf(instance, OTHER);
    }

    // The IDs of the stages kept apart run from this one to stagesEnd, exclusive; and likewise for each other kind.
    public int firstStage() {
        return stages.first();
    }

    public int stagesEnd() {
        return stages.end();
    }

    public Stage stageOf(int id) {
        return stages.key(id);
    }

    public int linksEnd() {
        return links.end();
    }

    public CausalLink linkOf(int id) {
        return links.key(id);
    }

    public int crossingsEnd() {
        return crossings.end();
    }

    public Crossing crossingOf(int id) {
        return crossings.key(id);
    }

    public int groupLinksEnd() {
        return groupLinks.end();
    }

    public GroupLink groupLinkOf(int id) {
        return groupLinks.key(id);
    }

    public int routesEnd() {
        return routes.end();
    }

    public EventRoute routeOf(int id) {
        return routes.key(id);
    }

    public int sinkRoutesEnd() {
        return sinkRoutes.end();
    }

    public SinkRoute sinkRouteOf(int id) {
        return sinkRoutes.key(id);
    }

    public int failuresEnd() {
        return failures.end();
    }

    public RaisedFailure failureOf(int id) {
        return failures.key(id);
    }

    public void entered(int stage) {
        entryPoints.set(stage);
    }

    public boolean entryPoint(int stage) {
        return entryPoints.get(stage);
    }

    // The delivery of the group's latest handling; instances that configure a group differently take turns.
    public void delivered(String group, EventDelivery delivery) {
        if (groups.contains(group)) {
            deliveries.put(group, delivery);
        }
    }

    public EventDelivery delivery(String group) {
        return deliveries.get(group);
    }

    // A handling stays asynchronous once one started after its publication returned.
    public void ranAsynchronously(int stage) {
        asynchronous.set(stage);
    }

    public boolean asynchronous(int stage) {
        return asynchronous.get(stage);
    }

    // The spans, links, sends, and failures counted under an other key, as their kind reached its cap.
    public long overflowed() {
        return overflowed;
    }

    // The heap the keys take, with the types and groups admitted apart.
    public long bytes() {
        var keys = (long) stages.size() + links.size() + crossings.size() + groupLinks.size() + routes.size() + sinkRoutes.size() + failures.size()
            + instances.size() + types.size() + groups.size() + deliveries.size();
        return keys * Footprint.CATALOG_KEY;
    }

    private int counted(int id, int other) {
        overflowed += id == other ? 1 : 0;
        return id;
    }

    private static <K> boolean admitted(Set<K> admitted, K key, int cap) {
        if (admitted.contains(key)) {
            return true;
        }
        if (admitted.size() >= cap) {
            return false;
        }
        admitted.add(key);
        return true;
    }
}
