package dev.watchwolf.cli.tui.install;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;

import dev.watchwolf.cli.tui.Painter;
import dev.watchwolf.cli.tui.Theme;

import java.io.IOException;
import java.util.List;

/**
 * One message with an {@code < OK >}, waiting for a person.
 *
 * <p>Exists for a single case: an install that was sent to the background finished with nobody
 * watching, so its result -- "install successful", or which steps failed -- has to be delivered the
 * next time somebody runs {@code build}, before the menu opens. Printing it would put it in
 * scrollback that Lanterna is about to clear; this makes acknowledging it deliberate, and the
 * record is only deleted once it has been.
 */
public final class AcknowledgeScreen {
    private final String title;
    private final List<String> lines;
    private final boolean good;

    public AcknowledgeScreen(String title, List<String> lines, boolean good) {
        this.title = title;
        this.lines = List.copyOf(lines);
        this.good = good;
    }

    public void run() throws IOException {
        this.runOn(new TerminalScreen(new DefaultTerminalFactory().createTerminal()));
    }

    /** Package-visible for a test driving a virtual terminal, as the other screens are. */
    void runOn(Screen screen) throws IOException {
        screen.startScreen();
        screen.setCursorPosition(null);
        try {
            while (true) {
                this.draw(screen);
                // blocking: this frame never animates, so there is nothing to redraw between keys
                KeyStroke key = screen.readInput();
                if (key != null) return;
            }
        } finally {
            screen.stopScreen();
        }
    }

    private void draw(Screen screen) throws IOException {
        TerminalSize size = screen.doResizeIfNecessary();
        if (size == null) size = screen.getTerminalSize();

        Painter painter = new Painter(screen.newTextGraphics(), size);
        painter.clear();

        int width = painter.width();
        int height = painter.height();
        int boxWidth = Math.min(width - 4, 72);
        int boxHeight = Math.min(height - 2, this.lines.size() + 6);
        int x = Math.max(0, (width - boxWidth) / 2);
        int y = Math.max(0, (height - boxHeight) / 2);

        painter.panel(x, y, boxWidth, boxHeight, null);
        TextColor accent = this.good ? Theme.OK : Theme.BAD;
        painter.text(x + 2, y, " " + this.title + " ", accent);

        int row = y + 2;
        for (String line : this.lines) {
            if (row >= y + boxHeight - 3) break;
            painter.text(x + 3, row++, Painter.fit(line, boxWidth - 6), Theme.TEXT);
        }

        painter.text(x + (boxWidth - 8) / 2, y + boxHeight - 2, "< OK >", accent);
        screen.refresh();
    }
}
