package dev.watchwolf.cli.tui;

import dev.watchwolf.cli.docker.ContainerSnapshot;
import dev.watchwolf.cli.docker.PortBindingInfo;
import dev.watchwolf.cli.inventory.ClientDiscovery;
import dev.watchwolf.cli.inventory.ClientStatus;
import dev.watchwolf.cli.inventory.EnvironmentSnapshot;
import dev.watchwolf.cli.inventory.ManagerStatus;
import dev.watchwolf.cli.inventory.McServerStatus;
import dev.watchwolf.cli.layout.InstallLayout;
import dev.watchwolf.cli.layout.RuntimeFlavor;
import dev.watchwolf.cli.model.Confidence;
import dev.watchwolf.cli.model.SessionInfo;
import dev.watchwolf.cli.tui.monitor.EntityView;
import dev.watchwolf.cli.tui.monitor.MonitorModel;
import dev.watchwolf.cli.tui.monitor.MonitorRow;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class MonitorModelShould {
    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");
    private static final InstallLayout LAYOUT =
            new InstallLayout(Paths.get("/home/someone/WatchWolf"), RuntimeFlavor.RELEASE);

    private static ContainerSnapshot container(String name, String state,
                                               PortBindingInfo... ports) {
        return new ContainerSnapshot("id-" + name, name, "image:latest", state, "Up",
                NOW.minusSeconds(600), List.of(ports));
    }

    private static EnvironmentSnapshot snapshot(List<McServerStatus> servers,
                                                List<ClientStatus> clients, String limitation) {
        return new EnvironmentSnapshot(NOW, true, "29.4.3", null, true,
                new ManagerStatus(ManagerStatus.Kind.SERVERS_MANAGER,
                        container("ServersManager", "running",
                                new PortBindingInfo(8000, 8000, "tcp")), true, null),
                new ManagerStatus(ManagerStatus.Kind.CLIENTS_MANAGER,
                        container("ClientsManager", "running"), true, null),
                servers,
                new ClientDiscovery.Result(clients, "clients (" + clients.size() + ")", limitation),
                "192.168.1.193");
    }

    private static McServerStatus server(String id, String type, String version) {
        return new McServerStatus(id,
                container("MC_Server-" + id, "running",
                        new PortBindingInfo(8001, 25565, "tcp"),
                        new PortBindingInfo(8002, 25566, "tcp")),
                new SessionInfo(id, Map.of("serverType", type, "serverVersion", version)),
                true, true, null);
    }

    /** No container -- the run is over; only logs/<id>/ survives. isRunning() is false. */
    private static McServerStatus finishedServer(String id, String type, String version) {
        return new McServerStatus(id, null,
                new SessionInfo(id, Map.of("serverType", type, "serverVersion", version)),
                false, true, null);
    }

    @Test
    public void showNoLogLinesOnTheOverview() {
        // logs belong to one entity; the overview is an inventory, so its whole height goes to
        // the tree
        MonitorModel model = new MonitorModel(LAYOUT,
                snapshot(List.of(server("1772387923303", "Spigot", "1.8.8")), List.of(), null));

        assertFalse(model.isInEntityView());
        assertTrue(model.entityView().isEmpty(), "no entity view until you enter one");
    }

    @Test
    public void groupChildrenUnderTheirManager() {
        MonitorModel model = new MonitorModel(LAYOUT, snapshot(
                List.of(server("1772387923303", "Spigot", "1.8.8")),
                List.of(new ClientStatus(7001, "Alice", "127.0.0.1:8001", Confidence.OBSERVED)),
                null));

        List<MonitorRow> rows = model.rows();
        assertEquals("ServersManager", rows.get(0).name());
        assertEquals(MonitorRow.Kind.SERVER, rows.get(1).kind());
        assertEquals(1, rows.get(1).depth());
        assertEquals("ClientsManager", rows.get(2).name());
        assertEquals(MonitorRow.Kind.CLIENT, rows.get(3).kind());
        assertEquals(1, rows.get(3).depth());
    }

    @Test
    public void showAServersPortPairAndItsTypeFromInfoTxt() {
        MonitorModel model = new MonitorModel(LAYOUT,
                snapshot(List.of(server("1772387923303", "Paper", "1.20.4")), List.of(), null));

        MonitorRow row = model.rows().get(1);
        assertEquals("8001/8002", row.ports());
        assertEquals("Paper", row.type());
        assertEquals("1.20.4", row.version());
    }

    @Test
    public void showAnUnnamedBotByItsPortRatherThanGuessing() {
        MonitorModel model = new MonitorModel(LAYOUT, snapshot(List.of(),
                List.of(new ClientStatus(7005, null, null, Confidence.INFERRED)), null));

        MonitorRow row = model.rows().stream()
                .filter(r -> r.kind() == MonitorRow.Kind.CLIENT).findFirst().orElseThrow();
        assertEquals("bot@7005", row.name());
        assertEquals(Confidence.INFERRED, row.confidence());
    }

    @Test
    public void putTheDiscoveryCaveatOnScreen() {
        MonitorModel model = new MonitorModel(LAYOUT, snapshot(List.of(), List.of(),
                "2 bot(s) named in the log are no longer listening."));

        assertTrue(model.rows().stream().anyMatch(row -> row.kind() == MonitorRow.Kind.NOTE
                && row.name().contains("no longer listening")));
    }

    @Test
    public void readAServersLogFromTheFileNotFromDocker() {
        // MC server containers run with --autoRemove, so Docker throws their output away the
        // instant the server stops; logs/<id>/latest.log is what survives
        MonitorModel model = new MonitorModel(LAYOUT,
                snapshot(List.of(server("1772387923303", "Spigot", "1.8.8")), List.of(), null));

        model.moveDown();
        assertTrue(model.enter());

        EntityView view = model.entityView().orElseThrow();
        assertInstanceOf(EntityView.LogSource.FileLog.class, view.logSource());
        assertEquals(LAYOUT.sessionLogFile("1772387923303"),
                ((EntityView.LogSource.FileLog) view.logSource()).path());
        assertTrue(view.logIsLive(), "a running server can still produce new lines");
    }

    @Test
    public void markAFinishedServersLogAsNotLive() {
        // the bug report: "following" implied more output was coming even for a server that had
        // already exited -- nothing will ever be appended to its logs/<id>/latest.log again once
        // the container (which wrote it) is gone
        MonitorModel model = new MonitorModel(LAYOUT, snapshot(
                List.of(finishedServer("1772387923303", "Spigot", "1.8.8")), List.of(), null));

        model.moveDown();
        assertTrue(model.enter());

        EntityView view = model.entityView().orElseThrow();
        assertInstanceOf(EntityView.LogSource.FileLog.class, view.logSource());
        assertFalse(view.logIsLive(), "a finished server's log file will never grow again");
    }

    @Test
    public void readAManagersLogFromItsContainer() {
        MonitorModel model = new MonitorModel(LAYOUT, snapshot(List.of(), List.of(), null));

        assertTrue(model.enter());
        EntityView view = model.entityView().orElseThrow();

        assertInstanceOf(EntityView.LogSource.ContainerLog.class, view.logSource());
        assertEquals("ServersManager",
                ((EntityView.LogSource.ContainerLog) view.logSource()).containerName());
        assertTrue(view.logIsLive(), "an up manager is still streaming output");
    }

    @Test
    public void markAStoppedManagersLogAsNotLive() {
        EnvironmentSnapshot stopped = new EnvironmentSnapshot(NOW, true, "29.4.3", null, true,
                new ManagerStatus(ManagerStatus.Kind.SERVERS_MANAGER, null, false, null),
                new ManagerStatus(ManagerStatus.Kind.CLIENTS_MANAGER,
                        container("ClientsManager", "running"), true, null),
                List.of(), ClientDiscovery.Result.unavailable("not running"), "192.168.1.193");

        MonitorModel model = new MonitorModel(LAYOUT, stopped);
        assertTrue(model.enter());   // ServersManager is the first row

        EntityView view = model.entityView().orElseThrow();
        assertInstanceOf(EntityView.LogSource.None.class, view.logSource());
        assertFalse(view.logIsLive());
    }

    @Test
    public void filterOneBotOutOfTheManagersSharedStream() {
        // every bot writes to the one ClientsManager stdout, prefixed [<user> - <server>]
        MonitorModel model = new MonitorModel(LAYOUT, snapshot(List.of(),
                List.of(new ClientStatus(7001, "Alice", "127.0.0.1:8001", Confidence.OBSERVED)),
                null));

        model.moveDown();       // ClientsManager
        model.moveDown();       // Alice
        assertTrue(model.enter());

        EntityView view = model.entityView().orElseThrow();
        EntityView.LogSource.FilteredContainerLog source =
                assertInstanceOf(EntityView.LogSource.FilteredContainerLog.class, view.logSource());
        assertEquals("ClientsManager", source.containerName());
        assertEquals("[Alice - ", source.linePrefix());
        // a bot only appears in the model at all while its port is still listening, so reaching
        // this branch already means it is live
        assertTrue(view.logIsLive());
    }

    @Test
    public void explainWhyAnUnnamedBotHasNoLogOfItsOwn() {
        MonitorModel model = new MonitorModel(LAYOUT, snapshot(List.of(),
                List.of(new ClientStatus(7005, null, null, Confidence.INFERRED)), null));

        model.moveDown();
        model.moveDown();
        model.enter();

        EntityView view = model.entityView().orElseThrow();
        assertInstanceOf(EntityView.LogSource.None.class, view.logSource());
        assertTrue(view.unavailableReason().contains("threads inside one container"));
        assertFalse(view.logIsLive());
    }

    @Test
    public void comeBackToTheOverviewOnEscape() {
        MonitorModel model = new MonitorModel(LAYOUT, snapshot(List.of(), List.of(), null));

        model.enter();
        assertTrue(model.isInEntityView());
        model.back();
        assertFalse(model.isInEntityView());
        assertTrue(model.entityView().isEmpty());
    }

    @Test
    public void keepTheCursorOnTheSameEntityAcrossPolls() {
        MonitorModel model = new MonitorModel(LAYOUT, snapshot(
                List.of(server("1772387923303", "Spigot", "1.8.8")), List.of(), null));
        model.moveDown();
        String before = model.selectedRow().orElseThrow().key();

        // a new poll arrives with an extra server ahead of it in the list
        model.update(snapshot(List.of(server("1772387999999", "Paper", "1.20.4"),
                server("1772387923303", "Spigot", "1.8.8")), List.of(), null));

        assertEquals(before, model.selectedRow().orElseThrow().key());
    }

    @Test
    public void degradeToOneMessageWhenDockerIsUnreachable() {
        EnvironmentSnapshot broken = new EnvironmentSnapshot(NOW, false, null,
                "Cannot connect to the Docker daemon", false,
                new ManagerStatus(ManagerStatus.Kind.SERVERS_MANAGER, null, false, null),
                new ManagerStatus(ManagerStatus.Kind.CLIENTS_MANAGER, null, false, null),
                List.of(), ClientDiscovery.Result.unavailable("Docker is unreachable."), null);

        MonitorModel model = new MonitorModel(LAYOUT, broken);

        assertEquals(1, model.rows().size());
        assertTrue(model.rows().get(0).name().contains("Cannot connect to the Docker daemon"));
    }

    @Test
    public void collapseAManagersChildren() {
        MonitorModel model = new MonitorModel(LAYOUT,
                snapshot(List.of(server("1772387923303", "Spigot", "1.8.8")), List.of(), null));
        int expanded = model.rows().size();

        model.toggleCollapse();

        assertTrue(model.rows().size() < expanded);
    }
}
