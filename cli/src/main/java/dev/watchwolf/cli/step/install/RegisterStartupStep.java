package dev.watchwolf.cli.step.install;

import dev.watchwolf.cli.step.*;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Registers WatchWolf to start at boot: a systemd unit, or a Windows Startup {@code .bat} on WSL.
 *
 * <p>Both are host state the container cannot write, so both are rendered into the host-action
 * script. The WSL path needs no root -- it writes into the user's own Startup folder -- which is
 * why {@link HostAction#requiringRoot()} is only set on the systemd branch.
 *
 * <p><b>Not carried over:</b> the old script offered to disable the WSL sudo password by writing
 * {@code NOPASSWD:ALL} into {@code /etc/sudoers.d/}. That is a permanent, machine-wide privilege
 * change made to avoid a prompt, and this command does not do it. The CLI needs no sudo at run time
 * anyway: it reaches the daemon through the Docker socket.
 */
public final class RegisterStartupStep implements Step {
    public static final StepId ID = StepId.of("register-startup");
    public static final String UNIT_PATH = "/etc/systemd/system/watchwolf.service";

    @Override public StepId id()    { return ID; }
    @Override public String title() { return "Start WatchWolf at boot"; }

    @Override
    public Set<StepId> requires() {
        return Set.of(RegisterLauncherStep.ID);
    }

    @Override
    public boolean isApplicable(StepContext context) {
        return context.plan().registerStartup();
    }

    @Override
    public String skipReason(StepContext context) {
        return "not selected; run 'watchwolf run' by hand";
    }

    @Override
    public void perform(StepContext context) throws StepFailedException {
        if (isWsl(context)) {
            this.registerWindowsStartup(context);
        } else {
            this.registerSystemdUnit(context);
        }
    }

    private void registerSystemdUnit(StepContext context) {
        String base = context.layout().base().toString();
        String unit = String.join("\n",
                "[Unit]",
                "Description=WatchWolf (ServersManager and ClientsManager)",
                "After=docker.service",
                "Requires=docker.service",
                "",
                "[Service]",
                "Type=oneshot",
                "RemainAfterExit=yes",
                "ExecStart=" + RegisterLauncherStep.TARGET + " run --path " + base,
                "ExecStop=" + RegisterLauncherStep.TARGET + " stop --path " + base,
                "User=" + System.getProperty("user.name", "root"),
                "",
                "[Install]",
                "WantedBy=multi-user.target");

        context.hostAction()
                .add("Register WatchWolf as a systemd service so it starts at boot.",
                        "cat > " + UNIT_PATH + " <<'WATCHWOLF_UNIT'",
                        unit,
                        "WATCHWOLF_UNIT",
                        "systemctl daemon-reload",
                        "systemctl enable watchwolf")
                .requiringRoot();

        context.progress().detail("queued: systemd unit " + UNIT_PATH);
    }

    /** WSL has no systemd by default, so Windows' Startup folder is the equivalent. */
    private void registerWindowsStartup(StepContext context) throws StepFailedException {
        Path startupFolder = windowsStartupFolder(context);
        if (startupFolder == null) {
            throw new StepFailedException("registering WatchWolf at startup",
                    "could not locate the Windows Startup folder from WSL",
                    "Create a .bat there yourself containing: wsl "
                            + RegisterLauncherStep.TARGET + " run --path "
                            + context.layout().base());
        }

        String batch = "wsl " + RegisterLauncherStep.TARGET
                + " run --path \"" + context.layout().base() + "\"";
        String target = startupFolder.resolve("WatchWolf.bat").toString();

        // no root needed: this is the user's own Startup folder
        context.hostAction().add(
                "Start WatchWolf when Windows starts (WSL).",
                "printf '%s\\n' " + HostAction.quote(batch) + " > " + HostAction.quote(target));

        context.progress().detail("queued: " + target);
    }

    static boolean isWsl(StepContext context) {
        try {
            String version = context.files().readString(Paths.get("/proc/version"));
            return version.toLowerCase().contains("microsoft");
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    private static Path windowsStartupFolder(StepContext context) {
        // the mount point WSL gives the Windows drive; the user profile lives under Users/
        Path users = Paths.get("/mnt/c/Users");
        if (!context.files().isDirectory(users)) return null;

        for (Path candidate : context.files().list(users)) {
            Path startup = candidate.resolve(
                    "AppData/Roaming/Microsoft/Windows/Start Menu/Programs/Startup");
            if (context.files().isDirectory(startup)) return startup;
        }
        return null;
    }

    @Override
    public Verification verification() {
        return new Verification() {
            @Override
            public String describe() {
                return "a systemd unit is enabled, or a WatchWolf.bat exists in Windows' Startup "
                        + "folder";
            }

            @Override
            public void check(StepContext context) throws VerificationFailedException {
                if (isWsl(context)) {
                    Path folder = windowsStartupFolder(context);
                    if (folder == null || !context.files().exists(folder.resolve("WatchWolf.bat"))) {
                        throw new VerificationFailedException(
                                "WatchWolf is not registered to start with Windows",
                                "no WatchWolf.bat in the Startup folder",
                                "Run 'watchwolf install' and accept the host action it prints.");
                    }
                    return;
                }

                if (!context.files().exists(Paths.get(UNIT_PATH))) {
                    throw new VerificationFailedException(
                            "WatchWolf is not registered as a service",
                            UNIT_PATH + " does not exist",
                            "Run 'watchwolf install' and accept the host action it prints.");
                }
            }
        };
    }
}
