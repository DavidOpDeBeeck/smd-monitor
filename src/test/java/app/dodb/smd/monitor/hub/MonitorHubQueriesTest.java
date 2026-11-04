package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.RecordingInstance.MutableClock;
import app.dodb.smd.monitor.hub.budget.Budgets;
import app.dodb.smd.monitor.hub.budget.Footprint;
import app.dodb.smd.monitor.hub.span.MessageKind;
import app.dodb.smd.monitor.hub.span.MessageSpan;
import app.dodb.smd.monitor.hub.span.MessageType;
import app.dodb.smd.monitor.hub.span.Outcome;
import app.dodb.smd.monitor.hub.span.Stage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static app.dodb.smd.monitor.hub.RecordingInstance.ASSIGN;
import static app.dodb.smd.monitor.hub.RecordingInstance.BILLING;
import static app.dodb.smd.monitor.hub.RecordingInstance.OPEN;
import static app.dodb.smd.monitor.hub.RecordingInstance.OPENED;
import static app.dodb.smd.monitor.hub.RecordingInstance.STARTED_AT;
import static app.dodb.smd.monitor.hub.RecordingInstance.TICKETS;
import static app.dodb.smd.monitor.hub.RecordingInstance.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class MonitorHubQueriesTest {

    private static final String FAILURE = "java.lang.IllegalStateException";
    private final MutableClock clock = new MutableClock();
    private final SynchronousHub hub = new SynchronousHub(HubConfig.withDefaults().build(), clock);
    private final RecordingInstance tickets = new RecordingInstance(TICKETS, "tickets-1");
    private final RecordingInstance billing = new RecordingInstance(BILLING, "billing-1");

    @Test
    void tape_filters_listsTheMatchingInvocationsNewestFirstWithTheCountsOfTheScope() {
        tickets.reportTo(hub, tickets.dispatch(OPEN), failed(tickets.dispatch(ASSIGN), 7), tickets.dispatch(OPEN), timed(tickets.dispatch(OPEN), 5));
        billing.reportTo(hub, billing.dispatch(OPEN));
        var filter = new InvocationFilter(Optional.of(TICKETS), Set.of(), new TypeSelection(Set.of(), Set.of(new ApplicationType(TICKETS, OPEN))), Set.of(), "");

        var page = hub.tape(new TapeQuery(filter, Duration.ZERO, Optional.empty(), 10));

        assertThat(page.invocations()).extracting(retained -> retained.invocation().id().value()).containsExactly(4L, 3L, 1L);
        assertThat(page.total()).isEqualTo(4);
        assertThat(page.matching()).isEqualTo(3);
        assertThat(page.slowest()).contains(Duration.ofMillis(5));
        assertThat(page.next()).isEmpty();
    }

    // Retained invocations sit in chunks of 4,096 in arrival order, every application's interleaved; a query of one application or of the
    // failures passes over the others' slots by what each slot keeps, also in the chunks a later epoch copied.
    @Test
    void tape_scopeAcrossChunksOfInterleavedApplications_countsOnlyItsInvocations() {
        for (var round = 0; round < 3; round++) {
            var spans = new ArrayList<MessageSpan>();
            for (var index = 0; index < 1_500; index++) {
                spans.add(index % 100 == 0 ? failed(tickets.dispatch(ASSIGN), 1) : tickets.dispatch(OPEN));
            }
            tickets.reportTo(hub, spans.toArray(MessageSpan[]::new));
            billing.reportTo(hub, IntStream.range(0, 1_500).mapToObj(index -> billing.dispatch(OPEN)).toArray(MessageSpan[]::new));
        }
        var billingOnly = new InvocationFilter(Optional.of(BILLING), Set.of(), TypeSelection.EVERY_TYPE, Set.of(), "");

        var page = hub.tape(new TapeQuery(billingOnly, Duration.ZERO, Optional.empty(), 10));
        var failures = hub.failures(List.of(step(ASSIGN))).causes();

        assertThat(page.total()).isEqualTo(4_500);
        assertThat(page.invocations()).extracting(retained -> retained.invocation().application()).containsOnly(BILLING);
        assertThat(failures).singleElement().satisfies(cause -> assertThat(cause.retained()).isEqualTo(45));
    }

    @Test
    void tape_statusesAndSearch_matchTheFailureTypeIgnoringCase() {
        tickets.reportTo(hub, tickets.dispatch(OPEN), failed(tickets.dispatch(ASSIGN), 1));
        var filter = new InvocationFilter(Optional.empty(), Set.of(), TypeSelection.EVERY_TYPE, Set.of(Outcome.Status.FAILED), "illegalSTATE");

        var page = hub.tape(new TapeQuery(filter, Duration.ZERO, Optional.empty(), 10));

        assertThat(page.invocations()).extracting(retained -> retained.invocation().type()).containsExactly(ASSIGN);
    }

    @Test
    void tape_applications_keepOnlyTheirInvocations() {
        tickets.reportTo(hub, tickets.dispatch(OPEN));
        billing.reportTo(hub, billing.dispatch(OPEN));
        var filter = new InvocationFilter(Optional.empty(), Set.of(BILLING), TypeSelection.EVERY_TYPE, Set.of(), "");

        var page = hub.tape(new TapeQuery(filter, Duration.ZERO, Optional.empty(), 10));

        assertThat(page.invocations()).extracting(retained -> retained.invocation().application()).containsExactly(BILLING);
        assertThat(page.total()).isEqualTo(2);
    }

    @Test
    void tape_minimumDuration_leavesOutFasterInvocations() {
        tickets.reportTo(hub, timed(tickets.dispatch(OPEN), 1), timed(tickets.dispatch(OPEN), 20));

        var page = hub.tape(new TapeQuery(InvocationFilter.EVERY_INVOCATION, Duration.ofMillis(10), Optional.empty(), 10));

        assertThat(page.invocations()).extracting(retained -> retained.invocation().duration()).containsExactly(Duration.ofMillis(20));
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.matching()).isEqualTo(1);
    }

    @Test
    void tape_pagesOfTwo_followTheCursorToTheLastPage() {
        tickets.reportTo(hub, tickets.dispatch(OPEN), tickets.dispatch(OPEN), tickets.dispatch(OPEN), tickets.dispatch(OPEN), tickets.dispatch(OPEN));
        var pages = new ArrayList<List<Long>>();

        var cursor = Optional.<InvocationId>empty();
        do {
            var page = hub.tape(new TapeQuery(InvocationFilter.EVERY_INVOCATION, Duration.ZERO, cursor, 2));
            pages.add(page.invocations().stream().map(retained -> retained.invocation().id().value()).toList());
            cursor = page.next();
        } while (cursor.isPresent());

        assertThat(pages).containsExactly(List.of(5L, 4L), List.of(3L, 2L), List.of(1L));
    }

    @Test
    void tape_parentEvictedFromAnotherApplication_namesTheParentAsNoLongerRetained() {
        // Two traces of their own, and the billing child with its parent's summary.
        var hub = new SynchronousHub(HubConfig.withDefaults().build(), clock, Budgets.specimens(2 * (Footprint.SPECIMEN + Footprint.TRACE) + Footprint.PARENT));
        var open = RecordingInstance.beforeTheWindow(tickets.dispatch(OPEN));
        tickets.reportTo(hub, open, RecordingInstance.beforeTheWindow(tickets.dispatch(OPEN)), tickets.dispatch(OPEN));

        billing.reportTo(hub, billing.dispatch(ASSIGN, open));

        var child = hub.tape(new TapeQuery(InvocationFilter.EVERY_INVOCATION, Duration.ZERO, Optional.empty(), 10)).invocations().getFirst();
        assertThat(child.invocation().application()).isEqualTo(BILLING);
        assertThat(child.parent()).hasValueSatisfying(parent -> assertThat(parent.application()).isEqualTo(TICKETS));
        assertThat(child.parentRetained()).isFalse();
    }

    @Test
    void tape_limitBeyondTheMaximum_rejectsWithContext() {
        assertThatThrownBy(() -> new TapeQuery(InvocationFilter.EVERY_INVOCATION, Duration.ZERO, Optional.empty(), TapeQuery.MAX_LIMIT + 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("limit", "between 1 and 1000", "1001");
    }

    // Six buckets of equal width on the log axis from 10 µs to 60 s: 2 ms falls in the third, 150 and 200 ms in the fourth.
    @Test
    void durations_bucketsOnTheLatencyAxis_countEachMatchingInvocationInTheBucketOfItsDurationAndThoseSlowerThanTheThreshold() {
        tickets.reportTo(hub, timed(tickets.dispatch(OPEN), 2), timed(tickets.dispatch(OPEN), 150), timed(tickets.dispatch(OPEN), 200), tickets.dispatch(ASSIGN));
        var filter = new InvocationFilter(Optional.empty(), Set.of(), new TypeSelection(Set.of(), Set.of(new ApplicationType(TICKETS, OPEN))), Set.of(), "");

        var histogram = hub.durations(filter, Duration.ofMillis(150), 6);

        assertThat(histogram.invocations()).isEqualTo(3);
        assertThat(histogram.slower()).isEqualTo(2);
        assertThat(histogram.buckets()).containsExactly(0, 0, 1, 2, 0, 0);
    }

    @Test
    void durations_noBuckets_rejectsWithContext() {
        assertThatThrownBy(() -> hub.durations(InvocationFilter.EVERY_INVOCATION, Duration.ZERO, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("between 1 and 200 buckets", "got 0");
    }

    @Test
    void traces_newest_listsEachTraceOnceWithItsFigures() {
        var open = tickets.dispatch(OPEN);
        var opened = tickets.dispatch(OPENED, open);
        var handling = tickets.handle("ticket-view", opened);
        tickets.reportTo(hub, handling, opened, open, tickets.dispatch(ASSIGN));

        var page = hub.traces(traceQuery(TraceOrder.NEWEST, Optional.empty(), 10));

        assertThat(page.traces()).extracting(trace -> trace.root().type(), Trace::messages, Trace::handlings, Trace::size)
            .containsExactly(tuple(ASSIGN, 1, 0, 1), tuple(OPEN, 2, 1, 3));
        assertThat(page.traces().getLast().composition()).containsEntry(MessageKind.EVENT, 1).containsEntry(MessageKind.COMMAND, 0);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.matching()).isEqualTo(2);
    }

    @Test
    void traces_slowestInPagesOfTwo_listEveryTraceOnceInOrder() {
        tickets.reportTo(hub, timed(tickets.dispatch(OPEN), 3), timed(tickets.dispatch(OPEN), 9), timed(tickets.dispatch(OPEN), 3), timed(tickets.dispatch(OPEN), 1),
            timed(tickets.dispatch(OPEN), 9));
        var roots = new ArrayList<Long>();

        var cursor = Optional.<TraceCursor>empty();
        do {
            var page = hub.traces(traceQuery(TraceOrder.SLOWEST, cursor, 2));
            page.traces().forEach(trace -> roots.add(trace.root().id().value()));
            cursor = page.next();
        } while (cursor.isPresent());

        assertThat(roots).containsExactly(5L, 2L, 3L, 1L, 4L);
    }

    @Test
    void traces_largestPageSmallerThanTheTraces_holdsTheLargestInOrderWithTheirCounts() {
        var small = tickets.dispatch(OPEN);
        var large = tickets.dispatch(OPEN);
        var medium = tickets.dispatch(OPEN);
        tickets.reportTo(hub, tickets.dispatch(ASSIGN), small, tickets.dispatch(OPENED, small), tickets.dispatch(ASSIGN), large, tickets.dispatch(OPENED, large),
            tickets.dispatch(ASSIGN, large), tickets.dispatch(ASSIGN, large), medium, tickets.dispatch(OPENED, medium), tickets.dispatch(ASSIGN, medium),
            tickets.dispatch(ASSIGN));

        var page = hub.traces(traceQuery(TraceOrder.LARGEST, Optional.empty(), 3));

        assertThat(page.traces()).extracting(Trace::messages).containsExactly(4, 3, 2);
        assertThat(page.total()).isEqualTo(6);
        assertThat(page.matching()).isEqualTo(6);
        assertThat(page.next()).map(TraceCursor::value).contains(2L);
    }

    @Test
    void traces_chainsWithAFailedMemberInAnotherApplication_matchTheCrossingTraceOnlyInEitherScope() {
        var open = tickets.dispatch(OPEN);
        tickets.reportTo(hub, open, tickets.dispatch(OPEN));
        billing.reportTo(hub, failed(billing.dispatch(ASSIGN, open), 1));
        var query = new TraceQuery(new InvocationFilter(Optional.of(BILLING), Set.of(), TypeSelection.EVERY_TYPE, Set.of(Outcome.Status.FAILED), ""), true, TraceOrder.NEWEST,
            Optional.empty(), 10);

        var page = hub.traces(query);

        assertThat(page.traces()).extracting(Trace::root).extracting(Invocation::id).containsExactly(new InvocationId(1));
        assertThat(page.traces().getFirst().applications()).containsExactly(TICKETS, BILLING);
        assertThat(page.total()).isEqualTo(1);
    }

    @Test
    void traces_childReportedBeforeItsParent_listsTheApplicationsInTheOrderTheTraceReachedThem() {
        var open = tickets.dispatch(OPEN);
        billing.reportTo(hub, with(billing.dispatch(ASSIGN, open), STARTED_AT.plusMillis(1), new Outcome.Completed(Duration.ofMillis(1))));
        tickets.reportTo(hub, open);

        var page = hub.traces(traceQuery(TraceOrder.NEWEST, Optional.empty(), 10));

        assertThat(page.traces()).singleElement().satisfies(trace -> assertThat(trace.applications()).containsExactly(TICKETS, BILLING));
    }

    @Test
    void trace_ofAMember_returnsTheTraceOfItsRootWithTheParentOfEachMember() {
        var open = tickets.dispatch(OPEN);
        var assign = tickets.dispatch(ASSIGN, open);
        tickets.reportTo(hub, open, assign);

        var detail = hub.trace(new InvocationId(2));

        assertThat(detail).hasValueSatisfying(trace -> {
            assertThat(trace.trace().root().id()).isEqualTo(new InvocationId(1));
            assertThat(trace.members()).extracting(member -> member.invocation().id(), RetainedInvocation::parentRetained)
                .containsExactly(tuple(new InvocationId(1), false), tuple(new InvocationId(2), true));
        });
        assertThat(hub.trace(new InvocationId(99))).isEmpty();
    }

    @Test
    void failures_raisingChild_answersItsOccurrencesWithTheChainFromTheEntryPoint() {
        var open = failed(tickets.dispatch(OPEN), 2);
        tickets.reportTo(hub, open, failed(tickets.dispatch(ASSIGN, open), 1), tickets.dispatch(ASSIGN));

        var causes = hub.failures(List.of(step(ASSIGN))).causes();

        assertThat(causes).singleElement().satisfies(cause -> {
            assertThat(cause.cause()).isEqualTo(step(ASSIGN));
            assertThat(cause.retained()).isEqualTo(1);
            assertThat(cause.latest()).extracting(Invocation::id).containsExactly(new InvocationId(2));
            assertThat(cause.chains()).singleElement().satisfies(chain -> assertThat(chain.steps()).extracting(step -> step.stage().type())
                .containsExactly(OPEN, ASSIGN));
        });
    }

    // F1: the parent counts its own failure; the chain shows that its child raised it.
    @Test
    void failures_parentTheFailurePropagatedTo_answersTheChainDownToTheRaisingChild() {
        var open = failed(tickets.dispatch(OPEN), 2);
        tickets.reportTo(hub, open, failed(tickets.dispatch(ASSIGN, open), 1));

        var causes = hub.failures(List.of(step(OPEN))).causes();

        assertThat(causes).singleElement().satisfies(cause -> {
            assertThat(cause.retained()).isEqualTo(1);
            assertThat(cause.latest()).extracting(Invocation::id).containsExactly(new InvocationId(1));
            assertThat(cause.chains()).singleElement().satisfies(chain -> assertThat(chain.steps()).extracting(step -> step.stage().type())
                .containsExactly(OPEN, ASSIGN));
        });
    }

    @Test
    void failures_oneCauseReachedThroughTwoChains_countsEachChainApartMostFrequentFirst() {
        var open = failed(tickets.dispatch(OPEN), 3);
        var otherOpen = failed(tickets.dispatch(OPEN), 3);
        var opened = failed(tickets.dispatch(OPENED), 3);
        tickets.reportTo(hub, open, failed(tickets.dispatch(ASSIGN, open), 1), otherOpen, failed(tickets.dispatch(ASSIGN, otherOpen), 1), opened,
            failed(tickets.dispatch(ASSIGN, opened), 1));

        var causes = hub.failures(List.of(step(ASSIGN))).causes();

        assertThat(causes).singleElement().satisfies(cause -> {
            assertThat(cause.retained()).isEqualTo(3);
            assertThat(cause.latest()).extracting(invocation -> invocation.id().value()).hasSize(3).isSortedAccordingTo(Comparator.reverseOrder());
            assertThat(cause.chains()).extracting(chain -> chain.steps().stream().map(step -> step.stage().type()).toList(), FailureCause.Chain::occurrences)
                .containsExactly(tuple(List.of(OPEN, ASSIGN), 2), tuple(List.of(OPENED, ASSIGN), 1));
        });
    }

    @Test
    void failures_causesAskedOfWhichOneIsNotRetained_answersEachInTheOrderAsked() {
        tickets.reportTo(hub, failed(tickets.dispatch(OPEN), 1));

        var causes = hub.failures(List.of(step(ASSIGN), step(OPEN))).causes();

        assertThat(causes).extracting(FailureCause::cause, FailureCause::retained).containsExactly(tuple(step(ASSIGN), 0), tuple(step(OPEN), 1));
        assertThat(causes.getFirst().latest()).isEmpty();
        assertThat(causes.getFirst().chains()).isEmpty();
    }

    @Test
    void failures_askedOfOneEpochAgainForOtherCauses_answersEachAsAskedFirst() {
        var open = failed(tickets.dispatch(OPEN), 2);
        tickets.reportTo(hub, open, failed(tickets.dispatch(ASSIGN, open), 1), failed(tickets.dispatch(OPENED), 1));
        var epoch = hub.epoch();
        var assignFirst = hub.epoch().failures(List.of(step(ASSIGN)));

        var opened = epoch.failures(List.of(step(OPENED)));
        var assign = epoch.failures(List.of(step(ASSIGN), step(OPEN)));

        assertThat(opened.causes()).extracting(FailureCause::cause, FailureCause::retained).containsExactly(tuple(step(OPENED), 1));
        assertThat(assign.causes()).extracting(FailureCause::cause, FailureCause::retained).containsExactly(tuple(step(ASSIGN), 1), tuple(step(OPEN), 1));
        assertThat(assign.causes().getFirst()).isEqualTo(assignFirst.causes().getFirst());
    }

    @Test
    void failures_ofALaterEpoch_countsTheFailuresRetainedSinceWhileTheEarlierAnswersAsBefore() {
        tickets.reportTo(hub, failed(tickets.dispatch(OPEN), 1));
        var earlier = hub.epoch();
        earlier.failures(List.of(step(OPEN)));
        tickets.reportTo(hub, failed(tickets.dispatch(OPEN), 1));

        var later = hub.epoch().failures(List.of(step(OPEN)));

        assertThat(later.causes()).singleElement().satisfies(cause -> assertThat(cause.retained()).isEqualTo(2));
        assertThat(earlier.failures(List.of(step(OPEN))).causes()).singleElement().satisfies(cause -> assertThat(cause.retained()).isEqualTo(1));
    }

    @Test
    void failures_moreCausesThanTheLimit_rejectsWithContext() {
        var causes = Collections.nCopies(Epoch.MAX_CAUSES + 1, step(OPEN));

        assertThatThrownBy(() -> hub.failures(causes))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at most " + Epoch.MAX_CAUSES + " causes");
    }

    @Test
    void timeline_span_marksTheDispatchesThatRanInItLatestFirstWithTheirNearestMarkedAncestor() {
        var open = tickets.dispatch(OPEN);
        var opened = tickets.dispatch(OPENED, open);
        var handling = tickets.handle("assignments", opened);
        var assign = with(tickets.dispatch(ASSIGN, handling), STARTED_AT.plusMillis(1), new Outcome.Completed(Duration.ofMillis(1)));
        var old = with(tickets.dispatch(OPEN), STARTED_AT.minusSeconds(30), new Outcome.Completed(Duration.ofMillis(1)));
        tickets.reportTo(hub, open, opened, handling, assign, old);

        var window = hub.timeline(new TimelineQuery(InvocationFilter.EVERY_INVOCATION, Duration.ZERO, Duration.ofSeconds(10), 10));

        assertThat(window.marks()).extracting(mark -> mark.invocation().type(), TimelineWindow.Mark::root, TimelineWindow.Mark::markedAncestor)
            .containsExactly(tuple(ASSIGN, new InvocationId(1), Optional.of(new InvocationId(2))), tuple(OPENED, new InvocationId(1), Optional.of(new InvocationId(1))),
                tuple(OPEN, new InvocationId(1), Optional.empty()));
        assertThat(window.inSpan()).isEqualTo(3);
        assertThat(window.end()).isEqualTo(clock.instant());
    }

    @Test
    void timeline_limitBelowTheMatchingDispatches_marksOnlyTheLatestButCountsAll() {
        tickets.reportTo(hub, tickets.dispatch(OPEN), with(tickets.dispatch(OPEN), STARTED_AT.plusMillis(5), new Outcome.Completed(Duration.ZERO)),
            tickets.dispatch(ASSIGN));
        var filter = new InvocationFilter(Optional.of(TICKETS), Set.of(), new TypeSelection(Set.of(), Set.of(new ApplicationType(TICKETS, OPEN))), Set.of(), "");

        var window = hub.timeline(new TimelineQuery(filter, Duration.ZERO, Duration.ofSeconds(10), 1));

        assertThat(window.marks()).extracting(mark -> mark.invocation().id()).containsExactly(new InvocationId(2));
        assertThat(window.inSpan()).isEqualTo(3);
        assertThat(window.matching()).isEqualTo(2);
    }

    @Test
    void message_failedParentOfAFailedChild_listsItsLineageChildrenAndThePropagatedFailure() {
        var open = tickets.dispatch(OPEN);
        var assign = failed(tickets.dispatch(ASSIGN, open), 3);
        var child = failed(tickets.dispatch(OPEN, assign), 1);
        tickets.reportTo(hub, open, assign, child, tickets.dispatch(OPEN, assign));

        var detail = hub.message(new InvocationId(2));

        assertThat(detail).hasValueSatisfying(message -> {
            assertThat(message.root()).isEqualTo(new InvocationId(1));
            assertThat(message.ancestors()).extracting(ancestor -> ancestor.invocation().id()).containsExactly(new InvocationId(1));
            assertThat(message.children()).extracting(Invocation::id).containsExactly(new InvocationId(4), new InvocationId(6));
            assertThat(message.childCount()).isEqualTo(2);
            assertThat(message.propagatedFrom()).map(Invocation::id).contains(new InvocationId(4));
        });
    }

    @Test
    void message_evicted_isEmpty() {
        var hub = new SynchronousHub(HubConfig.withDefaults().build(), clock, Budgets.retaining(1));
        tickets.reportTo(hub, RecordingInstance.beforeTheWindow(tickets.dispatch(OPEN)), tickets.dispatch(OPEN));

        assertThat(hub.message(new InvocationId(1))).isEmpty();
        assertThat(hub.message(new InvocationId(2))).isPresent();
    }

    @Test
    void type_retainedDispatches_countsTheirFailureTypesAndListsTheLatestNewestFirst() {
        tickets.reportTo(hub, failed(tickets.dispatch(OPEN), 1), tickets.dispatch(OPEN), failed(tickets.dispatch(OPEN), 1), tickets.dispatch(ASSIGN));

        var type = hub.type(new ApplicationType(TICKETS, OPEN));

        assertThat(type.retained()).isEqualTo(3);
        assertThat(type.failureTypes()).containsExactly(new RetainedType.FailureTypeCount(FAILURE, 2));
        assertThat(type.latest()).extracting(Invocation::id).containsExactly(new InvocationId(3), new InvocationId(2), new InvocationId(1));
    }

    @Test
    void withMessageIdPrefix_prefixOfAMessageInScope_findsItsInvocationsNewestFirst() {
        var publication = tickets.dispatch(OPENED);
        tickets.reportTo(hub, publication, tickets.handle("ticket-view", publication), tickets.dispatch(OPEN));
        billing.reportTo(hub, billing.dispatch(OPEN));
        var prefix = publication.messageId().value().toString().substring(0, 8).toUpperCase();

        var found = hub.withMessageIdPrefix(Optional.of(TICKETS), prefix, 10);

        assertThat(found).extracting(Invocation::id).containsExactly(new InvocationId(2), new InvocationId(1));
    }

    @Test
    void withMessageIdPrefix_limitBeyondTheMaximum_rejectsWithContext() {
        assertThatThrownBy(() -> hub.withMessageIdPrefix(Optional.empty(), "abcd", Epoch.MAX_FOUND + 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("between 1 and 100", "101");
    }

    private static TraceQuery traceQuery(TraceOrder order, Optional<TraceCursor> after, int limit) {
        return new TraceQuery(InvocationFilter.EVERY_INVOCATION, false, order, after, limit);
    }

    private static MessageSpan timed(MessageSpan span, long millis) {
        return with(span, span.startedAt(), new Outcome.Completed(Duration.ofMillis(millis)));
    }

    private static FailureCause.Step step(MessageType type) {
        return new FailureCause.Step(TICKETS, new Stage.Dispatch(type), FAILURE);
    }

    private static MessageSpan failed(MessageSpan span, long millis) {
        return with(span, span.startedAt(), new Outcome.Failed(Duration.ofMillis(millis), FAILURE));
    }
}
