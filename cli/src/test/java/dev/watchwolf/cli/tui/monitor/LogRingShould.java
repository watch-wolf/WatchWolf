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

    @Test
    public void replaceAllMustNotResetScrollback() {
        // this is the bug report: a file log has no push notification, so its tail is
        // periodically re-read wholesale (MonitorScreen.reloadFileLog). Using clear()+addAll()
        // for that reset scrollback on every re-read, snapping the view back to "following" and
        // jumping to the bottom roughly once a second even while someone was mid-scroll reading
        // history. replaceAll() exists specifically so a periodic re-read cannot do that.
        LogRing ring = new LogRing(10);
        for (int i = 0; i < 5; i++) ring.add("line " + i);

        ring.scrollBack(2);
        assertEquals(List.of("line 0", "line 1", "line 2"), ring.window(3));

        ring.replaceAll(List.of("line 0", "line 1", "line 2", "line 3", "line 4"));

        assertFalse(ring.isFollowing(), "replaceAll must not touch scrollBack");
        assertEquals(List.of("line 0", "line 1", "line 2"), ring.window(3));
    }

    @Test
    public void replaceAllStaysTheSameDistanceFromTheLiveEdgeAsNewLinesArrive() {
        // preserving the scrollBack COUNT (not an absolute index) is what makes "scrolled back 2
        // lines" keep meaning the same thing as the tail moves forward -- the natural behaviour
        // for a log viewer, and different from freezing at a stale position
        LogRing ring = new LogRing(10);
        for (int i = 0; i < 5; i++) ring.add("line " + i);
        ring.scrollBack(2);   // looking at lines 0,1,2 (of 0..4)

        ring.replaceAll(List.of("line 0", "line 1", "line 2", "line 3", "line 4", "line 5"));

        // still 2 lines back from the (now one line further along) tail
        assertEquals(List.of("line 1", "line 2", "line 3"), ring.window(3));
    }

    @Test
    public void replaceAllStillRespectsTheCapacity() {
        LogRing ring = new LogRing(3);
        ring.replaceAll(List.of("a", "b", "c", "d"));

        assertEquals(List.of("b", "c", "d"), ring.window(10));
    }

    @Test
    public void distinguishClearFromReplaceAll() {
        // clear() is for switching to a different entity, where starting fresh at the live edge
        // is exactly what is wanted -- the two must not be conflated
        LogRing ring = new LogRing(10);
        ring.add("a");
        ring.scrollBack(1);

        ring.clear();

        assertTrue(ring.isFollowing());
        assertTrue(ring.window(10).isEmpty());
    }
}
