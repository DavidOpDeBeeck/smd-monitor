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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;

// The viewers of a saturation run, -Dsaturation.viewers=N: N open pages in a JVM of their own, as on the machines of the people viewing, so the
// hub's heap, collections, and CPU are its own. Each viewer connects to the hub's WebSocket as FrameClients connects, offering permessage-deflate,
// and its page (SaturationPages) asks its view's query whenever the viewer reads the frame of a later epoch. The run starts the viewers once it
// filled the hub, and its clock once every viewer read its first frame; when it ends, the viewers write every frame they read and every query
// they asked, which the run reads back. Both JVMs read System.nanoTime from the machine's monotonic clock, as HotSpot does on Linux, so the run
// compares the viewers' times with its own.
final class SaturationViewers implements AutoCloseable {

    private static final String VIEWERS_PROPERTY = "saturation.viewers";
    private static final String HEAP_PROPERTY = "saturation.viewerHeap";
    private static final String PREFIX = "saturation-viewers ";
    private static final Duration READY = Duration.ofMinutes(15);
    private static final Duration DONE = Duration.ofMinutes(5);

    private final Process process;
    private final Path directory;
    private final Writer commands;
    private final CountDownLatch ready = new CountDownLatch(1);
    private final CountDownLatch done = new CountDownLatch(1);
    private final AtomicReference<String> fault = new AtomicReference<>();
    private final AtomicLong disconnects = new AtomicLong();

    private SaturationViewers(Process process, Path directory) {
        this.process = requireNonNull(process);
        this.directory = requireNonNull(directory);
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

    // Starts a viewer per plan in a JVM of its own, with -Dsaturation.viewerHeap of heap, 4g by default, and waits until each read its first frame.
    static SaturationViewers start(URI live, URI api, List<SaturationPages.Plan> plans, Path output) throws IOException, InterruptedException {
        var directory = Files.createDirectories(output.resolve("viewers"));
        var plan = directory.resolve("plan.tsv");
        SaturationPages.writePlan(plan, plans);
        var classpath = directory.resolve("classpath.args");
        Files.writeString(classpath, "-cp \"" + System.getProperty("java.class.path").replace("\\", "\\\\") + "\"");
        var java = ProcessHandle.current().info().command()
            .orElseThrow(() -> new IllegalStateException("Saturation viewers cannot find the java command of this JVM to start their own"));
        var heap = System.getProperty(HEAP_PROPERTY, "4g");
        var process = new ProcessBuilder(java, "-Xms" + heap, "-Xmx" + heap, "@" + classpath, SaturationViewers.class.getName(), live.toString(), api.toString(),
            plan.toString(), directory.toString())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
        var viewers = new SaturationViewers(process, directory);
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

    // Stops the viewers and reads back what they read and asked.
    Viewed stop() throws IOException, InterruptedException {
        commands.write("stop\n");
        commands.flush();
        if (!done.await(DONE.toSeconds(), TimeUnit.SECONDS)) {
            throw new IllegalStateException("Saturation viewers did not stop within " + DONE);
        }
        process.waitFor(DONE.toSeconds(), TimeUnit.SECONDS);
        var frames = Files.readAllLines(directory.resolve("frames.csv")).stream().map(line -> line.split(","))
            .map(fields -> new FrameReceipt(Integer.parseInt(fields[0]), false, Long.parseLong(fields[1]), Boolean.parseBoolean(fields[2]),
                Long.parseLong(fields[3]), Long.parseLong(fields[4])))
            .toList();
        return new Viewed(frames, SaturationPages.readQueries(directory.resolve("queries.csv")));
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
                if (report.equals("ready")) {
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

    // Arguments: the hub's WebSocket address, its API's base address, the plan of the pages, and the directory to write what they read and asked.
    public static void main(String[] args) throws Exception {
        var live = URI.create(args[0]);
        var api = URI.create(args[1]);
        var plans = SaturationPages.readPlan(Path.of(args[2]));
        var directory = Path.of(args[3]);
        var receipts = new ConcurrentLinkedQueue<FrameReceipt>();
        var pages = SaturationPages.open(api, plans);
        var clients = FrameClients.of(live, FrameClients.configured().slowCharsPerSecond(), receipt -> {
            receipts.add(receipt);
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
        var rows = new ArrayList<String>();
        for (var receipt : receipts) {
            rows.add(receipt.client() + "," + receipt.epoch() + "," + receipt.snapshot() + "," + receipt.chars() + "," + receipt.receivedNanos());
        }
        Files.write(directory.resolve("frames.csv"), rows);
        pages.write(directory.resolve("queries.csv"));
        report("disconnects " + disconnects);
        report("done");
        // The HTTP and WebSocket clients may leave threads that are no daemons.
        System.exit(0);
    }

    private static void report(String line) {
        System.out.println(PREFIX + line);
        System.out.flush();
    }

    // What the viewers read of the frames and asked of the hub.
    record Viewed(List<FrameReceipt> frames, List<SaturationPages.Query> queries) {

        Viewed {
            requireNonNull(frames);
            requireNonNull(queries);
        }
    }
}
