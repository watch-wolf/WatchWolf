package dev.watchwolf.cli.doctor;

import java.util.Optional;

/**
 * Where the component-compatibility matrix comes from -- when there is one.
 *
 * <p>There is not one yet. Shipping the matrix is Stage 4 work (a protocol revision plus a
 * declarative resource in WatchWolf-Core), and this CLI deliberately does not invent a policy in
 * its absence. The interface exists so that when Stage 4 lands, it is a resource drop rather than a
 * rewrite of {@code doctor}.
 *
 * <p>Until then {@link AbsentMatrixSource} reports {@code SKIP}. Note that is <em>not</em> the same
 * as reporting a pass: versions are still collected and printed, they are simply not judged.
 */
public interface CompatibilityMatrixSource {

    Optional<Matrix> load();

    interface Matrix {
        boolean isCompatible(String component, String version, int protocolRevision);
        String describe();
    }

    /** The current reality: no matrix is shipped by any component. */
    final class AbsentMatrixSource implements CompatibilityMatrixSource {
        private final String whyAbsent;

        public AbsentMatrixSource() {
            this("no compatibility matrix is shipped by WatchWolf-Core yet (Stage 4)");
        }

        public AbsentMatrixSource(String whyAbsent) {
            this.whyAbsent = whyAbsent;
        }

        @Override
        public Optional<Matrix> load() {
            return Optional.empty();
        }

        public String whyAbsent() {
            return this.whyAbsent;
        }
    }
}
