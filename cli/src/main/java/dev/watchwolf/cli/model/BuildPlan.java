package dev.watchwolf.cli.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything {@code build} was asked to do -- the single input to the whole install run.
 *
 * <p>Both front ends produce one of these: the menuconfig screen edits it directly, and the flags
 * are converted into one. So everything downstream of the plan is front-end agnostic, and
 * {@code --print-plan} can dump exactly what a TUI session would have done.
 */
public final class BuildPlan {
    private final String branch;
    private final int parallelBuilders;

    private final boolean cloneServersManager;
    private final boolean cloneClientsManager;
    private final boolean cloneTester;
    private final boolean pullJdkImages;
    private final boolean downloadUsualPlugins;
    private final boolean downloadWatchWolfServer;
    private final boolean buildServersManagerImage;
    private final boolean buildClientsManagerImage;
    private final boolean registerStartup;
    private final boolean registerLauncher;
    private final boolean runSelfTest;

    private final boolean buildSpigot;
    private final boolean buildPaper;
    private final List<McVersion> spigotVersions;
    private final List<McVersion> paperVersions;
    private final Set<String> selfTestSuites;

    private BuildPlan(Builder builder) {
        this.branch = builder.branch;
        this.parallelBuilders = Math.max(1, builder.parallelBuilders);
        this.cloneServersManager = builder.cloneServersManager;
        this.cloneClientsManager = builder.cloneClientsManager;
        this.cloneTester = builder.cloneTester;
        this.pullJdkImages = builder.pullJdkImages;
        this.downloadUsualPlugins = builder.downloadUsualPlugins;
        this.downloadWatchWolfServer = builder.downloadWatchWolfServer;
        this.buildServersManagerImage = builder.buildServersManagerImage;
        this.buildClientsManagerImage = builder.buildClientsManagerImage;
        this.registerStartup = builder.registerStartup;
        this.registerLauncher = builder.registerLauncher;
        this.runSelfTest = builder.runSelfTest;
        this.buildSpigot = builder.buildSpigot;
        this.buildPaper = builder.buildPaper;
        this.spigotVersions = List.copyOf(builder.spigotVersions);
        this.paperVersions = List.copyOf(builder.paperVersions);
        this.selfTestSuites = Set.copyOf(builder.selfTestSuites);
    }

    public String branch()                    { return this.branch; }
    public int parallelBuilders()             { return this.parallelBuilders; }
    public boolean cloneServersManager()      { return this.cloneServersManager; }
    public boolean cloneClientsManager()      { return this.cloneClientsManager; }
    public boolean cloneTester()              { return this.cloneTester; }
    public boolean pullJdkImages()            { return this.pullJdkImages; }
    public boolean downloadUsualPlugins()     { return this.downloadUsualPlugins; }
    public boolean downloadWatchWolfServer()  { return this.downloadWatchWolfServer; }
    public boolean buildServersManagerImage() { return this.buildServersManagerImage; }
    public boolean buildClientsManagerImage() { return this.buildClientsManagerImage; }
    public boolean registerStartup()          { return this.registerStartup; }
    public boolean registerLauncher()         { return this.registerLauncher; }
    public boolean buildSpigot()              { return this.buildSpigot; }
    public boolean buildPaper()               { return this.buildPaper; }
    public List<McVersion> spigotVersions()   { return this.spigotVersions; }
    public List<McVersion> paperVersions()    { return this.paperVersions; }
    public Set<String> selfTestSuites()       { return this.selfTestSuites; }

    /**
     * The self-test needs the Tester checkout, so unticking that clone disables it. Enforced here
     * rather than only in the menu, so the flags cannot express the contradiction either.
     */
    public boolean runSelfTest() {
        return this.runSelfTest && this.cloneTester && !this.selfTestSuites.isEmpty();
    }

    public boolean anythingToBuild() {
        return (this.buildSpigot && !this.spigotVersions.isEmpty())
                || (this.buildPaper && !this.paperVersions.isEmpty());
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The sensible defaults: a full install on master, no startup registration, one builder. */
    public static BuildPlan defaults() {
        return builder().build();
    }

    public static final class Builder {
        private String branch = "master";
        private int parallelBuilders = 1;
        private boolean cloneServersManager = true;
        private boolean cloneClientsManager = true;
        private boolean cloneTester = true;
        private boolean pullJdkImages = true;
        private boolean downloadUsualPlugins = true;
        private boolean downloadWatchWolfServer = true;
        private boolean buildServersManagerImage = true;
        private boolean buildClientsManagerImage = true;
        private boolean registerStartup;
        private boolean registerLauncher;
        private boolean runSelfTest = true;
        private boolean buildSpigot = true;
        private boolean buildPaper = true;
        private final List<McVersion> spigotVersions = new ArrayList<>();
        private final List<McVersion> paperVersions = new ArrayList<>();
        private final Set<String> selfTestSuites =
                new LinkedHashSet<>(TesterSuiteCatalog.defaultSelection());

        private Builder() {
        }

        private Builder(BuildPlan plan) {
            this.branch = plan.branch;
            this.parallelBuilders = plan.parallelBuilders;
            this.cloneServersManager = plan.cloneServersManager;
            this.cloneClientsManager = plan.cloneClientsManager;
            this.cloneTester = plan.cloneTester;
            this.pullJdkImages = plan.pullJdkImages;
            this.downloadUsualPlugins = plan.downloadUsualPlugins;
            this.downloadWatchWolfServer = plan.downloadWatchWolfServer;
            this.buildServersManagerImage = plan.buildServersManagerImage;
            this.buildClientsManagerImage = plan.buildClientsManagerImage;
            this.registerStartup = plan.registerStartup;
            this.registerLauncher = plan.registerLauncher;
            this.runSelfTest = plan.runSelfTest;
            this.buildSpigot = plan.buildSpigot;
            this.buildPaper = plan.buildPaper;
            this.spigotVersions.addAll(plan.spigotVersions);
            this.paperVersions.addAll(plan.paperVersions);
            this.selfTestSuites.clear();
            this.selfTestSuites.addAll(plan.selfTestSuites);
        }

        public Builder branch(String branch)               { this.branch = branch; return this; }
        public Builder parallelBuilders(int n)             { this.parallelBuilders = n; return this; }
        public Builder cloneServersManager(boolean on)     { this.cloneServersManager = on; return this; }
        public Builder cloneClientsManager(boolean on)     { this.cloneClientsManager = on; return this; }
        public Builder cloneTester(boolean on)             { this.cloneTester = on; return this; }
        public Builder pullJdkImages(boolean on)           { this.pullJdkImages = on; return this; }
        public Builder downloadUsualPlugins(boolean on)    { this.downloadUsualPlugins = on; return this; }
        public Builder downloadWatchWolfServer(boolean on) { this.downloadWatchWolfServer = on; return this; }
        public Builder buildServersManagerImage(boolean on){ this.buildServersManagerImage = on; return this; }
        public Builder buildClientsManagerImage(boolean on){ this.buildClientsManagerImage = on; return this; }
        public Builder registerStartup(boolean on)         { this.registerStartup = on; return this; }
        public Builder registerLauncher(boolean on)        { this.registerLauncher = on; return this; }
        public Builder runSelfTest(boolean on)             { this.runSelfTest = on; return this; }
        public Builder buildSpigot(boolean on)             { this.buildSpigot = on; return this; }
        public Builder buildPaper(boolean on)              { this.buildPaper = on; return this; }

        public Builder spigotVersions(List<McVersion> versions) {
            this.spigotVersions.clear();
            this.spigotVersions.addAll(versions);
            return this;
        }

        public Builder paperVersions(List<McVersion> versions) {
            this.paperVersions.clear();
            this.paperVersions.addAll(versions);
            return this;
        }

        public Builder selfTestSuites(Set<String> suites) {
            this.selfTestSuites.clear();
            this.selfTestSuites.addAll(suites);
            return this;
        }

        public BuildPlan build() {
            return new BuildPlan(this);
        }
    }
}
