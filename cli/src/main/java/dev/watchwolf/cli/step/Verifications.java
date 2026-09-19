package dev.watchwolf.cli.step;

import java.util.ArrayList;
import java.util.List;

/** Combinators for {@link Verification}. */
public final class Verifications {
    private Verifications() {
    }

    /** All of them must hold; the first failure is the one reported. */
    public static Verification all(Verification... verifications) {
        List<Verification> parts = List.of(verifications);
        return new Verification() {
            @Override
            public String describe() {
                List<String> descriptions = new ArrayList<>();
                for (Verification part : parts) descriptions.add(part.describe());
                return String.join("; and ", descriptions);
            }

            @Override
            public void check(StepContext context) throws VerificationFailedException {
                for (Verification part : parts) part.check(context);
            }
        };
    }
}
