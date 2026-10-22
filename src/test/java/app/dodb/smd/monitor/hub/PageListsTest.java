package app.dodb.smd.monitor.hub;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// The page keeps the lists and the trace it asks the hub for as check-lists.ts describes: a first page merges into the top of a list, and what did
// not change stays the same object. Node runs the page's TypeScript as it is.
class PageListsTest {

    private static final Path SCRIPT = Path.of("frontend", "scripts", "check-lists.ts");

    @Test
    void checkLists_pageTypeScript_passesEveryCheck() throws Exception {
        assumeTrue(runs("node", "--version"), "node runs the page's TypeScript");

        var process = new ProcessBuilder("node", SCRIPT.toString()).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("node finished within a minute").isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains("fail 0");
    }

    private static boolean runs(String... command) throws InterruptedException {
        try {
            var process = new ProcessBuilder(List.of(command)).redirectErrorStream(true).start();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException missing) {
            return false;
        }
    }
}
