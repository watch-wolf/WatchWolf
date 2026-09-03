package dev.watchwolf.cli.tui.monitor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class LogRingShould {
    @Test
    public void dropTheOldestLineOnceFull() {
        LogRing ring = new LogRing(3);
        ring.add("a");
        ring.add("b");
        ring.add("c");
        ring.add("d");   // "a" must fall off, not "d" being rejected

        assertEquals(List.of("b", "c", "d"), ring.window(10));
    }

    @Test
    public void windowTheNewestLinesWhenFollowing() {
        LogRing ring = new LogRing(10);
        for (int i = 0; i < 5; i++) ring.add("line " + i);

        assertEquals(List.of("line 2", "line 3", "line 4"), ring.window(3));
    }

    @Test
    public void scrollBackPinsTheViewAwayFromTheTail() {
        LogRing ring = new LogRing(10);
        for (int i = 0; i < 5; i++) ring.add("line " + i);

        ring.scrollBack(2);
        assertFalse(ring.isFollowing());
        assertEquals(List.of("line 0", "line 1", "line 2"), ring.window(3));

        ring.scrollToTail();
        assertTrue(ring.isFollowing());
        assertEquals(List.of("line 2", "line 3", "line 4"), ring.window(3));
    }

    @Test
    public void neverScrollPastTheStart() {
        LogRing ring = new LogRing(10);
        ring.add("only line");

        ring.scrollBack(100);
        assertEquals(List.of("only line"), ring.window(10));
    }

    @Test
    public void filterCaseInsensitivelyAndResetScrollOnANewFilter() {
        LogRing ring = new LogRing(10);
        ring.add("INFO starting up");
        ring.add("ERROR could not connect");
        ring.add("INFO ready");

        ring.scrollBack(5);
        ring.setFilter("error");   // changing the filter should reset scrollback to the tail

        assertTrue(ring.isFollowing());
        assertEquals(List.of("ERROR could not connect"), ring.window(10));
    }

    @Test
    public void clearResetsBothLinesAndScrollback() {
        LogRing ring = new LogRing(10);
        ring.add("a");
        ring.scrollBack(1);

        ring.clear();

        assertTrue(ring.isFollowing());
        assertTrue(ring.window(10).isEmpty());
    }

    @Test
    public void returnNothingForAZeroOrNegativeHeight() {
        LogRing ring = new LogRing(10);
        ring.add("a");

        assertTrue(ring.window(0).isEmpty());
        assertTrue(ring.window(-1).isEmpty());
    }
}
