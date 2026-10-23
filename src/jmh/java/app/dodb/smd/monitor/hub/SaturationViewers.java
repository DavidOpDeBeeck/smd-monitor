package app.dodb.smd.monitor.hub;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

// The viewers of a saturation run, -Dsaturation.viewers=N: N open pages in a JVM of their own, as on the machines of the people viewing, so the
// hub's heap, collections, and CPU are its own. Each viewer connects to the hub's WebSocket as FrameClients connects, offering permessage-deflate,
// and its page (SaturationPages) asks its view's query whenever the viewer reads the frame of a later epoch. The run starts the viewers once it
// filled the hub, and its clock once every viewer read its first frame; the viewers report every frame they read and every query as it is
// answered, a line each, so the run judges each step as it ends. Both JVMs read System.nanoTime from the machine's monotonic clock, as HotSpot
// does on Linux, so the run compares the viewers' times with its own.
final class SaturationViewers implements AutoCloseable {

    private static final String VIEWERS_PROPERTY = "saturation.viewers";
    private static final String HEAP_PROPERTY = "saturation.viewerHeap";
    private static final String JFR_PROPERTY = "saturation.viewerJfr";
    private static final String PREFIX = "saturation-viewers ";
    private static final String FRAME = "frame ";
    private static final String QUERY = "query ";
    private static final Duration READY = Duration.ofMinutes(15);
    private static final Duration DONE = Duration.ofMinutes(5);

    private final Process process;
    private final Writer commands;
    private final Consumer<FrameReceipt> read;
    private final Consumer<SaturationPages.Query> asked;
    private final CountDownLatch ready = new CountDownLatch(1);
    private final CountDownLatch done = new CountDownLatch(1);
    private final AtomicReference<String> fault = new AtomicReference<>();
    private final AtomicLong disconnects = new AtomicLong();

    private SaturationViewers(Process process, Consumer<FrameReceipt> read, Consumer<SaturationPages.Query> asked) {
        this.process = requireNonNull(process);
        this.read = requireNonNull(read);
        this.asked = requireNonNull(asked);
        this.commands = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        Thread.ofPlatform().name("saturation-viewers-output").daemon().start(this::follow);
    }

    // The viewers -Dsaturation.viewers asks for, none by default.
    static int configured() {
        var value = System.getProperty(VIEWERS_PROPERTY, "0");
        try {
            var viewers = Integer.parseInt(value.strip());
            if (viewers >= 0) {
                return viewers;
            }
        } catch (NumberFormatException ignored) {
            // Reported below.
        }
        throw new IllegalArgumentException("Saturation run expects -D" + VIEWERS_PROPERTY + " to be a whole number, 0 or more; got '" + value + "'");
    }

    // Starts a viewer per plan in a JVM of its own, with -Dsaturation.viewerHeap of heap, 4g by default, and with -Dsaturation.viewerJfr a flight
    // recording into that file, and waits until each read its first frame.
    // The listeners hear every frame read and every query on the thread that follows the viewers' output.
    static SaturationViewers start(URI live, URI api, List<SaturationPages.Plan> plans, Path output, Consumer<FrameReceipt> read,
                                   Consumer<SaturationPages.Query> asked) throws IOException, InterruptedException {
        var directory = Files.createDirectories(output.resolve("viewers"));
        var plan = directory.resolve("plan.tsv");
        SaturationPages.writePlan(plan, plans);
        var classpath = directory.resolve("classpath.args");
        Files.writeString(classpath, "-cp \"" + System.getProperty("java.class.path").replace("\\", "\\\\") + "\"");
        var java = ProcessHandle.current().info().command()
            .orElseThrow(() -> new IllegalStateException("Saturation viewers cannot find the java command of this JVM to start their own"));
        var heap = System.getProperty(HEAP_PROPERTY, "4g");
        var command = new ArrayList<>(List.of(java, "-Xms" + heap, "-Xmx" + heap));
        Optional.ofNullable(System.getProperty(JFR_PROPERTY)).ifPresent(file -> command.add("-XX:StartFlightRecording=filename=" + file + ",settings=profile"));
        command.addAll(List.of("@" + classpath, SaturationViewers.class.getName(), live.toString(), api.toString(), plan.toString()));
        var process = new ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
        var viewers = new SaturationViewers(process, read, asked);
        if (!viewers.ready.await(READY.toSeconds(), TimeUnit.SECONDS) || !process.isAlive()) {
            viewers.close();
            throw new IllegalStateException("Saturation viewers did not all read a first frame within " + READY + viewers.fault().map(each -> ": " + each).orElse(""));
        }
        return viewers;
    }

    Optional<String> fault() {
        return Optional.ofNullable(fault.get());
    }

    // The CPU the viewers' JVM used so far.
    long cpuNanos() {
        return process.toHandle().info().totalCpuDuration().map(Duration::toNanos).orElse(0L);
    }

    long disconnects() {
        return disconnects.get();
    }

    // Stops the viewers once they reported everything they read and asked.
    void stop() throws IOException, InterruptedException {
        commands.write("stop\n");
        commands.flush();
        if (!done.await(DONE.toSeconds(), TimeUnit.SECONDS)) {
            throw new IllegalStateException("Saturation viewers did not stop within " + DONE);
        }
        process.waitFor(DONE.toSeconds(), TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        process.destroy();
    }

    // Lines of the viewers' JVM that start with the prefix report to the run; the rest, such as the client's logging, pass through.
    private void follow() {
        try (var lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            for (var line = lines.readLine(); line != null; line = lines.readLine()) {
                if (!line.startsWith(PREFIX)) {
                    System.out.println(line);
                    continue;
                }
                var report = line.substring(PREFIX.length());
                if (report.startsWith(FRAME)) {
                    var fields = report.substring(FRAME.length()).split(",");
                    read.accept(new FrameReceipt(Integer.parseInt(fields[0]), false, Long.parseLong(fields[1]), Boolean.parseBoolean(fields[2]),
                        Long.parseLong(fields[3]), Long.parseLong(fields[4])));
                } else if (report.startsWith(QUERY)) {
                    asked.accept(SaturationPages.Query.parse(report.substring(QUERY.length())));
                } else if (report.equals("ready")) {
                    ready.countDown();
                } else if (report.equals("done")) {
                    done.countDown();
                } else if (report.startsWith("disconnects ")) {
                    disconnects.set(Long.parseLong(report.substring("disconnects ".length())));
                } else if (report.startsWith("fault ")) {
                    fault.compareAndSet(null, "viewers' " + report.substring("fault ".length()));
                }
            }
        } catch (IOException ignored) {
            // The viewers' JVM ended.
        } finally {
            fault.compareAndSet(null, process.isAlive() || done.getCount() == 0 ? null : "viewers' JVM ended unexpectedly");
            ready.countDown();
            done.countDown();
        }
    }

    // Arguments: the hub's WebSocket address, its API's base address, and the plan of the pages.
    public static void main(String[] args) throws Exception {
        var live = URI.create(args[0]);
        var api = URI.create(args[1]);
        var plans = SaturationPages.readPlan(Path.of(args[2]));
        var pages = SaturationPages.open(api, plans, query -> report(QUERY + query.line()));
        var clients = FrameClients.of(live, FrameClients.configured().slowCharsPerSecond(), receipt -> {
            report(FRAME + receipt.client() + "," + receipt.epoch() + "," + receipt.snapshot() + "," + receipt.chars() + "," + receipt.receivedNanos());
            pages.read(receipt.client(), receipt.epoch());
        });
        clients.connect(plans.size(), false);
        clients.awaitFirstFrames();
        report("ready");
        var watcher = Thread.ofPlatform().daemon().start(() -> {
            try {
                while (pages.fault().isEmpty()) {
                    Thread.sleep(Duration.ofMillis(200));
                }
                report("fault " + pages.fault().get());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (var line = input.readLine(); line != null && !line.equals("stop"); line = input.readLine()) {
            // Only stop is a command.
        }
        watcher.interrupt();
        var disconnects = clients.disconnects(false);
        pages.close();
        clients.close();
        report("disconnects " + disconnects);
        report("done");
        // The HTTP and WebSocket clients may leave threads that are no daemons.
        System.exit(0);
    }

    // Viewers and pages report from their own threads; a line is printed whole.
    private static void report(String line) {
        System.out.println(PREFIX + line);
        System.out.flush();
    }
}
