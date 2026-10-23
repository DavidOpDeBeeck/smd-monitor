package app.dodb.smd.monitor.hub;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// What the pages of one step of a saturation run asked of the hub after the epochs of the step's seconds after it settled: the queries, those
// it answered too many at once and those that failed, which leave a page with its last answer, and of those it answered, with an answer or 304,
// the time to the answer, over all views and per view, the part of it until the answer's headers arrived, and its size.
record SaturationQueries(int rate, long queries, long tooMany, long failed, double answerP50Millis, double answerP99Millis, double answerMaxMillis,
                         Map<String, Double> answerP99MillisByView, double headersP50Millis, double headersP99Millis, double kilobytesP50) {

    static final String HEADER = "| Rate | Queries | Too many | Failed | Answer p50, p99, max ms | p99 ms: tape, traces, failures, timeline | Headers p50, p99 ms "
        + "| Answer KB p50 |";
    static final String SEPARATOR = "|---".repeat(8) + "|";

    static SaturationQueries of(int rate, List<SaturationPages.Query> queries) {
        var answered = queries.stream().filter(query -> query.answer() == SaturationPages.Answer.ANSWERED || query.answer() == SaturationPages.Answer.UNCHANGED)
            .toList();
        var byView = new LinkedHashMap<String, Double>();
        for (var view : SaturationPages.View.values()) {
            byView.put(view.key(), SaturationRun.percentile(answered.stream().filter(query -> query.view() == view).toList(), SaturationPages.Query::nanos, 0.99) / 1e6);
        }
        return new SaturationQueries(rate, queries.size(), queries.stream().filter(query -> query.answer() == SaturationPages.Answer.REFUSED).count(),
            queries.stream().filter(query -> query.answer() == SaturationPages.Answer.FAILED).count(), SaturationRun.percentile(answered, SaturationPages.Query::nanos, 0.5) / 1e6,
            SaturationRun.percentile(answered, SaturationPages.Query::nanos, 0.99) / 1e6, SaturationRun.percentile(answered, SaturationPages.Query::nanos, 1.0) / 1e6,
            byView, SaturationRun.percentile(answered, SaturationPages.Query::headersNanos, 0.5) / 1e6,
            SaturationRun.percentile(answered, SaturationPages.Query::headersNanos, 0.99) / 1e6, SaturationRun.percentile(answered, SaturationPages.Query::bytes, 0.5) / 1024);
    }

    String row() {
        var views = String.join(", ", Arrays.stream(SaturationPages.View.values()).map(view -> "%,.0f".formatted(answerP99MillisByView.get(view.key()))).toList());
        return "| %,d | %,d | %,d | %,d | %,.0f, %,.0f, %,.0f | %s | %,.0f, %,.0f | %,.0f |".formatted(rate, queries, tooMany, failed, answerP50Millis, answerP99Millis,
            answerMaxMillis, views, headersP50Millis, headersP99Millis, kilobytesP50);
    }
}
