package app.dodb.smd.monitor.json;

import app.dodb.smd.monitor.hub.ApplicationStatistics;
import app.dodb.smd.monitor.hub.GroupStatistics;
import app.dodb.smd.monitor.hub.HandlingStatistics;
import app.dodb.smd.monitor.json.FrameView.BriefView;

import java.util.List;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

// What each processing group's handling of each event counted over the window and the history, which the frame leaves out: only an expanded
// group, the failures view, and the flow view show it.
public record HandlingsView(String hubSession, long sequence, List<HandlingFiguresView> handlings) {

    public HandlingsView {
        requireNonNull(hubSession);
        requireNonNull(handlings);
    }

    public static HandlingsView from(UUID hubSession, long sequence, List<ApplicationStatistics> applications) {
        return new HandlingsView(hubSession.toString(), sequence, applications.stream()
            .flatMap(application -> application.groups().stream().flatMap(group -> group.handlings().stream()
                .map(handling -> HandlingFiguresView.from(application.application().value(), group, handling))))
            .toList());
    }

    public record HandlingFiguresView(String application, String processingGroup, String name, BriefView window, BriefView history) {

        static HandlingFiguresView from(String application, GroupStatistics group, HandlingStatistics handling) {
            return new HandlingFiguresView(application, group.processingGroup(), handling.type().name(), BriefView.from(handling.window()),
                BriefView.from(handling.history()));
        }
    }
}
