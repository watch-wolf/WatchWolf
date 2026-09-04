package dev.watchwolf.cli.docker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A container to start. Built fluently so call sites read as the docker command they replace. */
public final class RunSpec {
    private final String image;
    private String name;
    private final List<String> command = new ArrayList<>();
    private final List<String> entrypoint = new ArrayList<>();
    private final Map<String, String> binds = new LinkedHashMap<>();
    private final Map<String, String> environment = new LinkedHashMap<>();
    private String workingDir;
    private boolean autoRemove = true;
    private boolean hostNetwork;
    private String user;

    private RunSpec(String image) {
        this.image = image;
    }

    public static RunSpec of(String image) {
        return new RunSpec(image);
    }

    public RunSpec named(String name)                  { this.name = name; return this; }
    public RunSpec withCommand(String... args)         { this.command.addAll(List.of(args)); return this; }
    public RunSpec withEntrypoint(String... args)      { this.entrypoint.addAll(List.of(args)); return this; }
    public RunSpec bind(String host, String inside)    { this.binds.put(host, inside); return this; }
    public RunSpec env(String key, String value)       { this.environment.put(key, value); return this; }
    public RunSpec workingDir(String dir)              { this.workingDir = dir; return this; }
    public RunSpec autoRemove(boolean autoRemove)      { this.autoRemove = autoRemove; return this; }
    public RunSpec hostNetwork(boolean hostNetwork)    { this.hostNetwork = hostNetwork; return this; }
    public RunSpec asUser(String user)                 { this.user = user; return this; }

    public String image()                    { return this.image; }
    public String name()                     { return this.name; }
    public List<String> command()            { return List.copyOf(this.command); }
    public List<String> entrypoint()         { return List.copyOf(this.entrypoint); }
    public Map<String, String> binds()       { return Map.copyOf(this.binds); }
    public Map<String, String> environment() { return Map.copyOf(this.environment); }
    public String workingDir()               { return this.workingDir; }
    public boolean autoRemove()              { return this.autoRemove; }
    public boolean hostNetwork()             { return this.hostNetwork; }
    public String user()                     { return this.user; }

    @Override
    public String toString() {
        return "run " + this.image + (this.name == null ? "" : " --name " + this.name);
    }
}
