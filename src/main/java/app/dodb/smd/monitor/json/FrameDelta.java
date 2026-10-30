package app.dodb.smd.monitor.json;

import com.fasterxml.jackson.annotation.JsonFormat;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.requireNonNull;

// The changes from the snapshot of one epoch's frame to the next, found by comparing the two snapshots key by key (W2, P5). A change is the
// new value of a number, string, or boolean; an object of the changes of its fields; {"$": value} for a value replaced whole; {"$s": [[index,
// value], ...]} for a per-second series; or {"$k": {...}} for a list of keyed records, by their positions in the previous list: "r" the
// positions removed, "c" the changes of the records kept by their previous position, "a" the records added at the end, and "o" the order of
// the kept and added records when it is not theirs. Records are keyed by the fields that name them, so the page applies a change without
// knowing the keys. A record written as an array changes as a whole new array. The series of the frame are the integer lists whose field
// name ends in "PerSecond"; every one ends at the frame's end second, so applying changes first moves every series of the previous snapshot
// by the shift, new seconds zero; a field left out did not change beyond that, and a listed second of a series is set after the move.
//
// The snapshots compared are the frame's records, not their JSON, which takes several times their heap; a record's fields are named as its
// JSON names them.
public final class FrameDelta {

    static final String REPLACE = "$";
    static final String SERIES = "$s";
    static final String KEYED = "$k";
    static final String SERIES_SUFFIX = "PerSecond";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    // The fields that name the records of each list of the frame, by the name of the list.
    private static final Map<String, List<List<String>>> KEYS = Map.ofEntries(
        Map.entry("applications", List.of(List.of("name"))),
        Map.entry("instances", List.of(List.of("name"))),
        Map.entry("types", List.of(List.of("kind"), List.of("name"))),
        Map.entry("groups", List.of(List.of("processingGroup"))),
        Map.entry("handlings", List.of(List.of("name"))),
        Map.entry("links", List.of(List.of("parent"), List.of("child"))),
        Map.entry("crossings", List.of(List.of("parentApplication"), List.of("link", "parent"), List.of("link", "child"))),
        Map.entry("upstream", List.of(List.of("application"))),
        Map.entry("routes", List.of(List.of("publisher"), List.of("event"), List.of("processingGroup"))),
        Map.entry("sinkRoutes", List.of(List.of("event"), List.of("delivery"))),
        Map.entry("groupLinks", List.of(List.of("processingGroup"), List.of("child"))),
        Map.entry("kinds", List.of(List.of("kind"))),
        Map.entry("failures", List.of(List.of("kind"), List.of("type"), List.of("handling"), List.of("processingGroup"), List.of("failureType"))));
    private static final Map<Class<?>, RecordComponent[]> COMPONENTS = new ConcurrentHashMap<>();

    private final ObjectMapper mapper;
    private final long shift;

    private FrameDelta(ObjectMapper mapper, long shift) {
        this.mapper = requireNonNull(mapper);
        this.shift = shift;
    }

    // The changes from the previous snapshot to the current one, whose series ended shift seconds later.
    public static ObjectNode between(Record previous, Record current, long shift, ObjectMapper mapper) {
        var changes = new FrameDelta(mapper, shift).changes(null, requireNonNull(previous), requireNonNull(current));
        return changes == null ? NODES.objectNode() : (ObjectNode) changes;
    }

    // The JSON of the current snapshot, from that of the previous one and the changes between them.
    public static JsonNode apply(JsonNode previous, JsonNode changes, long shift) {
        return applied(shifted(requireNonNull(previous), shift), requireNonNull(changes));
    }

    // Null when nothing changed.
    private JsonNode changes(String name, Object previous, Object current) {
        if (previous instanceof Record before && current instanceof Record after && before.getClass() == after.getClass()) {
            return positional(after.getClass()) ? (before.equals(after) ? null : mapper.valueToTree(after)) : recordChanges(before, after);
        }
        if (previous instanceof Map<?, ?> before && current instanceof Map<?, ?> after) {
            return mapChanges(before, after);
        }
        if (previous instanceof List<?> before && current instanceof List<?> after) {
            return listChanges(name, before, after);
        }
        if (Objects.equals(previous, current)) {
            return null;
        }
        return current instanceof Number || current instanceof String || current instanceof Boolean ? mapper.valueToTree(current) : replace(current);
    }

    private JsonNode recordChanges(Record previous, Record current) {
        var changes = NODES.objectNode();
        for (var component : components(current.getClass())) {
            var change = changes(component.getName(), value(component, previous), value(component, current));
            if (change != null) {
                changes.set(component.getName(), change);
            }
        }
        return changes.isEmpty() ? null : changes;
    }

    private JsonNode mapChanges(Map<?, ?> previous, Map<?, ?> current) {
        if (!previous.keySet().equals(current.keySet())) {
            return replace(current);
        }
        var changes = NODES.objectNode();
        current.forEach((key, value) -> {
            var change = changes(null, previous.get(key), value);
            if (change != null) {
                changes.set(String.valueOf(key), change);
            }
        });
        return changes.isEmpty() ? null : changes;
    }

    private JsonNode listChanges(String name, List<?> previous, List<?> current) {
        if (series(name) && !previous.isEmpty() && previous.size() == current.size() && shift >= 0) {
            return seriesChanges(previous, current);
        }
        var keys = KEYS.get(name);
        if (keys != null && records(previous) && records(current)) {
            var keyed = keyedChanges(keys, previous, current);
            if (keyed != null) {
                return keyed.isEmpty() ? null : NODES.objectNode().set(KEYED, keyed);
            }
        }
        return previous.equals(current) ? null : replace(current);
    }

    private JsonNode seriesChanges(List<?> previous, List<?> current) {
        var changed = NODES.arrayNode();
        for (var index = 0; index < current.size(); index++) {
            var moved = index + shift;
            var expected = moved < previous.size() ? ((Number) previous.get((int) moved)).longValue() : 0;
            var value = ((Number) current.get(index)).longValue();
            if (value != expected) {
                changed.add(NODES.arrayNode().add(index).add(value));
            }
        }
        return changed.isEmpty() ? null : NODES.objectNode().set(SERIES, changed);
    }

    // Null when the records cannot be keyed, as two share a key.
    private ObjectNode keyedChanges(List<List<String>> keys, List<?> previous, List<?> current) {
        var previousKeys = keys(keys, previous);
        var currentKeys = keys(keys, current);
        if (previousKeys == null || currentKeys == null) {
            return null;
        }
        var positions = new HashMap<List<Object>, Integer>();
        for (var position = 0; position < previousKeys.size(); position++) {
            positions.put(previousKeys.get(position), position);
        }
        var kept = new HashSet<>(currentKeys);
        var removed = NODES.arrayNode();
        var natural = new ArrayList<List<Object>>();
        for (var position = 0; position < previousKeys.size(); position++) {
            if (kept.contains(previousKeys.get(position))) {
                natural.add(previousKeys.get(position));
            } else {
                removed.add(position);
            }
        }
        var changed = NODES.objectNode();
        var added = NODES.arrayNode();
        for (var index = 0; index < current.size(); index++) {
            var key = currentKeys.get(index);
            var position = positions.get(key);
            if (position == null) {
                added.add(mapper.valueToTree(current.get(index)));
                natural.add(key);
                continue;
            }
            var change = changes(null, previous.get(position), current.get(index));
            if (change != null) {
                changed.set(String.valueOf(position), change);
            }
        }
        var result = NODES.objectNode();
        if (!removed.isEmpty()) {
            result.set("r", removed);
        }
        if (!changed.isEmpty()) {
            result.set("c", changed);
        }
        if (!added.isEmpty()) {
            result.set("a", added);
        }
        if (!natural.equals(currentKeys)) {
            var naturalPositions = new HashMap<List<Object>, Integer>();
            for (var position = 0; position < natural.size(); position++) {
                naturalPositions.put(natural.get(position), position);
            }
            var order = NODES.arrayNode();
            currentKeys.forEach(key -> order.add(naturalPositions.get(key)));
            result.set("o", order);
        }
        return result;
    }

    // Null when two records share a key: the values that name the record, compared as they are rather than written as text.
    private static List<List<Object>> keys(List<List<String>> keys, List<?> records) {
        var result = new ArrayList<List<Object>>(records.size());
        for (var record : records) {
            var key = new Object[keys.size()];
            for (var index = 0; index < key.length; index++) {
                Object value = record;
                for (var field : keys.get(index)) {
                    value = value == null ? null : field((Record) value, field);
                }
                key[index] = value;
            }
            result.add(Arrays.asList(key));
        }
        return new HashSet<>(result).size() == result.size() ? result : null;
    }

    private ObjectNode replace(Object value) {
        return NODES.objectNode().set(REPLACE, value == null ? NODES.nullNode() : mapper.valueToTree(value));
    }

    // A copy of the snapshot whose series moved by the shift.
    private static JsonNode shifted(JsonNode node, long shift) {
        if (node.isObject()) {
            var result = NODES.objectNode();
            for (var field : node.propertyNames()) {
                var value = node.get(field);
                result.set(field, series(field) && value.isArray() ? moved(value, shift) : shifted(value, shift));
            }
            return result;
        }
        if (node.isArray()) {
            var result = NODES.arrayNode();
            node.forEach(element -> result.add(shifted(element, shift)));
            return result;
        }
        return node;
    }

    private static JsonNode moved(JsonNode series, long shift) {
        var result = NODES.arrayNode();
        for (var index = 0; index < series.size(); index++) {
            var moved = index + shift;
            result.add(moved < series.size() ? series.get((int) moved) : NODES.numberNode(0L));
        }
        return result;
    }

    // Applies the change to a previous value whose series moved already.
    private static JsonNode applied(JsonNode previous, JsonNode change) {
        if (change.isObject() && change.size() == 1 && change.has(REPLACE)) {
            return change.get(REPLACE);
        }
        if (change.isObject() && change.size() == 1 && change.has(SERIES)) {
            var result = ((ArrayNode) previous).deepCopy();
            change.get(SERIES).forEach(entry -> result.set(entry.get(0).asInt(), entry.get(1)));
            return result;
        }
        if (change.isObject() && change.size() == 1 && change.has(KEYED)) {
            return appliedKeyed((ArrayNode) previous, change.get(KEYED));
        }
        if (change.isObject()) {
            var result = ((ObjectNode) previous).deepCopy();
            for (var field : change.propertyNames()) {
                result.set(field, applied(previous.get(field), change.get(field)));
            }
            return result;
        }
        return change;
    }

    private static ArrayNode appliedKeyed(ArrayNode previous, JsonNode change) {
        var removed = new HashSet<Integer>();
        if (change.has("r")) {
            change.get("r").forEach(position -> removed.add(position.asInt()));
        }
        var records = new ArrayList<JsonNode>();
        for (var position = 0; position < previous.size(); position++) {
            if (removed.contains(position)) {
                continue;
            }
            var recordChange = change.has("c") ? change.get("c").get(String.valueOf(position)) : null;
            records.add(recordChange == null ? previous.get(position) : applied(previous.get(position), recordChange));
        }
        if (change.has("a")) {
            change.get("a").forEach(records::add);
        }
        var result = NODES.arrayNode();
        if (change.has("o")) {
            change.get("o").forEach(position -> result.add(records.get(position.asInt())));
        } else {
            records.forEach(result::add);
        }
        return result;
    }

    private static boolean series(String name) {
        return name != null && name.endsWith(SERIES_SUFFIX);
    }

    private static boolean positional(Class<?> type) {
        var format = type.getAnnotation(JsonFormat.class);
        return format != null && format.shape() == JsonFormat.Shape.ARRAY;
    }

    private static boolean records(List<?> values) {
        return values.stream().allMatch(value -> value instanceof Record);
    }


    private static RecordComponent[] components(Class<?> type) {
        return COMPONENTS.computeIfAbsent(type, Class::getRecordComponents);
    }

    private static Object field(Record record, String name) {
        for (var component : components(record.getClass())) {
            if (component.getName().equals(name)) {
                return value(component, record);
            }
        }
        throw new IllegalStateException("Record " + record.getClass().getSimpleName() + " has no field '" + name + "' to key it by");
    }

    private static Object value(RecordComponent component, Record record) {
        try {
            return component.getAccessor().invoke(record);
        } catch (IllegalAccessException | InvocationTargetException failure) {
            throw new IllegalStateException("Unable to read " + component.getName() + " of " + record.getClass().getSimpleName() + " to compare frames", failure);
        }
    }
}
