package app.dodb.smd.monitor.hub.catalog;

import app.dodb.smd.api.event.delivery.EventDelivery;
import app.dodb.smd.monitor.hub.HubConfig;
import app.dodb.smd.monitor.hub.span.MessageKind;
import app.dodb.smd.monitor.hub.span.MessageType;
import app.dodb.smd.monitor.hub.span.Stage;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogTest {

    private static final MessageType OPEN = new MessageType(MessageKind.COMMAND, "OpenTicket");
    private static final MessageType ASSIGN = new MessageType(MessageKind.COMMAND, "AssignTicket");
    private static final MessageType OPENED = new MessageType(MessageKind.EVENT, "TicketOpened");

    private final Catalog catalog = new Catalog(HubConfig.withDefaults().maxTypes(2).maxGroups(1).maxEdges(1).build());

    @Test
    void stage_stagesSeenAgain_keepTheirIdsInTheOrderFirstSeen() {
        var open = catalog.stage(new Stage.Dispatch(OPEN));
        var handling = catalog.stage(new Stage.Handling(OPEN, Optional.empty()));

        var again = catalog.stage(new Stage.Dispatch(OPEN));

        assertThat(again).isEqualTo(open).isEqualTo(catalog.firstStage());
        assertThat(handling).isEqualTo(open + 1);
        assertThat(catalog.stagesEnd()).isEqualTo(handling + 1);
        assertThat(catalog.stageOf(handling)).isEqualTo(new Stage.Handling(OPEN, Optional.empty()));
    }

    @Test
    void stage_typeBeyondMaxTypes_countsUnderTheOtherStageOfItsKindAndRole() {
        catalog.stage(new Stage.Dispatch(OPEN));
        catalog.stage(new Stage.Dispatch(ASSIGN));

        var dispatch = catalog.stage(new Stage.Dispatch(OPENED));
        var handling = catalog.stage(new Stage.Handling(OPENED, Optional.of("projection")));

        assertThat(dispatch).isEqualTo(Catalog.otherDispatch(MessageKind.EVENT));
        assertThat(handling).isEqualTo(Catalog.OTHER_HANDLING);
        assertThat(catalog.stagesEnd()).isEqualTo(catalog.firstStage() + 2);
    }

    @Test
    void stage_handlingOfAGroupBeyondMaxGroups_isTheOtherHandling() {
        catalog.stage(new Stage.Handling(OPENED, Optional.of("projection")));

        var beyond = catalog.stage(new Stage.Handling(OPENED, Optional.of("notifications")));

        assertThat(beyond).isEqualTo(Catalog.OTHER_HANDLING);
    }

    @Test
    void link_edgeBeyondMaxEdges_isTheOtherLink() {
        var first = catalog.link(new CausalLink(OPEN, ASSIGN));

        var beyond = catalog.link(new CausalLink(OPEN, OPENED));

        assertThat(first).isEqualTo(Catalog.OTHER + 1);
        assertThat(beyond).isEqualTo(Catalog.OTHER);
        assertThat(catalog.linksEnd()).isEqualTo(Catalog.OTHER + 2);
    }

    @Test
    void delivered_groupBeyondMaxGroups_keepsNoDelivery() {
        catalog.stage(new Stage.Handling(OPENED, Optional.of("projection")));

        catalog.delivered("projection", EventDelivery.SYNC);
        catalog.delivered("notifications", EventDelivery.SYNC);

        assertThat(catalog.delivery("projection")).isEqualTo(EventDelivery.SYNC);
        assertThat(catalog.delivery("notifications")).isNull();
    }
}
