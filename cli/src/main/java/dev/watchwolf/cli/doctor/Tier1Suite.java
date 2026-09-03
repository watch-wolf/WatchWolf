package dev.watchwolf.cli.doctor;

import dev.watchwolf.cli.docker.DaemonInfo;
import dev.watchwolf.cli.inventory.ManagerStatus;
import dev.watchwolf.cli.inventory.ServerJarInventory;
import dev.watchwolf.cli.model.JavaImageCatalog;
import dev.watchwolf.cli.model.McVersion;
import dev.watchwolf.cli.model.UsualPluginJar;
import dev.watchwolf.cli.net.AddressCandidate;
import dev.watchwolf.cli.net.HostInterfaces;
import dev.watchwolf.cli.net.PortProbe;
import dev.watchwolf.cli.parse.ContainerNames;
import dev.watchwolf.cli.step.StepContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The fast static checks. Seconds, read-only, safe against a live environment.
 *
 * <p>The last one is the reason the whole effort exists: enumerating the host's addresses and
 * saying which one the ServersManager will advertise turns "my tests fail intermittently, three
 * weeks later" into a warning at install time.
 */
public final class Tier1Suite {
    private final CompatibilityMatrixSource matrixSource;
    private final PortProbe portProbe;
    private final HostInterfaces interfaces;

    public Tier1Suite(CompatibilityMatrixSource matrixSource, PortProbe portProbe,
                      HostInterfaces interfaces) {
        this.matrixSource = matrixSource;
        this.portProbe = portProbe;
        this.interfaces = interfaces;
    }

    public List<Check> checks() {
        return List.of(
                this.dockerReachable(),
                this.requiredImagesPresent(),
                this.portsFreeOrOurs(),
                this.serverTypesPopulated(),
                this.watchWolfServerPluginPresent(),
                this.networkSanity(),
                this.versionMatrix());
    }

    public DoctorReport run(StepContext context, boolean strict) {
        DoctorReport report = new DoctorReport(strict);
        for (Check check : this.checks()) {
            try {
                report.add(check.run(context));
            } catch (RuntimeException ex) {
                report.add(CheckResult.fail(check.name(),
                        "the check itself failed: " + ex,
                        "This is a bug in the CLI; please report it."));
            }
        }
        return report;
    }

    // ---- the checks ------------------------------------------------------------------------

    private Check dockerReachable() {
        return new Check() {
            @Override public String name() { return "Docker is reachable"; }

            @Override
            public CheckResult run(StepContext context) {
                DaemonInfo daemon = context.docker().daemonInfo();
                if (!daemon.reachable()) {
                    return CheckResult.fail(this.name(), daemon.unreachableReason(),
                            "Start Docker and make sure this user can use its socket.");
                }
                return CheckResult.pass(this.name(),
                        "server " + daemon.serverVersion() + ", API " + daemon.apiVersion());
            }
        };
    }

    private Check requiredImagesPresent() {
        return new Check() {
            @Override public String name() { return "The JDK images servers run on are present"; }

            @Override
            public CheckResult run(StepContext context) {
                Set<McVersion> installed = new ServerJarInventory(context.files(),
                        context.layout()).installedVersions();
                if (installed.isEmpty()) {
                    return CheckResult.skip(this.name(), "no server jars installed yet");
                }

                List<String> missing = new ArrayList<>();
                for (String image : JavaImageCatalog.imagesRequiredBy(installed)) {
                    if (!context.docker().imageExists(image)) missing.add(image);
                }
                if (!missing.isEmpty()) {
                    return CheckResult.warn(this.name(),
                            "missing " + String.join(", ", missing),
                            "Run 'watchwolf build'. Without them the first server of that version "
                                    + "pulls at run time, which looks like a hang.");
                }
                return CheckResult.pass(this.name(),
                        installed.size() + " installed version(s) all covered");
            }
        };
    }

    private Check portsFreeOrOurs() {
        return new Check() {
            @Override public String name() { return "Ports 8000 and 7000 are free, or ours"; }

            @Override
            public CheckResult run(StepContext context) {
                List<String> problems = new ArrayList<>();
                for (ManagerStatus.Kind kind : ManagerStatus.Kind.values()) {
                    boolean occupied =
                            Tier1Suite.this.portProbe.isAccepting("127.0.0.1", kind.port());
                    boolean ours = context.docker().findContainer(kind.containerName())
                            .map(container -> container.isRunning()).orElse(false);
                    if (occupied && !ours) {
                        problems.add(kind.port() + " is in use by something that is not "
                                + kind.containerName());
                    }
                }
                if (!problems.isEmpty()) {
                    return CheckResult.fail(this.name(), String.join("; ", problems),
                            "Stop whatever is holding the port, or the managers will not start.");
                }
                return CheckResult.pass(this.name(), "8000 and 7000 available");
            }
        };
    }

    private Check serverTypesPopulated() {
        return new Check() {
            @Override public String name() { return "server-types/ holds at least one server"; }

            @Override
            public CheckResult run(StepContext context) {
                Set<McVersion> installed = new ServerJarInventory(context.files(),
                        context.layout()).installedVersions();
                if (installed.isEmpty()) {
                    return CheckResult.fail(this.name(),
                            "no .jar under " + context.layout().serverTypes(),
                            "Run 'watchwolf build' and select at least one Spigot or Paper "
                                    + "version. With none, every test fails at server start.");
                }
                return CheckResult.pass(this.name(), installed.size() + " version(s) installed");
            }
        };
    }

    private Check watchWolfServerPluginPresent() {
        return new Check() {
            @Override
            public String name() {
                return "usual-plugins/ holds a WatchWolf-Server jar covering those versions";
            }

            @Override
            public CheckResult run(StepContext context) {
                List<UsualPluginJar> serverJars = new ArrayList<>();
                List<String> unparsable = new ArrayList<>();

                for (Path path : context.files().list(context.layout().usualPlugins())) {
                    String fileName = path.getFileName().toString();
                    if (!fileName.endsWith(".jar")) continue;
                    UsualPluginJar parsed = UsualPluginJar.parseOrNull(fileName);
                    if (parsed == null) {
                        unparsable.add(fileName);
                    } else if (parsed.isWatchWolfServer()) {
                        serverJars.add(parsed);
                    }
                }

                if (serverJars.isEmpty()) {
                    return CheckResult.fail(this.name(),
                            "no WatchWolf-*.jar in " + context.layout().usualPlugins(),
                            "Run 'watchwolf build'. Servers start without it, but no test can "
                                    + "talk to them.");
                }

                List<String> uncovered = new ArrayList<>();
                for (McVersion version : new ServerJarInventory(context.files(),
                        context.layout()).installedVersions()) {
                    if (serverJars.stream().noneMatch(jar -> jar.supports(version))) {
                        uncovered.add(version.toString());
                    }
                }
                if (!uncovered.isEmpty()) {
                    return CheckResult.warn(this.name(),
                            "the plugin does not declare support for " + String.join(", ", uncovered),
                            "Tests against those versions will not connect. Install a newer "
                                    + "WatchWolf-Server, or remove those server jars.");
                }
                if (!unparsable.isEmpty()) {
                    return CheckResult.warn(this.name(),
                            "the ServersManager cannot parse " + String.join(", ", unparsable)
                                    + ", so it ignores them",
                            "Rename them to <Name>-<version>-<minMc>-<maxMc>.jar, or delete them.");
                }
                return CheckResult.pass(this.name(),
                        serverJars.get(0).fileName() + " covers every installed version");
            }
        };
    }

    /** The check this whole effort exists for. */
    private Check networkSanity() {
        return new Check() {
            @Override
            public String name() { return "The address the ServersManager will advertise"; }

            @Override
            public CheckResult run(StepContext context) {
                DaemonInfo daemon = context.docker().daemonInfo();
                if (daemon.reachable() && !daemon.hostNetworkingIsTruthful()) {
                    return CheckResult.skip(this.name(),
                            "this is Docker Desktop, where the container cannot see the host's "
                                    + "interfaces -- any answer here would describe the container, "
                                    + "not your machine");
                }

                List<AddressCandidate> candidates = Tier1Suite.this.interfaces.candidates();
                if (candidates.isEmpty()) {
                    return CheckResult.warn(this.name(), "no IPv4 interfaces found",
                            "Servers will be advertised as 127.0.0.1, reachable only from this host.");
                }

                String chosen = Tier1Suite.this.interfaces.preferredMachineIp();
                List<AddressCandidate> suspicious =
                        Tier1Suite.this.interfaces.suspiciousCandidates();

                if (!suspicious.isEmpty() || Tier1Suite.this.interfaces.hasAmbiguousChoice()) {
                    StringBuilder detail = new StringBuilder("will advertise " + chosen + "; ");
                    detail.append("candidates: ");
                    for (int i = 0; i < candidates.size(); i++) {
                        if (i > 0) detail.append(", ");
                        detail.append(candidates.get(i));
                    }
                    return CheckResult.warn(this.name(), detail.toString(),
                            "Several plausible addresses exist. If your tests run on another "
                                    + "machine and cannot reach " + chosen + ", set 'provider' in "
                                    + "the Tester's config.yaml, or disable the adapter you do not "
                                    + "want (a VirtualBox host-only adapter is the usual culprit).");
                }
                return CheckResult.pass(this.name(), "will advertise " + chosen);
            }
        };
    }

    private Check versionMatrix() {
        return new Check() {
            @Override public String name() { return "Component versions are compatible"; }

            @Override
            public CheckResult run(StepContext context) {
                if (Tier1Suite.this.matrixSource.load().isEmpty()) {
                    // SKIP, never PASS: versions are collected but nothing judges them, and
                    // saying "compatible" without a matrix would be a lie
                    String why = (Tier1Suite.this.matrixSource
                            instanceof CompatibilityMatrixSource.AbsentMatrixSource absent)
                            ? absent.whyAbsent() : "no compatibility matrix available";
                    return CheckResult.skip(this.name(),
                            why + "; versions were collected but not judged");
                }
                return CheckResult.pass(this.name(), "all components agree");
            }
        };
    }

    /** Whether any WatchWolf container is running -- used to word some remedies. */
    static boolean anythingRunning(StepContext context) {
        return context.docker().listContainers().stream()
                .anyMatch(container -> container.isRunning()
                        && (container.name().equals(ContainerNames.SERVERS_MANAGER)
                            || container.name().equals(ContainerNames.CLIENTS_MANAGER)));
    }
}
