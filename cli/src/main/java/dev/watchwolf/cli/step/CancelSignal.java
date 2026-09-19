package dev.watchwolf.cli.step;

/**
 * Asks "should I stop?", so a run can be abandoned without killing the process mid-write.
 *
 * <p>Cancellation is <b>cooperative and coarse</b> on purpose. {@link StepRunner} checks it between
 * steps, and the one step long enough to need finer granularity -- building Spigot, which is about
 * an hour per version -- checks it in its own poll loop. Nothing is interrupted mid-operation:
 * every step is idempotent and verified, so stopping at a step boundary and re-running later
 * resumes rather than repeats. Killing a step half way through would give up that guarantee for no
 * gain, since the slow parts (BuildTools containers, image pulls) run in the daemon and outlive
 * this process anyway.
 */
@FunctionalInterface
public interface CancelSignal {
    boolean cancelled();

    static CancelSignal never() {
        return () -> false;
    }
}
