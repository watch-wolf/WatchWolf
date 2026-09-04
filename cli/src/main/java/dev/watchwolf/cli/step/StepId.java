package dev.watchwolf.cli.step;

import java.util.Objects;

/** A step's stable identifier, used for dependencies, selection and reporting. */
public final class StepId implements Comparable<StepId> {
    private final String value;

    private StepId(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static StepId of(String value) {
        if (!value.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException(
                    "Step ids are lower-case kebab-case: '" + value + "'");
        }
        return new StepId(value);
    }

    public String value() {
        return this.value;
    }

    @Override public int compareTo(StepId other) { return this.value.compareTo(other.value); }
    @Override public String toString()           { return this.value; }
    @Override public boolean equals(Object o)    { return (o instanceof StepId) && this.value.equals(((StepId) o).value); }
    @Override public int hashCode()              { return this.value.hashCode(); }
}
