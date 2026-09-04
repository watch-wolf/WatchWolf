package dev.watchwolf.cli.command;

import dev.watchwolf.cli.docker.DockerFacade;
import dev.watchwolf.cli.docker.DockerJavaFacade;
import dev.watchwolf.cli.inventory.EnvironmentScanner;
import dev.watchwolf.cli.inventory.SocketAndLogClientDiscovery;
import dev.watchwolf.cli.io.FileGateway;
import dev.watchwolf.cli.io.NioFileGateway;
import dev.watchwolf.cli.layout.InstallLayout;
import dev.watchwolf.cli.model.BuildPlan;
import dev.watchwolf.cli.net.HostInterfaces;
import dev.watchwolf.cli.net.PortProbe;
import dev.watchwolf.cli.proc.CommandRunner;
import dev.watchwolf.cli.proc.ProcessCommandRunner;
import dev.watchwolf.cli.progress.ProgressSink;
import dev.watchwolf.cli.remote.HttpFetcher;
import dev.watchwolf.cli.remote.JdkHttpFetcher;
import dev.watchwolf.cli.step.CancelSignal;
import dev.watchwolf.cli.step.HostAction;
import dev.watchwolf.cli.step.StepContext;

import java.time.Clock;

/**
 * Wires the real implementations of every seam together.
 *
 * <p>The only place in the application that names a concrete implementation, which is what keeps
 * everything else constructible against fakes.
 */
public final class CliContext implements AutoCloseable {
    private final GlobalOptions options;
    private final InstallLayout layout;
    private final DockerFacade docker;
    private final FileGateway files;
    private final CommandRunner commands;
    private final HttpFetcher http;
    private final HostInterfaces interfaces;
    private final PortProbe portProbe;
    private final Clock clock;
    private final ProgressSink progress;
    private final HostAction hostAction = new HostAction();

    public CliContext(GlobalOptions options) {
        this.options = options;
        this.layout = options.layout();
        this.docker = DockerJavaFacade.connect();
        this.files = new NioFileGateway();
        this.commands = new ProcessCommandRunner();
        this.http = new JdkHttpFetcher();
        this.interfaces = new HostInterfaces();
        this.portProbe = new PortProbe();
        this.clock = Clock.systemUTC();
        this.progress = options.progress();
    }

    public GlobalOptions options()     { return this.options; }
    public InstallLayout layout()      { return this.layout; }
    public DockerFacade docker()       { return this.docker; }
    public FileGateway files()         { return this.files; }
    public CommandRunner commands()    { return this.commands; }
    public HttpFetcher http()          { return this.http; }
    public HostInterfaces interfaces() { return this.interfaces; }
    public PortProbe portProbe()       { return this.portProbe; }
    public Clock clock()               { return this.clock; }
    public ProgressSink progress()     { return this.progress; }
    public HostAction hostAction()     { return this.hostAction; }

    public StepContext stepContext(BuildPlan plan) {
        return this.stepContext(plan, this.progress, CancelSignal.never());
    }

    /**
     * The same context with progress routed somewhere else and a way to stop it -- what the TUI
     * install uses, since it must draw the progress rather than print it, and must be abortable.
     */
    public StepContext stepContext(BuildPlan plan, ProgressSink progress, CancelSignal cancel) {
        return new StepContext(this.layout, plan, this.docker, this.commands, this.files,
                this.http, this.interfaces, this.clock, progress, this.hostAction, cancel);
    }

    public EnvironmentScanner scanner() {
        return new EnvironmentScanner(this.docker, this.files, this.layout,
                new SocketAndLogClientDiscovery(this.docker), this.portProbe,
                this.interfaces, this.clock);
    }

    @Override
    public void close() {
        this.docker.close();
    }
}
