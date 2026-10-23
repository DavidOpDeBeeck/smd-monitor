import java.time.Instant
import java.time.temporal.ChronoUnit

plugins {
    java
    application
    alias(libs.plugins.jmh)
}

group = "app.dodb"
version = libs.versions.smd.monitor.get()

base {
    archivesName = "smd-monitor-hub"
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
}

// ./gradlew installDist writes the runnable hub to build/install/smd-monitor-hub: its jars in lib, and bin/smd-monitor-hub to start it.
application {
    applicationName = "smd-monitor-hub"
    mainClass = "app.dodb.smd.monitor.hub.javalin.MonitorHubServer"
}

repositories {
    mavenCentral()
    mavenLocal()
}

val integrationTest = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    implementation(platform(libs.opentelemetry.bom))
    implementation(libs.smd.api)
    implementation(libs.smd.opentelemetry)
    implementation(libs.javalin)
    implementation(libs.avaje.config)
    implementation(libs.jackson.databind)
    runtimeOnly(libs.avaje.simple.logger)
    compileOnly(libs.opentelemetry.sdk.trace)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.awaitility)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.opentelemetry.sdk.testing)
    // The OTLP exporter's own encoder, so decoding is tested against what exporters send.
    testImplementation(libs.opentelemetry.exporter.otlp.common)

    testImplementation(libs.jetty.websocket.jakarta.client)

    // The monitored applications that export to the hub are Spring Boot applications, as SMD's are.
    "integrationTestImplementation"(platform(libs.spring.boot.dependencies))
    "integrationTestImplementation"(libs.smd.spring.boot.starter)
    "integrationTestImplementation"(libs.bundles.spring.boot.opentelemetry.tracing)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxParallelForks = Runtime.getRuntime().availableProcessors()
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
    description = "Runs integration tests"
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(integrationTestTask)
}

// The browser UI is a SolidJS + Tailwind project bundled by Vite; it needs Node.js 22.12 or newer on the PATH.
val frontend = layout.projectDirectory.dir("frontend")

// npm is a batch script on Windows, which a process can only start through cmd.
fun npm(vararg arguments: String): List<String> =
    if (System.getProperty("os.name").startsWith("Windows")) listOf("cmd", "/c", "npm", *arguments) else listOf("npm", *arguments)

val installMonitorUi = tasks.register<Exec>("installMonitorUi") {
    description = "Installs the locked npm dependencies of the monitor UI"
    group = "build"
    workingDir = frontend.asFile
    // Not `npm ci`: it deletes node_modules first, which fails on Windows while an IDE has a native module loaded.
    commandLine(npm("install", "--no-audit", "--no-fund"))
    inputs.files(frontend.file("package.json"), frontend.file("package-lock.json"))
    outputs.file(frontend.file("node_modules/.package-lock.json"))
}

val buildMonitorUi = tasks.register<Exec>("buildMonitorUi") {
    description = "Type-checks and bundles the monitor UI into classpath resources under smd-monitor/"
    group = "build"
    dependsOn(installMonitorUi)
    workingDir = frontend.asFile
    commandLine(npm("run", "build"))
    inputs.dir(frontend.dir("src"))
    inputs.files(frontend.file("index.html"), frontend.file("vite.config.ts"), frontend.file("tsconfig.json"), frontend.file("package-lock.json"))
    outputs.dir(layout.buildDirectory.dir("generated/monitor-ui"))
}

sourceSets.main {
    resources.srcDir(buildMonitorUi)
}

val demo = sourceSets.create("demo") {
    compileClasspath += sourceSets.main.get().output + configurations.runtimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

dependencies {
    "demoImplementation"(platform(libs.spring.boot.dependencies))
    "demoImplementation"(libs.smd.spring.boot.starter)
    "demoImplementation"(libs.bundles.spring.boot.opentelemetry.tracing)
}

tasks.register<JavaExec>("demo") {
    description = "Runs a monitor hub with a ticket service and a billing service exporting their spans to it"
    group = "application"
    classpath = demo.runtimeClasspath
    mainClass.set("app.dodb.smd.monitor.hub.demo.HubDemo")
}

// Benchmarks of ingest, frames, and queries at the design targets of docs/monitor-hub-design.md; they share the load generator of the tests.
// Narrow them with -PjmhIncludes=<regex>, for example -PjmhIncludes=WriterBenchmark.
jmh {
    jmhVersion = libs.versions.jmh
    includeTests = true
    fork = 1
    warmupIterations = 2
    warmup = "5s"
    iterations = 3
    timeOnIteration = "5s"
    jvmArgs = listOf("-Xms12g", "-Xmx12g")
    resultFormat = "JSON"
    providers.gradleProperty("jmhIncludes").orNull?.let { includes = listOf(it) }
    // For example -PjmhProfilers=stack, to see where a benchmark spends its time.
    providers.gradleProperty("jmhProfilers").orNull?.let { profilers = it.split(",") }
}

// Names the JMH run as the saturation runs name theirs, by when it started and the commit it measures, marked when the working tree differs, and
// the machine (-PbenchmarkMachine), in build/results/jmh/run.json beside the results, so ./gradlew benchmarkRecord can keep it.
tasks.named("jmh") {
    val stamp = layout.buildDirectory.file("results/jmh/run.json")
    val machine = providers.gradleProperty("benchmarkMachine")
    val root = layout.projectDirectory.asFile
    doFirst {
        fun git(vararg arguments: String) = ProcessBuilder("git", *arguments).directory(root).start().inputStream.bufferedReader().readText().trim()
        val changed = git("status", "--porcelain", "--untracked-files=no").isNotEmpty()
        val run = "${Instant.now().truncatedTo(ChronoUnit.SECONDS)} ${git("rev-parse", "--short", "HEAD")}${if (changed) " with changes" else ""}"
        val described = machine.orNull ?: "${System.getProperty("os.arch")}, ${Runtime.getRuntime().availableProcessors()} threads, ${System.getProperty("os.name")}"
        val json = { value: String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("{\"run\":${json(run)},\"machine\":${json(described)}}\n")
    }
}

// The JVM of a saturation run: the heap the benchmarks use, a GC log per process in the output directory, which the JVM opens before the run
// creates it, and optionally a flight recording, for example -PsaturationJfr=build/saturation/B100.jfr, to see where the writer and the
// collector spend their time.
fun JavaExec.saturationJvm(output: Provider<Directory>) {
    group = "verification"
    classpath = sourceSets["jmh"].runtimeClasspath
    jvmArgs("-Xms12g", "-Xmx12g", "-Xlog:gc:file=${output.get().file("gc-%p.log").asFile.path}")
    providers.gradleProperty("saturationJfr").orNull?.let { jvmArgs("-XX:StartFlightRecording=filename=$it,settings=profile") }
    // For example -PsaturationJvmArgs="-XX:+UseZGC", to measure with another collector than the default G1.
    providers.gradleProperty("saturationJvmArgs").orNull?.let { jvmArgs(it.split(" ").filter(String::isNotBlank)) }
    // For example -PbenchmarkMachine="i5-12600K, 64 GB, Windows 11", to name the machine in the results; described from the JVM otherwise.
    providers.gradleProperty("benchmarkMachine").orNull?.let { jvmArgs("-Dsaturation.machine=$it") }
    // For example -PsaturationClients=4 -PsaturationSlowClients=1, to publish the frames over the hub's WebSocket to clients and measure what they read
    // and how late (FrameClients); the slow ones read 10,000,000 characters of JSON a second, or -PsaturationSlowClientRate.
    providers.gradleProperty("saturationClients").orNull?.let { jvmArgs("-Dsaturation.clients=$it") }
    providers.gradleProperty("saturationSlowClients").orNull?.let { jvmArgs("-Dsaturation.slowClients=$it") }
    providers.gradleProperty("saturationSlowClientRate").orNull?.let { jvmArgs("-Dsaturation.slowClientRate=$it") }
    // For example -PsaturationTransport=otlp, or otlp-gzip, to export the batches to the hub's OTLP server over HTTP as exporters do instead of
    // submitting them, and measure how long it takes to answer (SaturationExporters).
    providers.gradleProperty("saturationTransport").orNull?.let { jvmArgs("-Dsaturation.transport=$it") }
    // For example -PsaturationViewers=64, to have that many open pages in a JVM of their own read the frames from the hub's WebSocket and ask its query
    // API for their views, and measure what they read and how long the hub takes to answer (SaturationViewers); their JVM's heap is
    // -PsaturationViewerHeap, 4g by default.
    providers.gradleProperty("saturationViewers").orNull?.let { jvmArgs("-Dsaturation.viewers=$it") }
    providers.gradleProperty("saturationViewerHeap").orNull?.let { jvmArgs("-Dsaturation.viewerHeap=$it") }
    // For example -PsaturationViewerJfr=build/viewers.jfr, to record the viewers' JVM as -PsaturationJfr records the hub's.
    providers.gradleProperty("saturationViewerJfr").orNull?.let { jvmArgs("-Dsaturation.viewerJfr=$it") }
    // For example -PsaturationQueryCap=16, to let the hub run that many queries at once instead of its own limit, to tune it.
    providers.gradleProperty("saturationQueryCap").orNull?.let { jvmArgs("-Dsaturation.queryCap=$it") }
    // For example -PsaturationFrameInterval=5, to have the publisher send a frame every 5 seconds in place of every second, to explore a slower tick.
    providers.gradleProperty("saturationFrameInterval").orNull?.let { jvmArgs("-Dsaturation.frameInterval=$it") }
    doFirst { output.get().asFile.mkdirs() }
}

// Open-loop saturation runs of the hub with its writer thread, which raise the span rate step by step until it sheds, falls behind, or refuses;
// see SaturationScenario. By default it runs the core scenarios (the reference, 500 applications, and the 256 MB budget); choose others with
// -PsaturationScenarios=all, quick (the reference and the profiles), a sweep such as applications, or IDs such as applications-500, and shorten the history filled first with -PsaturationFill=PT2M. Offer only some rates instead of the ladder with
// -PsaturationRates=40000,50000,60000, and write into another directory of build than saturation with -PsaturationOutput=saturation-probe, so a
// probe leaves the full run's results alone. Each run prints a line per rate after where the run stands and about how long it still takes, keeps
// the latest in <output>/progress.txt, appends a table per scenario to <output>/summary.md, and writes every second to <output>/<scenario>.csv.
tasks.register<JavaExec>("saturation") {
    description = "Runs the hub's open-loop saturation scenarios"
    val output = layout.buildDirectory.dir(providers.gradleProperty("saturationOutput").orElse("saturation"))
    saturationJvm(output)
    mainClass.set("app.dodb.smd.monitor.hub.SaturationRun")
    val scenarios = providers.gradleProperty("saturationScenarios").orElse("core")
    val fill = providers.gradleProperty("saturationFill").orElse("-")
    val rates = providers.gradleProperty("saturationRates")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(scenarios.get(), output.get().asFile.path, fill.get()) + listOfNotNull(rates.orNull)
    })
}

// How many open UIs the hub serves while it ingests, and at what cost (SaturationViewerLadder): a run without viewers, then one per number of viewers
// and query cap, each filling a minute of the reference scenario and offering one rate, into build/saturation-viewers; about 25 minutes. Narrow it
// with -PsaturationViewerCounts=1,16,64 and -PsaturationQueryCaps=4,64, and offer another rate with -PsaturationViewerRate=80000.
tasks.register<JavaExec>("saturationViewers") {
    description = "Runs the viewer ladder: open UIs against what they get and what they cost the hub"
    val output = layout.buildDirectory.dir("saturation-viewers")
    saturationJvm(output)
    mainClass.set("app.dodb.smd.monitor.hub.SaturationViewerLadder")
    val counts = providers.gradleProperty("saturationViewerCounts").orElse("")
    val caps = providers.gradleProperty("saturationQueryCaps").orElse("")
    val rate = providers.gradleProperty("saturationViewerRate").orElse("")
    argumentProviders.add(CommandLineArgumentProvider { listOf(output.get().asFile.path, counts.get(), caps.get(), rate.get()) })
}

// How many open UIs the hub serves at which span rate (SaturationCapacity): the highest rate it keeps up with without viewers, then for 1, 2, 4, and
// so on viewers the highest at which their UIs stay usable, until even the lowest is not; each climb fills a minute of the reference scenario,
// into build/saturation-capacity.
tasks.register<JavaExec>("saturationCapacity") {
    description = "Finds the frontier of span rate and open UIs the hub serves usably"
    val output = layout.buildDirectory.dir("saturation-capacity")
    saturationJvm(output)
    mainClass.set("app.dodb.smd.monitor.hub.SaturationCapacity")
    argumentProviders.add(CommandLineArgumentProvider { listOf(output.get().asFile.path) })
}

// Checks the throughput docs/monitor.md documents for the reference machine, scenario by scenario (SaturationCheck); about 40 minutes on a quiet
// machine, so it is left out of check. Narrow it with -PsaturationScenarios=profiles, for example.
tasks.register<JavaExec>("saturationCheck") {
    description = "Checks that the hub sustains the throughput its documentation promises"
    val output = layout.buildDirectory.dir("saturation-check")
    saturationJvm(output)
    mainClass.set("app.dodb.smd.monitor.hub.SaturationCheck")
    val scenarios = providers.gradleProperty("saturationScenarios").orElse("")
    argumentProviders.add(CommandLineArgumentProvider { listOf(output.get().asFile.path, scenarios.get()) })
}

// The benchmarks' one process (docs/benchmarks/README.md): run one of the tasks above on a committed tree, then keep its run with benchmarkRecord.
// docs/benchmarks/benchmarks.py does the work; it runs with the py launcher on Windows, where python often opens the Microsoft Store instead, and
// python3 elsewhere; choose another with -PbenchmarkPython=/path/to/python.
fun Exec.benchmarks(vararg arguments: String) {
    group = "verification"
    val windows = System.getProperty("os.name").startsWith("Windows")
    workingDir = layout.projectDirectory.asFile
    executable = providers.gradleProperty("benchmarkPython").orElse(if (windows) "py" else "python3").get()
    args("-I", "docs/benchmarks/benchmarks.py", *arguments)
}

// Keeps every run the benchmark tasks wrote for HEAD (build/saturation, build/saturation-capacity, build/saturation-viewers, build/results/jmh) as a
// run file in docs/benchmarks/runs, named -PbenchmarkName="Scoped specimen walks", and writes the page. -PbenchmarkResults=saturation=<dir>,... records
// runs from elsewhere instead, such as main measured in another worktree.
tasks.register<Exec>("benchmarkRecord") {
    description = "Keeps the benchmark runs of HEAD in docs/benchmarks/runs and writes the benchmarks page"
    val name = providers.gradleProperty("benchmarkName")
    val results = providers.gradleProperty("benchmarkResults").orElse("")
    benchmarks("record")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--name", name.orNull ?: throw GradleException("Name the runs with -PbenchmarkName=\"<what they measure>\", such as the change")) +
                results.get().split(",").filter(String::isNotBlank).flatMap { listOf("--results", it.trim()) }
    })
}

// Writes the benchmarks page, build/benchmarks/page.html, from the runs in docs/benchmarks/runs.
tasks.register<Exec>("benchmarkPage") {
    description = "Writes the benchmarks page from docs/benchmarks/runs"
    benchmarks("page")
}

// Keeps the benchmarks compiling as the hub changes; running them is left to the jmh task.
tasks.check {
    dependsOn(tasks.jmhClasses)
}