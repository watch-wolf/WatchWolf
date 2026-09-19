package dev.watchwolf.cli.step;

import dev.watchwolf.cli.docker.DockerFacade;
import dev.watchwolf.cli.io.FileGateway;
import dev.watchwolf.cli.layout.InstallLayout;
import dev.watchwolf.cli.log.RunLog;
import dev.watchwolf.cli.model.BuildPlan;
import dev.watchwolf.cli.net.HostInterfaces;
import dev.watchwolf.cli.proc.CommandRunner;
import dev.watchwolf.cli.progress.ProgressSink;
import dev.watchwolf.cli.remote.HttpFetcher;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Everything a step is allowed to touch.
 *
 * <p>All of it arrives through a seam, which is what lets the whole install flow be exercised
 * against fakes -- including making one step's verification fail on purpose.
 */
public final class StepContext {
    private final InstallLayout layout;
    private final BuildPlan plan;
    private final DockerFacade docker;
    private final CommandRunner commands;
    private final FileGateway files;
    private final HttpFetcher http;
    private final HostInterfaces interfaces;
    private final Clock clock;
    private final ProgressSink progress;
    private final HostAction hostAction;
    private final CancelSignal cancelSignal;
    private final RunLog runLog;

    /** Values handed from one step to the next, e.g. the WatchWolf-Server version that was found. */
    private final Map<StepId, Object> outputs = new LinkedHashMap<>();

    public StepContext(InstallLayout layout, BuildPlan plan, DockerFacade docker,
                       CommandRunner commands, FileGateway files, HttpFetcher http,
                       HostInterfaces interfaces, Clock clock, ProgressSink progress,
                       HostAction hostAction) {
        this(layout, plan, docker, commands, files, http, interfaces, clock, progress, hostAction,
                CancelSignal.never(), RunLog.disabled());
    }

    public StepContext(InstallLayout layout, BuildPlan plan, DockerFacade docker,
                       CommandRunner commands, FileGateway files, HttpFetcher http,
                       HostInterfaces interfaces, Clock clock, ProgressSink progress,
                       HostAction hostAction, CancelSignal cancelSignal, RunLog runLog) {
        this.layout = layout;
        this.plan = plan;
        this.docker = docker;
        this.commands = commands;
        this.files = files;
        this.http = http;
        this.interfaces = interfaces;
        this.clock = clock;
        this.progress = progress;
        this.hostAction = hostAction;
        this.cancelSignal = cancelSignal;
        this.runLog = runLog;
    }

    public InstallLayout layout()      { return this.layout; }
    public BuildPlan plan()            { return this.plan; }
    public DockerFacade docker()       { return this.docker; }
    public CommandRunner commands()    { return this.commands; }
    public FileGateway files()         { return this.files; }
    public HttpFetcher http()          { return this.http; }
    public HostInterfaces interfaces() { return this.interfaces; }
    public Clock clock()               { return this.clock; }
    public ProgressSink progress()     { return this.progress; }
    public HostAction hostAction()     { return this.hostAction; }

    /** Polled by anything long-running; see {@link CancelSignal} for why it is coarse. */
    public CancelSignal cancelSignal() { return this.cancelSignal; }

    /**
     * This run's log. Steps use it for output too long to inline in a failure but too valuable to
     * throw away -- {@link RunLog#attachment} -- not as a second progress channel.
     */
    public RunLog runLog()             { return this.runLog; }

    public void publish(StepId id, Object value) {
        this.outputs.put(id, value);
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> output(StepId id, Class<T> type) {
        Object value = this.outputs.get(id);
        return type.isInstance(value) ? Optional.of((T) value) : Optional.empty();
    }
}
