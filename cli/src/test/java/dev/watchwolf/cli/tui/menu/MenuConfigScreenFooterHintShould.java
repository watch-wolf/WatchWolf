package dev.watchwolf.cli.tui.menu;

import dev.watchwolf.cli.model.BuildPlan;
import dev.watchwolf.cli.model.McVersion;
import dev.watchwolf.cli.remote.WatchWolfWebClient;
import dev.watchwolf.cli.tui.Async;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The footer is where F8/F9 are advertised, so it has to agree with what they actually do. It used
 * to look only at the current level's own checkboxes, which meant "Server jars" -- a menu holding
 * nothing but the Spigot and Paper submenus -- offered no hint at all, while the keys worked
 * perfectly well there.
 */
public class MenuConfigScreenFooterHintShould {
    private static final MenuConfigScreen.VersionFetcher NO_OP_FETCHER =
            new MenuConfigScreen.VersionFetcher() {
                @Override public void fetchSpigot(Consumer<Async<List<McVersion>>> onState) { }
                @Override public void fetchPaper(Consumer<Async<List<McVersion>>> onState) { }
                @Override public void fetchUsualPlugins(
                        Consumer<Async<List<WatchWolfWebClient.UsualPlugin>>> onState) { }
                @Override public void cancel() { }
            };

    private MenuModel menu;
    private MenuConfigScreen screen;

    @BeforeEach
    void setUp() {
        this.menu = new MenuModel(BuildPlan.defaults(), "/home/someone/WatchWolf");
        this.menu.spigotLoaded(List.of(McVersion.of("1.20.4")));
        this.screen = new MenuConfigScreen(this.menu, NO_OP_FETCHER);
    }

    @Test
    public void offerBulkSelectionWhereThereAreCheckboxes() {
        String hint = this.screen.footerHint(this.menu.root());

        assertTrue(hint.contains("F8 select all"), hint);
        assertTrue(hint.contains("F9 deselect all"), hint);
    }

    @Test
    public void offerItOnAMenuHoldingOnlySubmenus() {
        MenuNode serverJars = this.menu.node(MenuModel.ID_SERVER_JARS).orElseThrow();
        assertTrue(serverJars.children().stream()
                        .noneMatch(child -> child.kind() == MenuNode.Kind.CHECK),
                "the fixture assumes Server jars holds only submenus");

        assertTrue(this.screen.footerHint(serverJars).contains("F8 select all"),
                "the keys reach every version inside, so the hint has to say so");
    }

    @Test
    public void stayQuietWhereThereIsNothingToSelect() {
        MenuNode installPath = this.menu.node(MenuModel.ID_INSTALL_PATH).orElseThrow();

        assertFalse(this.screen.footerHint(installPath).contains("F8"),
                "a text field has nothing to bulk-select");
    }
}
