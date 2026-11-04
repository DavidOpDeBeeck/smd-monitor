package app.dodb.smd.monitor.hub;

import app.dodb.smd.monitor.hub.span.Stage;
import app.dodb.smd.monitor.hub.specimen.Specimen;
import app.dodb.smd.monitor.hub.specimen.SpecimenView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Map.Entry.comparingByValue;

// The failed specimens of an epoch by cause, and how their failures propagated, found in one walk of the epoch's failed specimens so that each
// failures query reads only the occurrences of the causes it asks for (F1). A failed parent most likely propagated the failure of its failed child
// that completed last, so an occurrence's chain runs from the entry point its failure reached, through the parents it propagated to, down to the
// invocation that raised it, which no failed retained child failed. It never changes once built, so any thread reads it.
final class FailureChains {

    // In ascending sequence, as the view walks them; the arrays below index them, -1 for none.
    private final List<Specimen> failed;
    private final int[] causeOf;
    private final int[] failedParentOf;
    // The failed child whose failure each failed parent most likely propagated: the one that completed last.
    private final int[] propagatedFrom;
    private final List<FailureCause.Step> causes;
    private final Map<FailureCause.Step, Integer> causeIndexes;
    // The occurrences of cause c, in ascending sequence, are occurrences[firstOf[c]] up to occurrences[firstOf[c + 1]].
    private final int[] firstOf;
    private final int[] occurrences;

    private FailureChains(List<Specimen> failed, int[] causeOf, int[] failedParentOf, int[] propagatedFrom, List<FailureCause.Step> causes,
                          Map<FailureCause.Step, Integer> causeIndexes, int[] firstOf, int[] occurrences) {
        this.failed = failed;
        this.causeOf = causeOf;
        this.failedParentOf = failedParentOf;
        this.propagatedFrom = propagatedFrom;
        this.causes = causes;
        this.causeIndexes = causeIndexes;
        this.firstOf = firstOf;
        this.occurrences = occurrences;
    }

    static FailureChains of(SpecimenView view) {
        var failed = new ArrayList<Specimen>();
        view.forEachFailed(failed::add);
        var ids = failed.stream().mapToLong(Specimen::id).toArray();
        var failedParentOf = new int[failed.size()];
        var propagatedFrom = new int[failed.size()];
        Arrays.fill(propagatedFrom, -1);
        // A cause looked up by the stage and failure type its specimens share, which the catalog interns.
        var byStage = new IdentityHashMap<Stage, Map<String, Integer>>();
        var causes = new ArrayList<FailureCause.Step>();
        var causeIndexes = new HashMap<FailureCause.Step, Integer>();
        var causeOf = new int[failed.size()];
        // One pass, as reading the specimens, not the arrays, takes the time.
        for (var index = 0; index < failed.size(); index++) {
            var specimen = failed.get(index);
            var parent = failedParentOf(specimen, ids);
            failedParentOf[index] = parent;
            if (parent >= 0 && (propagatedFrom[parent] < 0 || specimen.completedAtNanos() > failed.get(propagatedFrom[parent]).completedAtNanos())) {
                propagatedFrom[parent] = index;
            }
            var failureType = specimen.failureType().orElseThrow();
            causeOf[index] = byStage.computeIfAbsent(specimen.stage(), ignored -> new IdentityHashMap<>())
                .computeIfAbsent(failureType, ignored -> causeIndexes.computeIfAbsent(new FailureCause.Step(specimen.application(), specimen.stage(), failureType),
                    step -> {
                        causes.add(step);
                        return causes.size() - 1;
                    }));
        }
        var firstOf = new int[causes.size() + 1];
        for (var cause : causeOf) {
            firstOf[cause + 1]++;
        }
        for (var cause = 1; cause < firstOf.length; cause++) {
            firstOf[cause] += firstOf[cause - 1];
        }
        var filled = Arrays.copyOf(firstOf, causes.size());
        var occurrences = new int[failed.size()];
        for (var index = 0; index < failed.size(); index++) {
            occurrences[filled[causeOf[index]]++] = index;
        }
        return new FailureChains(failed, causeOf, failedParentOf, propagatedFrom, causes, causeIndexes, firstOf, occurrences);
    }

    // How many occurrences of the cause the epoch retains, the latest of them newest first, and its most frequent chains.
    FailureCause cause(FailureCause.Step cause) {
        var index = causeIndexes.get(cause);
        if (index == null) {
            return new FailureCause(cause, 0, List.of(), List.of());
        }
        var from = firstOf[index];
        var to = firstOf[index + 1];
        // In the order they first occurred, so equally frequent chains rank by that.
        var chains = new LinkedHashMap<Chain, Integer>();
        for (var at = from; at < to; at++) {
            chains.merge(chainOf(occurrences[at]), 1, Integer::sum);
        }
        var ranked = chains.entrySet().stream()
            .sorted(comparingByValue(Collections.reverseOrder()))
            .limit(FailureCause.CHAINS)
            .map(entry -> new FailureCause.Chain(Arrays.stream(entry.getKey().causes).mapToObj(causes::get).toList(), entry.getValue()))
            .toList();
        var latest = new ArrayList<Invocation>(FailureCause.LATEST);
        for (var at = to - 1; at >= Math.max(from, to - FailureCause.LATEST); at--) {
            latest.add(failed.get(occurrences[at]).invocation());
        }
        return new FailureCause(cause, to - from, latest, ranked);
    }

    // The causes of the occurrence's chain from the entry point to the invocation that raised; at most as many steps each way as there are
    // failed specimens, should the parents found form a cycle.
    private Chain chainOf(int occurrence) {
        var above = 0;
        for (var current = occurrence; failedParentOf[current] >= 0 && propagatedFrom[failedParentOf[current]] == current && above <= failed.size();
             current = failedParentOf[current]) {
            above++;
        }
        var below = 0;
        for (var raising = propagatedFrom[occurrence]; raising >= 0 && below <= failed.size(); raising = propagatedFrom[raising]) {
            below++;
        }
        var chain = new int[above + 1 + below];
        var current = occurrence;
        for (var step = above - 1; step >= 0; step--) {
            current = failedParentOf[current];
            chain[step] = causeOf[current];
        }
        chain[above] = causeOf[occurrence];
        var raising = propagatedFrom[occurrence];
        for (var step = above + 1; step < chain.length; step++) {
            chain[step] = causeOf[raising];
            raising = propagatedFrom[raising];
        }
        return new Chain(chain);
    }

    // The index of the specimen's parent among the failed, or -1 when its parent did not fail or is not retained.
    private static int failedParentOf(Specimen specimen, long[] failedIds) {
        var parent = specimen.parent();
        if (parent.isEmpty()) {
            return -1;
        }
        var index = Arrays.binarySearch(failedIds, parent.get().parent().value());
        return index < 0 ? -1 : index;
    }

    // The causes of a chain by their index; equal when they hold the same causes.
    private static final class Chain {

        private final int[] causes;
        private final int hash;

        private Chain(int[] causes) {
            this.causes = causes;
            this.hash = Arrays.hashCode(causes);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Chain chain && chain.hash == hash && Arrays.equals(chain.causes, causes);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
