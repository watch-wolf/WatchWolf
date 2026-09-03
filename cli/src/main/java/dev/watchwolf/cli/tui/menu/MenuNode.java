package dev.watchwolf.cli.tui.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One row of the menuconfig screen.
 *
 * <p>Pure data: no terminal code, so the whole navigation and selection behaviour is unit-testable.
 */
public final class MenuNode {
    public enum Kind {
        /** {@code [*]} -- toggled with space. */
        CHECK,
        /** {@code [ text ]} -- an editable value. */
        TEXT,
        /** {@code ( ) / (*)} -- one of a group. */
        RADIO,
        /** {@code --->} -- descends with Enter. */
        SUBMENU,
        /** A read-only caption. */
        LABEL
    }

    private final String id;
    private final Kind kind;
    private String label;
    private final List<MenuNode> children = new ArrayList<>();

    private boolean checked;
    private String value;
    private String help;
    private String radioGroup;
    private boolean enabled = true;
    private String disabledReason;
    private String annotation;

    public MenuNode(String id, Kind kind, String label) {
        this.id = id;
        this.kind = kind;
        this.label = label;
    }

    public static MenuNode check(String id, String label, boolean checked) {
        MenuNode node = new MenuNode(id, Kind.CHECK, label);
        node.checked = checked;
        return node;
    }

    public static MenuNode text(String id, String label, String value) {
        MenuNode node = new MenuNode(id, Kind.TEXT, label);
        node.value = value;
        return node;
    }

    public static MenuNode radio(String id, String label, String group, boolean selected) {
        MenuNode node = new MenuNode(id, Kind.RADIO, label);
        node.radioGroup = group;
        node.checked = selected;
        return node;
    }

    public static MenuNode submenu(String id, String label) {
        return new MenuNode(id, Kind.SUBMENU, label);
    }

    public static MenuNode label(String id, String label) {
        return new MenuNode(id, Kind.LABEL, label);
    }

    public MenuNode withHelp(String help) {
        this.help = help;
        return this;
    }

    public MenuNode withAnnotation(String annotation) {
        this.annotation = annotation;
        return this;
    }

    public MenuNode with(MenuNode... children) {
        this.children.addAll(List.of(children));
        return this;
    }

    public MenuNode add(MenuNode child) {
        this.children.add(child);
        return this;
    }

    public String id()                       { return this.id; }
    public Kind kind()                       { return this.kind; }
    public String label()                    { return this.label; }
    public List<MenuNode> children()         { return this.children; }
    public boolean isChecked()               { return this.checked; }
    public String value()                    { return this.value; }
    public Optional<String> help()           { return Optional.ofNullable(this.help); }
    public Optional<String> annotation()     { return Optional.ofNullable(this.annotation); }
    public String radioGroup()               { return this.radioGroup; }
    public boolean isEnabled()               { return this.enabled; }
    public Optional<String> disabledReason()  { return Optional.ofNullable(this.disabledReason); }

    public void setLabel(String label)       { this.label = label; }
    public void setChecked(boolean checked)  { this.checked = checked; }
    public void setValue(String value)       { this.value = value; }
    public void setAnnotation(String note)   { this.annotation = note; }

    /** Greyed out with a stated reason -- never silently unclickable. */
    public void disable(String reason) {
        this.enabled = false;
        this.disabledReason = reason;
        if (this.kind == Kind.CHECK) this.checked = false;
    }

    public void enable() {
        this.enabled = true;
        this.disabledReason = null;
    }

    public boolean isSelectable() {
        return this.kind != Kind.LABEL && this.enabled;
    }

    public Optional<MenuNode> find(String id) {
        if (this.id.equals(id)) return Optional.of(this);
        for (MenuNode child : this.children) {
            Optional<MenuNode> found = child.find(id);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    /** Rendered prefix: {@code [*]}, {@code [ ]}, {@code (*)}, {@code --->} ... */
    public String marker() {
        return switch (this.kind) {
            case CHECK -> this.checked ? "[*]" : "[ ]";
            case RADIO -> this.checked ? "(*)" : "( )";
            case SUBMENU -> "   ";
            case TEXT, LABEL -> "   ";
        };
    }
}
