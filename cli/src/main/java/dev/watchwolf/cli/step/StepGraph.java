package dev.watchwolf.cli.step;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The steps of a run, topologically ordered by their declared dependencies. */
public final class StepGraph {
    private final List<Step> ordered;
    private final Map<StepId, Step> byId;

    private StepGraph(List<Step> ordered, Map<StepId, Step> byId) {
        this.ordered = List.copyOf(ordered);
        this.byId = Map.copyOf(byId);
    }

    /**
     * @throws IllegalArgumentException on a duplicate id, an unknown dependency, or a cycle --
     *         all three are programming errors, caught by the code checks rather than at runtime
     */
    public static StepGraph of(List<Step> steps) {
        Map<StepId, Step> byId = new LinkedHashMap<>();
        for (Step step : steps) {
            if (byId.put(step.id(), step) != null) {
                throw new IllegalArgumentException("Duplicate step id: " + step.id());
            }
        }
        for (Step step : steps) {
            for (StepId required : step.requires()) {
                if (!byId.containsKey(required)) {
                    throw new IllegalArgumentException(
                            "Step " + step.id() + " requires unknown step " + required);
                }
            }
        }

        List<Step> ordered = new ArrayList<>(steps.size());
        Set<StepId> done = new LinkedHashSet<>();
        Set<StepId> visiting = new LinkedHashSet<>();
        for (Step step : steps) {
            visit(step, byId, done, visiting, ordered);
        }
        return new StepGraph(ordered, byId);
    }

    private static void visit(Step step, Map<StepId, Step> byId, Set<StepId> done,
                              Set<StepId> visiting, List<Step> ordered) {
        if (done.contains(step.id())) return;
        if (!visiting.add(step.id())) {
            throw new IllegalArgumentException(
                    "Dependency cycle through step " + step.id() + " (" + visiting + ")");
        }
        for (StepId required : step.requires()) {
            visit(byId.get(required), byId, done, visiting, ordered);
        }
        visiting.remove(step.id());
        done.add(step.id());
        ordered.add(step);
    }

    public List<Step> ordered() {
        return this.ordered;
    }

    public Step byId(StepId id) {
        return this.byId.get(id);
    }

    public int size() {
        return this.ordered.size();
    }
}
