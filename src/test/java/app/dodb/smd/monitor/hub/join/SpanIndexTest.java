package app.dodb.smd.monitor.hub.join;

import app.dodb.smd.api.message.MessageId;
import app.dodb.smd.monitor.hub.InvocationId;
import app.dodb.smd.monitor.hub.budget.Footprint;
import app.dodb.smd.monitor.hub.span.ApplicationName;
import app.dodb.smd.monitor.hub.span.MessageKind;
import app.dodb.smd.monitor.hub.span.MessageType;
import app.dodb.smd.monitor.hub.span.SpanRef;
import app.dodb.smd.monitor.hub.span.Stage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SpanIndexTest {

    private static final ApplicationName TICKETS = new ApplicationName("ticket-service");
    private static final ApplicationName BILLING = new ApplicationName("billing-service");
    private static final ApplicationName ORDERS = new ApplicationName("order-service");
    private static final MessageType OPEN = new MessageType(MessageKind.COMMAND, "OpenTicket");
    private static final MessageType ASSIGN = new MessageType(MessageKind.COMMAND, "AssignTicket");
    private static final Duration HORIZON = Duration.ofSeconds(60);
    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    private final SpanIndex index = new SpanIndex(HORIZON);
    private long ids;

    @Test
    void receive_parentThenChild_linksTheChildOnce() {
        var parent = span();
        receive(TICKETS, OPEN, parent, Optional.empty(), NOW);

        var links = receive(TICKETS, ASSIGN, span(), Optional.of(parent), NOW);

        assertThat(links).singleElement().satisfies(link -> {
            assertThat(link.parent().type()).isEqualTo(OPEN);
            assertThat(link.child().type()).isEqualTo(ASSIGN);
            assertThat(link.delivered()).isFalse();
        });
    }

    @Test
    void receive_childThenParent_linksTheChildWhenTheParentArrives() {
        var parent = span();
        receive(TICKETS, ASSIGN, span(), Optional.of(parent), NOW);

        var links = receive(TICKETS, OPEN, parent, Optional.empty(), NOW);

        assertThat(links).singleElement().satisfies(link -> assertThat(link.child().type()).isEqualTo(ASSIGN));
    }

    @Test
    void expire_releasedSpanOlderThanTheHorizon_isForgotten() {
        var parent = span();
        receive(TICKETS, OPEN, parent, Optional.empty(), NOW);
        index.release(parent);

        index.expire(NOW.plus(HORIZON).plusSeconds(1));

        assertThat(index.knows(parent)).isFalse();
        assertThat(receive(TICKETS, ASSIGN, span(), Optional.of(parent), NOW.plus(HORIZON).plusSeconds(1))).isEmpty();
        assertThat(index.entries(TICKETS)).isEqualTo(1);
    }

    @Test
    void expire_retainedSpanOlderThanTheHorizon_staysUntilReleased() {
        var parent = span();
        receive(TICKETS, OPEN, parent, Optional.empty(), NOW);
        index.expire(NOW.plus(HORIZON).plusSeconds(1));
        var retained = index.knows(parent);

        index.release(parent);

        assertThat(retained).isTrue();
        assertThat(index.knows(parent)).isFalse();
    }

    @Test
    void expire_childWhoseParentNeverArrives_countsItOnceAnotherHorizonPassed() {
        var child = span();
        receive(TICKETS, ASSIGN, child, Optional.of(span()), NOW);
        index.release(child);
        index.expire(NOW.plus(HORIZON).plusSeconds(1));
        var leftTheHorizon = index.unlinked(TICKETS);

        index.expire(NOW.plus(HORIZON.multipliedBy(2)).plusSeconds(1));

        assertThat(leftTheHorizon).isEqualTo(Unlinked.NONE);
        assertThat(index.unlinked(TICKETS)).isEqualTo(new Unlinked(1, 0));
    }

    @Test
    void receive_parentAfterItsChildLeftTheHorizon_countsTheChildUnlinkedAfterTheHorizon() {
        var parent = span();
        var child = span();
        receive(BILLING, ASSIGN, child, Optional.of(parent), NOW);
        index.release(child);
        index.expire(NOW.plus(HORIZON).plusSeconds(1));

        var links = receive(TICKETS, OPEN, parent, Optional.empty(), NOW.plus(HORIZON).plusSeconds(1));

        assertThat(links).isEmpty();
        assertThat(index.unlinked(BILLING)).isEqualTo(new Unlinked(0, 1));
        assertThat(index.unlinked(TICKETS)).isEqualTo(Unlinked.NONE);
    }

    @Test
    void receiveSend_sendWithinTheHorizon_isKnownUntilItLeaves() {
        var send = span();
        index.receiveSend(send, TICKETS, NOW);
        var known = index.knows(send);

        index.expire(NOW.plus(HORIZON).plusSeconds(1));

        assertThat(known).isTrue();
        assertThat(index.knows(send)).isFalse();
    }

    @Test
    void fit_beyondTheAllowance_forgetsTheOldestOfTheApplicationUsingTheMostAndShortensItsHorizon() {
        var oldest = span();
        index.receiveSend(oldest, TICKETS, NOW.minusSeconds(20));
        index.receiveSend(span(), TICKETS, NOW);
        index.receiveSend(span(), TICKETS, NOW);
        index.receiveSend(span(), BILLING, NOW.minusSeconds(30));

        var fits = index.fit(3 * Footprint.SEND, NOW);

        assertThat(fits).isTrue();
        assertThat(index.knows(oldest)).isFalse();
        assertThat(index.entries(TICKETS)).isEqualTo(2);
        assertThat(index.entries(BILLING)).isEqualTo(1);
        assertThat(index.bytes()).isEqualTo(3 * Footprint.SEND);
        assertThat(index.effectiveHorizon(TICKETS)).isEqualTo(Duration.ofSeconds(20));
        assertThat(index.effectiveHorizon(BILLING)).isEqualTo(HORIZON);
    }

    @Test
    void fit_twoApplicationsAboveTheirShare_forgetsFromTheOneUsingTheMostUntilTheyUseAlikeAndKeepsTheOneWithinItsShare() {
        for (var sent = 0; sent < 6; sent++) {
            index.receiveSend(span(), TICKETS, NOW.minusSeconds(20));
        }
        for (var sent = 0; sent < 4; sent++) {
            index.receiveSend(span(), BILLING, NOW.minusSeconds(30));
        }
        index.receiveSend(span(), ORDERS, NOW.minusSeconds(40));

        var fits = index.fit(7 * Footprint.SEND, NOW);

        assertThat(fits).isTrue();
        assertThat(List.of(index.entries(TICKETS), index.entries(BILLING), index.entries(ORDERS))).containsExactly(3, 3, 1);
        assertThat(index.bytes()).isEqualTo(7 * Footprint.SEND);
        assertThat(index.effectiveHorizon(TICKETS)).isEqualTo(Duration.ofSeconds(20));
        assertThat(index.effectiveHorizon(BILLING)).isEqualTo(Duration.ofSeconds(30));
        assertThat(index.effectiveHorizon(ORDERS)).isEqualTo(HORIZON);
    }

    @Test
    void expire_afterAFit_growsTheEffectiveHorizonBackASecondAtATime() {
        index.receiveSend(span(), TICKETS, NOW.minusSeconds(20));
        index.receiveSend(span(), TICKETS, NOW);
        index.fit(Footprint.SEND, NOW);

        index.expire(NOW.plusSeconds(1));

        assertThat(index.effectiveHorizon(TICKETS)).isEqualTo(Duration.ofSeconds(21));
    }

    @Test
    void release_retainedSpanThatLeftTheHorizon_forgetsItWithoutCountingItAsJoinState() {
        var span = span();
        receive(TICKETS, OPEN, span, Optional.empty(), NOW);
        index.receiveSend(span(), TICKETS, NOW.plus(HORIZON));
        index.expire(NOW.plus(HORIZON).plusSeconds(1));

        index.release(span);

        assertThat(index.knows(span)).isFalse();
        assertThat(index.bytes()).isEqualTo(Footprint.SEND);
        assertThat(index.entries(TICKETS)).isEqualTo(1);
    }

    @Test
    void bytes_retainedSpan_isLeftToTheSpecimens() {
        receive(TICKETS, OPEN, span(), Optional.empty(), NOW);

        assertThat(index.bytes()).isZero();
        assertThat(index.bytes(TICKETS)).isZero();
    }

    @Test
    void forget_retiredApplication_forgetsItsEntriesAndItsChildrenAwaitingAParent() {
        var parent = span();
        var child = span();
        receive(BILLING, ASSIGN, child, Optional.of(parent), NOW);
        index.release(child);

        index.forget(BILLING);

        assertThat(index.knows(child)).isFalse();
        assertThat(receive(TICKETS, OPEN, parent, Optional.empty(), NOW)).isEmpty();
        assertThat(index.unlinked(BILLING)).isEqualTo(Unlinked.NONE);
    }

    @Test
    void forget_retiredApplicationAwaitingAParent_leavesTheBytesOfTheOthers() {
        var child = span();
        receive(BILLING, ASSIGN, child, Optional.of(span()), NOW);
        index.release(child);
        index.receiveSend(span(), BILLING, NOW);
        index.receiveSend(span(), TICKETS, NOW);

        index.forget(BILLING);

        assertThat(index.bytes()).isEqualTo(Footprint.SEND);
        assertThat(index.bytes(TICKETS)).isEqualTo(Footprint.SEND);
    }

    private List<Link> receive(ApplicationName application, MessageType type, SpanRef span, Optional<SpanRef> continues, Instant now) {
        var fact = new Fact(new InvocationId(++ids), application, new Stage.Dispatch(type), MessageId.generate(), NOW, Duration.ofMillis(1));
        return index.receive(span, continues, fact, now, true);
    }

    private SpanRef span() {
        var id = ++ids;
        return SpanRef.of(HexFormat.of().toHexDigits(0L) + HexFormat.of().toHexDigits(id), HexFormat.of().toHexDigits(id));
    }
}
