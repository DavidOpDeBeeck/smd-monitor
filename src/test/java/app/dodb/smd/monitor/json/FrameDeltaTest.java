package app.dodb.smd.monitor.json;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FrameDeltaTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void between_nothingChanged_isEmpty() {
        var frame = frame(10, List.of(item("tickets", 3, 0, 1, 2)), "note");

        var changes = FrameDelta.between(frame, frame, 0, mapper);

        assertThat(changes.isEmpty()).isTrue();
    }

    @Test
    void between_seriesMovedASecond_listsOnlyTheSecondsThatDifferFromTheMove() {
        var previous = frame(10, List.of(item("tickets", 3, 1, 2, 3)), "note");
        var current = frame(11, List.of(item("tickets", 3, 2, 3, 0)), "note");

        var changes = FrameDelta.between(previous, current, 1, mapper);

        assertThat(changes.toString()).isEqualTo("{\"endEpochSecond\":11}");
        assertAppliesTo(previous, current, changes, 1);
    }

    @Test
    void between_revisedSecondAndNewSecond_listsBoth() {
        var previous = frame(10, List.of(item("tickets", 3, 1, 2, 3)), "note");
        var current = frame(11, List.of(item("tickets", 3, 5, 3, 7)), "note");

        var changes = FrameDelta.between(previous, current, 1, mapper);

        assertThat(changes.at("/applications/$k/c/0/startedPerSecond").toString()).isEqualTo("{\"$s\":[[0,5],[2,7]]}");
        assertAppliesTo(previous, current, changes, 1);
    }

    @Test
    void between_recordsAddedRemovedAndReordered_changesTheListByPreviousPositions() {
        var previous = frame(10, List.of(item("billing", 1, 0), item("orders", 2, 0), item("tickets", 3, 0)), "note");
        var current = frame(10, List.of(item("accounts", 9, 0), item("billing", 1, 0), item("tickets", 4, 0)), "note");

        var changes = FrameDelta.between(previous, current, 0, mapper);

        var keyed = changes.at("/applications/$k");
        assertThat(keyed.get("r").toString()).isEqualTo("[1]");
        assertThat(keyed.get("c").toString()).isEqualTo("{\"2\":{\"count\":4}}");
        assertThat(keyed.get("a").get(0).get("name").asString()).isEqualTo("accounts");
        assertThat(keyed.get("o").toString()).isEqualTo("[2,0,1]");
        assertAppliesTo(previous, current, changes, 0);
    }

    @Test
    void between_valueBecameNull_replacesItWhole() {
        var previous = frame(10, List.of(), "note");
        var current = frame(10, List.of(), null);

        var changes = FrameDelta.between(previous, current, 0, mapper);

        assertThat(changes.toString()).isEqualTo("{\"note\":{\"$\":null}}");
        assertAppliesTo(previous, current, changes, 0);
    }

    @Test
    void between_mapValueChanged_listsTheChangedKey() {
        var previous = new Snapshot(10, List.of(), "note", Map.of("EVICTED", 1L, "DUPLICATE", 2L));
        var current = new Snapshot(10, List.of(), "note", Map.of("EVICTED", 5L, "DUPLICATE", 2L));

        var changes = FrameDelta.between(previous, current, 0, mapper);

        assertThat(changes.toString()).isEqualTo("{\"reasons\":{\"EVICTED\":5}}");
        assertAppliesTo(previous, current, changes, 0);
    }

    @Test
    void between_positionalRecordChanged_replacesItsArrayWhole() {
        var previous = frame(10, List.of(new Item("tickets", 3, List.of(0L), new Figures(3, 1.5), List.of())), "note");
        var current = frame(10, List.of(new Item("tickets", 3, List.of(0L), new Figures(4, 1.5), List.of())), "note");

        var changes = FrameDelta.between(previous, current, 0, mapper);

        assertThat(changes.at("/applications/$k/c/0").toString()).isEqualTo("{\"figures\":[4,1.5]}");
        assertAppliesTo(previous, current, changes, 0);
    }

    // Only the lists named as series move with the frame's end; another list of integers, or a positional record of them, keeps its place.
    @Test
    void between_seriesMovedWithIntegerListsNotNamedAsSeries_movesOnlyTheSeries() {
        var previous = frame(10, List.of(new Item("tickets", 3, List.of(1L, 2L), new Figures(1, 2.0), List.of(7L, 8L))), "note");
        var current = frame(11, List.of(new Item("tickets", 3, List.of(2L, 0L), new Figures(1, 2.0), List.of(7L, 8L))), "note");

        var changes = FrameDelta.between(previous, current, 1, mapper);

        assertThat(changes.toString()).isEqualTo("{\"endEpochSecond\":11}");
        assertAppliesTo(previous, current, changes, 1);
    }

    private void assertAppliesTo(Snapshot previous, Snapshot current, JsonNode changes, long shift) {
        assertThat(FrameDelta.apply(mapper.valueToTree(previous), changes, shift)).isEqualTo(mapper.valueToTree(current));
    }

    private static Snapshot frame(long endEpochSecond, List<Item> applications, String note) {
        return new Snapshot(endEpochSecond, applications, note, Map.of());
    }

    private static Item item(String name, long count, long... startedPerSecond) {
        return new Item(name, count, Arrays.stream(startedPerSecond).boxed().toList(), new Figures(0, 0.0), List.of());
    }

    public record Snapshot(long endEpochSecond, List<Item> applications, String note, Map<String, Long> reasons) {
    }

    public record Item(String name, long count, List<Long> startedPerSecond, Figures figures, List<Long> codes) {
    }

    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    public record Figures(long count, double millis) {
    }
}
