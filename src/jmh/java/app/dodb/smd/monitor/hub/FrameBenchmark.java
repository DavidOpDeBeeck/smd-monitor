package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.json.Frame;
import app.dodb.smd.monitor.json.FrameView;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

// What the hub builds every second for its frame (W2, W3), against the budgets of 20 ms for an epoch and 100 KB for a delta: the epoch alone,
// whose statistics dominate it; the delta the publisher writes for every page in sync, from the frame's records and the changes since the epoch
// before; and the snapshot a page receives when it connects or missed a delta. The sizes of both are printed, as JSON and deflated as
// permessage-deflate sends them.
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class FrameBenchmark {

    private static final int SAMPLED_SECONDS = 30;

    private final ObjectMapper mapper = new ObjectMapper();
    private SteadyLoad load;
    private Frame previous;
    private Epoch epoch;

    @Setup(Level.Trial)
    public void fill() {
        load = SteadyLoad.filled();
        previous = frame(load.hub().epoch());
        var deltas = new ArrayList<Integer>();
        var deflatedDeltas = new ArrayList<Integer>();
        for (var second = 0; second < SAMPLED_SECONDS; second++) {
            load.nextSecond().forEach(load::receive);
            var frame = frame(load.hub().epoch());
            var delta = mapper.writeValueAsString(frame.deltaFrom(previous, mapper));
            deltas.add(bytes(delta));
            deflatedDeltas.add(deflated(delta));
            previous = frame;
        }
        var snapshot = mapper.writeValueAsString(previous.snapshot());
        System.out.printf("%nFrames of the steady load over %d seconds: a delta of %,d bytes at the median and %,d at most, deflated %,d and %,d; a snapshot of "
                + "%,d bytes, deflated %,d%n", SAMPLED_SECONDS, median(deltas), Collections.max(deltas), median(deflatedDeltas),
            Collections.max(deflatedDeltas), bytes(snapshot), deflated(snapshot));
    }

    // The next second of the load is received before the measurement.
    @Setup(Level.Invocation)
    public void nextSecond() {
        load.nextSecond().forEach(load::receive);
        epoch = load.hub().epoch();
    }

    @Benchmark
    public Epoch statistics() {
        return load.hub().epoch();
    }

    @Benchmark
    public String delta() {
        var frame = frame(epoch);
        var delta = mapper.writeValueAsString(frame.deltaFrom(previous, mapper));
        previous = frame;
        return delta;
    }

    @Benchmark
    public String snapshot() {
        return mapper.writeValueAsString(frame(epoch).snapshot());
    }

    private static Frame frame(Epoch epoch) {
        return new Frame(epoch.sequence(), FrameView.from(epoch.at(), epoch.statistics(), epoch.health(), OptionalLong.empty()));
    }

    private static int bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8).length;
    }

    private static int deflated(String json) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DeflaterOutputStream(bytes, new Deflater(Deflater.DEFAULT_COMPRESSION, true))) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return bytes.size();
    }

    private static int median(List<Integer> values) {
        var sorted = values.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }
}
