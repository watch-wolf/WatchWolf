package dev.watchwolf.cli.tui;

import com.googlecode.lanterna.TextColor;

/**
 * The palette both screens share.
 *
 * <p>Deliberately restrained and btop-adjacent: a dim frame, a bright title, and colour used only
 * where it carries meaning -- state, and how confident a row is.
 */
public final class Theme {
    public static final TextColor BACKGROUND = TextColor.ANSI.DEFAULT;
    public static final TextColor FRAME = TextColor.ANSI.BLUE_BRIGHT;
    public static final TextColor TITLE = TextColor.ANSI.CYAN_BRIGHT;
    public static final TextColor TEXT = TextColor.ANSI.WHITE;
    public static final TextColor DIM = TextColor.ANSI.BLACK_BRIGHT;
    public static final TextColor OK = TextColor.ANSI.GREEN_BRIGHT;
    public static final TextColor WARN = TextColor.ANSI.YELLOW_BRIGHT;
    public static final TextColor BAD = TextColor.ANSI.RED_BRIGHT;
    public static final TextColor SELECTED_BACKGROUND = TextColor.ANSI.BLUE;
    public static final TextColor HEADING = TextColor.ANSI.MAGENTA_BRIGHT;

    private Theme() {
    }

    public static TextColor forState(String state) {
        return switch (state) {
            case "online", "running", "joined", "ok" -> OK;
            case "starting", "finished" -> WARN;
            case "offline" -> DIM;
            default -> TEXT;
        };
    }
}
