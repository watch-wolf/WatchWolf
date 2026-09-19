package dev.watchwolf.cli.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class GlobalOptionsShould {
    @Test
    public void defaultToTheDevBranchWithNoFlags() {
        // 'master' is not in a state this CLI can rely on yet -- see MenuModel, where the branch
        // radio shows it but keeps it disabled. --branch stays as the explicit override below.
        GlobalOptions options = new GlobalOptions();

        assertEquals("dev", options.resolvedBranch());
    }

    @Test
    public void respectAnExplicitBranchOverride() {
        GlobalOptions options = new GlobalOptions();
        options.branch = "master";

        assertEquals("master", options.resolvedBranch());
    }

    @Test
    public void ignoreABlankExplicitBranch() {
        GlobalOptions options = new GlobalOptions();
        options.branch = "   ";

        assertEquals("dev", options.resolvedBranch());
    }
}
