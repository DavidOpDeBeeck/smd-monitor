package app.dodb.smd.monitor.hub.otlp;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// The fields of one protobuf message by number. Numeric values are read as longs and length-delimited values kept as bytes, read
// as strings or nested messages on demand. Every read checks its bounds, so a malformed message fails with an IllegalArgumentException.
final class ProtobufMessage {

    private final Map<Integer, List<Object>> fields = new HashMap<>();

    private ProtobufMessage() {
    }

    static ProtobufMessage parse(byte[] bytes) {
        var message = new ProtobufMessage();
        var input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        try {
            while (input.hasRemaining()) {
                var key = varint(input);
                var wireType = (int) (key & 0x7);
                Object value = switch (wireType) {
                    case 0 -> varint(input);
                    case 1 -> input.getLong();
                    case 2 -> {
                        var length = varint(input);
                        if (length < 0 || length > input.remaining()) {
                            throw new IllegalArgumentException("Protobuf field before byte " + input.position() + " declares " + length + " bytes; only "
                                + input.remaining() + " remain");
                        }
                        var delimited = new byte[(int) length];
                        input.get(delimited);
                        yield delimited;
                    }
                    case 5 -> (long) input.getInt();
                    default -> throw new IllegalArgumentException("Protobuf wire type " + wireType + " before byte " + input.position() + " is not supported");
                };
                message.fields.computeIfAbsent((int) (key >>> 3), ignored -> new ArrayList<>()).add(value);
            }
        } catch (BufferUnderflowException truncated) {
            throw new IllegalArgumentException("Protobuf message of " + bytes.length + " bytes ends within a field", truncated);
        }
        return message;
    }

    List<ProtobufMessage> messages(int field) {
        return values(field, byte[].class).stream().map(ProtobufMessage::parse).toList();
    }

    Optional<ProtobufMessage> message(int field) {
        return last(field, byte[].class).map(ProtobufMessage::parse);
    }

    Optional<String> string(int field) {
        return last(field, byte[].class).map(value -> new String(value, StandardCharsets.UTF_8));
    }

    byte[] bytes(int field) {
        return last(field, byte[].class).orElse(new byte[0]);
    }

    long number(int field) {
        return last(field, Long.class).orElse(0L);
    }

    private <T> Optional<T> last(int field, Class<T> type) {
        var values = values(field, type);
        return values.isEmpty() ? Optional.empty() : Optional.of(values.getLast());
    }

    private <T> List<T> values(int field, Class<T> type) {
        var values = fields.getOrDefault(field, List.of());
        if (!values.stream().allMatch(type::isInstance)) {
            throw new IllegalArgumentException("Protobuf field " + field + " requires " + (type == Long.class ? "a numeric" : "a length-delimited")
                + " wire type");
        }
        return values.stream().map(type::cast).toList();
    }

    private static long varint(ByteBuffer input) {
        var value = 0L;
        for (var shift = 0; shift < 64; shift += 7) {
            var current = input.get();
            value |= (long) (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return value;
            }
        }
        throw new IllegalArgumentException("Protobuf varint before byte " + input.position() + " is longer than 10 bytes");
    }
}
