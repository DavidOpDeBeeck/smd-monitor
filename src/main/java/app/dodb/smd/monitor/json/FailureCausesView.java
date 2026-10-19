package app.dodb.smd.monitor.json;

import app.dodb.smd.monitor.hub.FailureCause;
import app.dodb.smd.monitor.hub.Failures;
import app.dodb.smd.monitor.hub.span.Stage;

import java.util.List;
import java.util.UUID;

// The retained occurrences of the failure causes the page asked for, in the order asked.
public record FailureCausesView(String hubSession, long sequence, List<CauseView> causes) {

    public static FailureCausesView from(UUID hubSession, Failures failures) {
        return new FailureCausesView(hubSession.toString(), failures.sequence(), failures.causes().stream().map(CauseView::from).toList());
    }

    // The latest occurrences come newest first; a chain runs from the entry point to the invocation that raised the failure.
    public record CauseView(StepView cause, int retained, List<InvocationView> latest, List<ChainView> chains) {

        static CauseView from(FailureCause cause) {
            return new CauseView(StepView.from(cause.cause()), cause.retained(), cause.latest().stream().map(InvocationView::from).toList(),
                cause.chains().stream().map(ChainView::from).toList());
        }
    }

    // The processing group is null for a dispatch and for the handling of a command or query.
    public record StepView(String application, String kind, String type, boolean handling, String processingGroup, String failureType) {

        static StepView from(FailureCause.Step step) {
            var type = step.stage().type();
            return switch (step.stage()) {
                case Stage.Dispatch ignored -> new StepView(step.application().value(), type.kind().name(), type.name(), false, null, step.failureType());
                case Stage.Handling handling -> new StepView(step.application().value(), type.kind().name(), type.name(), true,
                    handling.processingGroup().orElse(null), step.failureType());
            };
        }
    }

    public record ChainView(List<StepView> steps, int occurrences) {

        static ChainView from(FailureCause.Chain chain) {
            return new ChainView(chain.steps().stream().map(StepView::from).toList(), chain.occurrences());
        }
    }
}
