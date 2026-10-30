package app.dodb.smd.monitor.json;

import app.dodb.smd.monitor.hub.ApplicationStatistics;
import app.dodb.smd.monitor.hub.ApplicationType;
import app.dodb.smd.monitor.hub.Counts;
import app.dodb.smd.monitor.hub.LinkStatistics;
import app.dodb.smd.monitor.hub.RetainedType;
import app.dodb.smd.monitor.hub.TypeStatistics;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.json.FrameView.BriefView;
import app.dodb.smd.monitor.json.FrameView.MessageTypeView;
import app.dodb.smd.monitor.json.FrameView.StatisticsView;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

// The failure types most frequent first, and the latest dispatches newest first. The window and the history hold every figure of the type over
// each, which the frame leaves out; they are null when the application did not report the type, and its seconds are then empty. The links into the type and
// out of it are those of its application and the crossings of every application, most invocations first; an event's handlings are those of its
// application's processing groups. Its seconds end at the end second, that of the epoch it answers from.
public record RetainedTypeView(String hubSession, long sequence, long endEpochSecond, StatisticsView window, StatisticsView history, int retained,
                               List<FailureTypeView> failureTypes, List<InvocationView> latest, List<TypeLinkView> incoming, List<TypeLinkView> outgoing,
                               List<TypeHandlingView> handlings, List<Long> startedPerSecond, List<Long> failedPerSecond) {

    public static RetainedTypeView from(UUID hubSession, long endEpochSecond, RetainedType type, Optional<Counts> history, ApplicationType selected,
                                       List<ApplicationStatistics> applications) {
        var links = applications.stream().flatMap(application -> Stream.concat(
                application.links().stream().map(link -> TypeLinkView.from(application.application(), application.application(), link)),
                application.crossings().stream().map(crossing -> TypeLinkView.from(crossing.parentApplication(), application.application(), crossing.link()))))
            .sorted(Comparator.comparingLong(TypeLinkView::history).reversed())
            .toList();
        var application = selected.application().value();
        var name = MessageTypeView.from(selected.type());
        var counted = applications.stream().filter(each -> each.application().equals(selected.application()))
            .flatMap(each -> each.types().stream()).filter(each -> each.type().equals(selected.type()))
            .findFirst();
        return new RetainedTypeView(hubSession.toString(), type.sequence(), endEpochSecond, counted.map(each -> StatisticsView.from(each.window())).orElse(null),
            history.map(StatisticsView::from).orElse(null), type.retained(),
            type.failureTypes().stream().map(count -> new FailureTypeView(count.failureType(), count.count())).toList(),
            type.latest().stream().map(InvocationView::from).toList(),
            links.stream().filter(link -> link.childApplication().equals(application) && link.child().equals(name)).toList(),
            links.stream().filter(link -> link.parentApplication().equals(application) && link.parent().equals(name)).toList(),
            applications.stream().filter(statistics -> statistics.application().equals(selected.application()))
                .flatMap(statistics -> statistics.groups().stream().flatMap(group -> group.handlings().stream()
                    .filter(handling -> handling.type().equals(selected.type()))
                    .map(handling -> new TypeHandlingView(group.processingGroup(), BriefView.from(handling.window()), BriefView.from(handling.history())))))
                .toList(),
            counted.map(TypeStatistics::startedPerSecond).orElse(List.of()), counted.map(TypeStatistics::failedPerSecond).orElse(List.of()));
    }

    // A processing group's handling of the event.
    public record TypeHandlingView(String processingGroup, BriefView window, BriefView history) {
    }

    public record FailureTypeView(String failureType, int count) {
    }

    // A link between two types, each named with its application.
    public record TypeLinkView(String parentApplication, MessageTypeView parent, String childApplication, MessageTypeView child, long history,
                               long lastSecond) {

        static TypeLinkView from(ApplicationName parentApplication, ApplicationName childApplication, LinkStatistics link) {
            return new TypeLinkView(parentApplication.value(), MessageTypeView.from(link.link().parent()), childApplication.value(),
                MessageTypeView.from(link.link().child()), link.history(), link.lastSecond());
        }
    }
}
