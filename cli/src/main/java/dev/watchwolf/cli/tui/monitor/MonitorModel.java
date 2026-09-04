package dev.watchwolf.cli.tui.monitor;

import dev.watchwolf.cli.inventory.ClientStatus;
import dev.watchwolf.cli.inventory.EnvironmentSnapshot;
import dev.watchwolf.cli.inventory.ManagerStatus;
import dev.watchwolf.cli.inventory.McServerStatus;
import dev.watchwolf.cli.layout.InstallLayout;
import dev.watchwolf.cli.model.Confidence;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The dashboard's state, with no terminal code in it.
 *
 * <p><b>Two levels.</b> The overview is a pure inventory and carries no log lines at all -- logs
 * belong to one entity, so you only see them after entering that entity with Enter. That keeps the
 * overview's whole height available for the tree, and keeps "what is running" separate from "what
 * is this one doing".
 */
public final class MonitorModel {
    private final InstallLayout layout;

    private EnvironmentSnapshot snapshot;
    private List<MonitorRow> rows = List.of();
    private int cursor;
    private String focusedKey;               // non-null == level 2
    private final Set<String> collapsed = new LinkedHashSet<>();

    public MonitorModel(InstallLayout layout, EnvironmentSnapshot snapshot) {
        this.layout = layout;
        this.update(snapshot);
    }

    /** A new poll arrived. The cursor stays on the same entity where it still exists. */
    public void update(EnvironmentSnapshot snapshot) {
        String previousKey = this.selectedRow().map(MonitorRow::key).orElse(null);
        this.snapshot = snapshot;
        this.rows = this.buildRows();

        if (previousKey != null) {
            for (int i = 0; i < this.rows.size(); i++) {
                if (previousKey.equals(this.rows.get(i).key())) {
                    this.cursor = i;
                    return;
                }
            }
        }
        this.clampCursor();
    }

    public EnvironmentSnapshot snapshot() { return this.snapshot; }
    public List<MonitorRow> rows()        { return this.rows; }
    public int cursor()                   { return this.cursor; }

    /** True when we are inside one entity, i.e. the only place logs are shown. */
    public boolean isInEntityView() {
        return this.focusedKey != null;
    }

    // ---- navigation ------------------------------------------------------------------------

    public void moveDown() {
        for (int i = this.cursor + 1; i < this.rows.size(); i++) {
            if (this.rows.get(i).selectable()) {
                this.cursor = i;
                return;
            }
        }
    }

    public void moveUp() {
        for (int i = this.cursor - 1; i >= 0; i--) {
            if (this.rows.get(i).selectable()) {
                this.cursor = i;
                return;
            }
        }
    }

    public Optional<MonitorRow> selectedRow() {
        if (this.cursor < 0 || this.cursor >= this.rows.size()) return Optional.empty();
        return Optional.of(this.rows.get(this.cursor));
    }

    /** Enter: descend into the selected entity, where its logs live. */
    public boolean enter() {
        Optional<MonitorRow> selected = this.selectedRow();
        if (selected.isEmpty() || selected.get().key() == null) return false;
        this.focusedKey = selected.get().key();
        return true;
    }

    /** Escape: back to the overview. */
    public void back() {
        this.focusedKey = null;
    }

    public void toggleCollapse() {
        this.selectedRow().ifPresent(row -> {
            if (row.kind() != MonitorRow.Kind.MANAGER) return;
            if (!this.collapsed.remove(row.key())) this.collapsed.add(row.key());
            this.rows = this.buildRows();
            this.clampCursor();
        });
    }

    private void clampCursor() {
        if (this.rows.isEmpty()) {
            this.cursor = 0;
            return;
        }
        this.cursor = Math.max(0, Math.min(this.cursor, this.rows.size() - 1));
        if (!this.rows.get(this.cursor).selectable()) this.moveDown();
    }

    // ---- level 1: the tree -----------------------------------------------------------------

    private List<MonitorRow> buildRows() {
        List<MonitorRow> rows = new ArrayList<>();

        if (!this.snapshot.dockerReachable()) {
            rows.add(MonitorRow.note("Docker is not reachable: "
                    + this.snapshot.dockerUnreachableReason().orElse("unknown"), 0));
            return rows;
        }

        ManagerStatus serversManager = this.snapshot.serversManager();
        rows.add(this.managerRow(serversManager));
        if (!this.collapsed.contains(serversManager.name())) {
            if (this.snapshot.servers().isEmpty()) {
                rows.add(MonitorRow.note("(no servers)", 1));
            }
            for (McServerStatus server : this.snapshot.servers()) {
                rows.add(this.serverRow(server));
            }
        }

        ManagerStatus clientsManager = this.snapshot.clientsManager();
        rows.add(this.managerRow(clientsManager));
        if (!this.collapsed.contains(clientsManager.name())) {
            List<ClientStatus> clients = this.snapshot.clients().clients();
            if (clients.isEmpty()) {
                rows.add(MonitorRow.note("(no bots)", 1));
            }
            for (ClientStatus client : clients) {
                rows.add(this.clientRow(client));
            }
            // the caveat belongs on screen, not in a footnote: it says which rows were guessed
            if (this.snapshot.clients().limitation() != null) {
                rows.add(MonitorRow.note(this.snapshot.clients().limitation(), 1));
            }
        }

        return rows;
    }

    private MonitorRow managerRow(ManagerStatus manager) {
        return new MonitorRow(MonitorRow.Kind.MANAGER, manager.name(), manager.name(),
                "manager", "-", manager.kind().portLabel(), manager.stateLabel(),
                manager.uptime(this.snapshot.takenAt()).map(MonitorModel::human).orElse("-"),
                manager.isUp() ? Confidence.OBSERVED : Confidence.UNKNOWN, 0, true);
    }

    private MonitorRow serverRow(McServerStatus server) {
        return new MonitorRow(MonitorRow.Kind.SERVER, "server:" + server.sessionId(),
                server.name(), server.type(), server.version(), server.portsLabel(),
                server.isRunning() ? "running" : "finished",
                server.uptime(this.snapshot.takenAt()).map(MonitorModel::human).orElse("-"),
                server.confidence(), 1, true);
    }

    private MonitorRow clientRow(ClientStatus client) {
        return new MonitorRow(MonitorRow.Kind.CLIENT, "client:" + client.connectorPort(),
                client.displayName(), "bot", "-", client.portsLabel(),
                client.username().isPresent() ? "joined" : "starting", "-",
                client.confidence(), 1, true);
    }

    // ---- level 2: one entity ---------------------------------------------------------------

    /** The detail view, including where to read this entity's log from. */
    public Optional<EntityView> entityView() {
        if (this.focusedKey == null) return Optional.empty();

        if (this.focusedKey.startsWith("server:")) {
            return this.snapshot.serverById(this.focusedKey.substring("server:".length()))
                    .map(this::serverView);
        }
        if (this.focusedKey.startsWith("client:")) {
            int port = Integer.parseInt(this.focusedKey.substring("client:".length()));
            return this.snapshot.clients().clients().stream()
                    .filter(client -> client.connectorPort() == port)
                    .findFirst().map(this::clientView);
        }
        return this.snapshot.managers().stream()
                .filter(manager -> manager.name().equals(this.focusedKey))
                .findFirst().map(this::managerView);
    }

    private EntityView managerView(ManagerStatus manager) {
        List<String> facts = new ArrayList<>();
        facts.add("state " + manager.stateLabel() + "   ports " + manager.kind().portLabel());
        facts.add("image " + manager.image().orElse("-") + "   uptime "
                + manager.uptime(this.snapshot.takenAt()).map(MonitorModel::human).orElse("-"));
        manager.stats().ifPresent(stats -> facts.add(String.format(
                "cpu %.1f%%   mem %s", stats.cpuPercent(), stats.humanMemory())));

        if (!manager.isUp()) {
            return new EntityView(manager.name(), facts,
                    new EntityView.LogSource.None("the container is not running"),
                    "Start it with 'watchwolf run'.", false);
        }
        return new EntityView(manager.name(), facts,
                new EntityView.LogSource.ContainerLog(manager.name()), null, true);
    }

    private EntityView serverView(McServerStatus server) {
        List<String> facts = new ArrayList<>();
        facts.add("type " + server.type() + " " + server.version()
                + "   ports " + server.portsLabel() + " (minecraft/watchwolf)");
        facts.add("state " + (server.isRunning() ? "running" : "finished")
                + "   uptime " + server.uptime(this.snapshot.takenAt())
                        .map(MonitorModel::human).orElse("-"));
        facts.add("session " + server.sessionId()
                + "   tmp " + (server.tmpFolderPresent() ? "present" : "cleaned"));
        server.info().flatMap(info -> info.advertisedIp())
                .ifPresent(ip -> facts.add("advertised " + ip));

        // ALWAYS the file, never docker logs: these containers auto-remove, so Docker discards
        // their output the moment the server stops -- the file is what survives
        if (!server.logsReadable()) {
            return new EntityView(server.name(), facts,
                    new EntityView.LogSource.None("logs/" + server.sessionId()
                            + "/latest.log is not readable by this user"),
                    "It is owned by root (the ServersManager container wrote it). Press 'e', or "
                            + "run 'watchwolf logs --session " + server.sessionId() + "'.", false);
        }
        // A finished server's log file still reads fine -- it just will never grow again, so
        // "following" it is meaningless. isRunning() is exactly the signal for that.
        return new EntityView(server.name(), facts,
                new EntityView.LogSource.FileLog(this.layout.sessionLogFile(server.sessionId())),
                null, server.isRunning());
    }

    private EntityView clientView(ClientStatus client) {
        List<String> facts = new ArrayList<>();
        facts.add("ports " + client.portsLabel() + " (connector/images)");
        facts.add("joined " + client.minecraftServer().orElse("(unknown)"));
        facts.add("identified " + client.confidence().name().toLowerCase()
                + (client.username().isEmpty()
                   ? "   -- the port is listening but the log never named it" : ""));

        if (client.username().isEmpty()) {
            return new EntityView(client.displayName(), facts,
                    new EntityView.LogSource.None(
                            "this bot's username is unknown, so its lines cannot be picked out of "
                            + "the ClientsManager's shared output"),
                    "Bots are threads inside one container, not containers of their own, so their "
                    + "only output is that shared stream. Open ClientsManager to read all of it.",
                    false);
        }
        // one shared stream for every bot; the line prefix is the only way to separate them. A bot
        // only appears in the model at all while its port is still listening (see
        // SocketAndLogClientDiscovery), so reaching this branch already means it is live.
        return new EntityView(client.displayName(), facts,
                new EntityView.LogSource.FilteredContainerLog(
                        this.snapshot.clientsManager().name(),
                        "[" + client.username().orElseThrow() + " - "),
                null, true);
    }

    static String human(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m";
        if (seconds < 86400) return (seconds / 3600) + "h" + ((seconds % 3600) / 60) + "m";
        return (seconds / 86400) + "d";
    }
}
