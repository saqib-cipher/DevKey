package com.codekeys.ime;

/**
 * Enum-based panel state for the IME.
 *
 * <p>Replaces the previous {@code panelEmoji / panelClipboard / panelSymbols}
 * boolean flags with a single discriminated value, so the UI never ends up in
 * an inconsistent state where (say) two panels claim to be active at once.
 */
public enum PanelMode {
    KEYBOARD,
    SYMBOLS,
    EMOJI,
    CLIPBOARD,
    /** D-pad + selection helpers, entered from the Gboard-style cursor button. */
    CURSOR
}
