package app.dodb.smd.monitor.hub.otlp;

import app.dodb.smd.monitor.hub.Submission;
import app.dodb.smd.monitor.hub.intake.SpanBatch;
import io.opentelemetry.api.common.AttributeKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

// The SMD spans of one export, however they arrived: read resource by resource, then submitted to the hub once the whole export is read. A
// span the hub cannot read or refuses is left out and counted, and the others reach the hub.
final class SpanIntake {

    private final List<SpanBatch> batches = new ArrayList<>();
    private final Rejected rejected = new Rejected();

    // The spans one resource reported, each read on its own so a span the hub cannot read leaves out only itself.
    <S> void read(Function<AttributeKey<String>, String> resource, List<S> spans, Function<S, ReadSpan> read) {
        var readSpans = new ArrayList<ReadSpan>();
        for (var span : spans) {
            try {
                readSpans.add(read.apply(span));
            } catch (IllegalArgumentException invalid) {
                rejected.add(1, invalid.getMessage());
            }
        }
        if (readSpans.isEmpty()) {
            return;
        }
        try {
            batches.add(SpanReader.report(resource, readSpans));
        } catch (IllegalArgumentException invalid) {
            rejected.add(readSpans.size(), invalid.getMessage());
        }
    }

    // Submits every batch, so an application whose queue is full holds up no other, and returns the first busy answer.
    Optional<Submission.Busy> submit(Function<SpanBatch, Submission> submit) {
        var busy = Optional.<Submission.Busy>empty();
        for (var batch : batches) {
            switch (submit.apply(batch)) {
                case Submission.Accepted(var refusal) -> refusal.ifPresent(refused -> rejected.add(refused.spans(), refused.reason()));
                case Submission.Busy answer -> busy = busy.or(() -> Optional.of(answer));
            }
        }
        return busy;
    }

    // What the hub could not read or refused, once submitted.
    Rejected rejected() {
        return rejected;
    }
}
