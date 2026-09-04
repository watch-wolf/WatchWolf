package dev.watchwolf.cli.tui.menu;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class MenuNodeShould {
    @Test
    public void markACheckboxWithItsCheckedState() {
        assertEquals("[ ]", MenuNode.check("id", "label", false).marker());
        assertEquals("[*]", MenuNode.check("id", "label", true).marker());
    }

    @Test
    public void markARadioWithItsSelectedState() {
        assertEquals("( )", MenuNode.radio("id", "label", "group", false).marker());
        assertEquals("(*)", MenuNode.radio("id", "label", "group", true).marker());
    }

    @Test
    public void markAnEmptySubmenuAsNone() {
        MenuNode submenu = MenuNode.submenu("id", "label");
        assertEquals(MenuNode.AggregateState.NONE, submenu.aggregateState());
        assertEquals("[ ]", submenu.marker());
    }

    @Test
    public void markASubmenuWithNothingCheckedAsNone() {
        MenuNode submenu = MenuNode.submenu("id", "label")
                .add(MenuNode.check("a", "a", false))
                .add(MenuNode.check("b", "b", false));

        assertEquals(MenuNode.AggregateState.NONE, submenu.aggregateState());
        assertEquals("[ ]", submenu.marker());
    }

    @Test
    public void markASubmenuWithEverythingCheckedAsAll() {
        MenuNode submenu = MenuNode.submenu("id", "label")
                .add(MenuNode.check("a", "a", true))
                .add(MenuNode.check("b", "b", true));

        assertEquals(MenuNode.AggregateState.ALL, submenu.aggregateState());
        assertEquals("[*]", submenu.marker());
    }

    @Test
    public void markASubmenuWithAPartialSelectionAsSome() {
        MenuNode submenu = MenuNode.submenu("id", "label")
                .add(MenuNode.check("a", "a", true))
                .add(MenuNode.check("b", "b", false));

        assertEquals(MenuNode.AggregateState.SOME, submenu.aggregateState());
        assertEquals("[o]", submenu.marker());
    }

    @Test
    public void rollUpAggregateStateThroughNestedSubmenus() {
        // Server jars -> Spigot -> versions, Server jars -> Paper -> versions: the outer marker
        // must reflect every CHECK descendant at any depth, not just direct children
        MenuNode spigot = MenuNode.submenu("spigot", "Spigot")
                .add(MenuNode.check("spigot:1.8.8", "1.8.8", true));
        MenuNode paper = MenuNode.submenu("paper", "Paper")
                .add(MenuNode.check("paper:1.20.4", "1.20.4", false));
        MenuNode serverJars = MenuNode.submenu("server-jars", "Server jars").add(spigot).add(paper);

        assertEquals(MenuNode.AggregateState.SOME, serverJars.aggregateState());

        paper.children().get(0).setChecked(true);
        assertEquals(MenuNode.AggregateState.ALL, serverJars.aggregateState());
    }

    @Test
    public void acceptAnyValueWithNoValidatorAttached() {
        MenuNode text = MenuNode.text("id", "label", "1");
        assertTrue(text.validate("").isEmpty());
        assertTrue(text.validate("anything at all").isEmpty());
    }

    @Test
    public void reportAValidationErrorFromAnAttachedValidator() {
        MenuNode text = MenuNode.text("id", "label", "1")
                .withValidator(value -> value.isBlank() ? Optional.of("must not be empty")
                        : Optional.empty());

        assertEquals(Optional.of("must not be empty"), text.validate(""));
        assertEquals(Optional.empty(), text.validate("something"));
    }
}
