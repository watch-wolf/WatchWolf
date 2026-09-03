package dev.watchwolf.cli.tui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;

/**
 * Box drawing and clipped text, so neither screen has to think about the edges.
 *
 * <p>Every write is clipped to the terminal, because a long container name or a wide log line must
 * never wrap and shove the layout down a row.
 */
public final class Painter {
    private final TextGraphics graphics;
    private final TerminalSize size;

    public Painter(TextGraphics graphics, TerminalSize size) {
        this.graphics = graphics;
        this.size = size;
    }

    public int width()  { return this.size.getColumns(); }
    public int height() { return this.size.getRows(); }

    public void clear() {
        this.graphics.setBackgroundColor(Theme.BACKGROUND);
        this.graphics.fill(' ');
    }

    /** A bordered panel with a title in its top edge, btop-style: {@code ┌─ title ─────┐}. */
    public void panel(int x, int y, int width, int height, String title) {
        this.graphics.setForegroundColor(Theme.FRAME);
        this.graphics.setBackgroundColor(Theme.BACKGROUND);

        this.graphics.setCharacter(x, y, TextCharacter.fromCharacter('┌')[0]);
        this.graphics.setCharacter(x + width - 1, y, TextCharacter.fromCharacter('┐')[0]);
        this.graphics.setCharacter(x, y + height - 1, TextCharacter.fromCharacter('└')[0]);
        this.graphics.setCharacter(x + width - 1, y + height - 1,
                TextCharacter.fromCharacter('┘')[0]);

        for (int i = 1; i < width - 1; i++) {
            this.graphics.setCharacter(x + i, y, TextCharacter.fromCharacter('─')[0]);
            this.graphics.setCharacter(x + i, y + height - 1,
                    TextCharacter.fromCharacter('─')[0]);
        }
        for (int i = 1; i < height - 1; i++) {
            this.graphics.setCharacter(x, y + i, TextCharacter.fromCharacter('│')[0]);
            this.graphics.setCharacter(x + width - 1, y + i,
                    TextCharacter.fromCharacter('│')[0]);
        }

        if (title != null && !title.isBlank()) {
            this.text(x + 2, y, " " + title + " ", Theme.TITLE);
        }
    }

    /** A separator across a panel, for a footer hint line. */
    public void divider(int x, int y, int width) {
        this.graphics.setForegroundColor(Theme.FRAME);
        this.graphics.setCharacter(x, y, TextCharacter.fromCharacter('├')[0]);
        this.graphics.setCharacter(x + width - 1, y, TextCharacter.fromCharacter('┤')[0]);
        for (int i = 1; i < width - 1; i++) {
            this.graphics.setCharacter(x + i, y, TextCharacter.fromCharacter('─')[0]);
        }
    }

    public void text(int x, int y, String text, TextColor colour) {
        this.text(x, y, text, colour, Theme.BACKGROUND);
    }

    public void text(int x, int y, String text, TextColor foreground, TextColor background) {
        if (y < 0 || y >= this.height() || text == null) return;

        String clipped = text;
        int available = this.width() - x;
        if (available <= 0) return;
        if (clipped.length() > available) clipped = clipped.substring(0, available);

        this.graphics.setForegroundColor(foreground);
        this.graphics.setBackgroundColor(background);
        this.graphics.putString(x, y, clipped);
        this.graphics.setBackgroundColor(Theme.BACKGROUND);
    }

    /** A whole row painted in one background -- how the cursor is shown. */
    public void row(int x, int y, int width, String text, TextColor foreground,
                    TextColor background) {
        if (y < 0 || y >= this.height()) return;

        StringBuilder padded = new StringBuilder(text == null ? "" : text);
        while (padded.length() < width) padded.append(' ');
        this.text(x, y, padded.substring(0, Math.min(padded.length(), width)),
                foreground, background);
    }

    public static String fit(String text, int width) {
        if (text == null) return " ".repeat(Math.max(0, width));
        if (text.length() >= width) return text.substring(0, Math.max(0, width));
        return text + " ".repeat(width - text.length());
    }
}
