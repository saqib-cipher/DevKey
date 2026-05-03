package com.codekeys.ime;

import android.inputmethodservice.InputMethodService;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

/**
 * InputEngine — thin wrapper around {@link InputConnection} that centralises
 * text manipulation primitives used by the IME. Keeping this in its own class
 * makes the IME class easier to read and lets us unit-test selection-safe
 * behaviours independently from the view code.
 */
final class InputEngine {

    private final InputMethodService ime;

    InputEngine(InputMethodService ime) {
        this.ime = ime;
    }

    private InputConnection ic() {
        return ime.getCurrentInputConnection();
    }

    boolean hasSelection() {
        InputConnection ic = ic();
        if (ic == null) return false;
        ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
        return et != null && et.selectionStart != et.selectionEnd;
    }

    int cursorPosition() {
        InputConnection ic = ic();
        if (ic == null) return -1;
        ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
        return et == null ? -1 : et.selectionStart;
    }

    int totalLength() {
        InputConnection ic = ic();
        if (ic == null) return -1;
        ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
        return (et == null || et.text == null) ? -1 : et.text.length();
    }

    /** Commits text, replacing any selection automatically. */
    void commit(String text) {
        InputConnection ic = ic();
        if (ic != null && text != null) ic.commitText(text, 1);
    }

    /**
     * Backspace primitive used by both single-tap and the accelerating hold-loop:
     *   • If a selection is active, removes the selection only (one user-visible action).
     *   • Otherwise removes one code-point before the caret.
     * Returns {@code true} when something was actually deleted.
     */
    boolean deleteOne() {
        InputConnection ic = ic();
        if (ic == null) return false;
        if (hasSelection()) {
            ic.commitText("", 1);
            return true;
        }
        CharSequence before = ic.getTextBeforeCursor(2, 0);
        if (TextUtils.isEmpty(before)) return false;
        // Handle surrogate pairs so emoji deletion is one tap, not two.
        int n = (before.length() >= 2 && Character.isSurrogatePair(before.charAt(0), before.charAt(1))) ? 2 : 1;
        ic.deleteSurroundingText(n, 0);
        return true;
    }

    /** Deletes the previous word (or the selection if any). */
    boolean deleteWord() {
        InputConnection ic = ic();
        if (ic == null) return false;
        if (hasSelection()) { ic.commitText("", 1); return true; }
        CharSequence before = ic.getTextBeforeCursor(64, 0);
        if (TextUtils.isEmpty(before)) return false;
        int i = before.length() - 1;
        int n = 0;
        while (i >= 0 && before.charAt(i) == ' ') { i--; n++; }
        while (i >= 0 && before.charAt(i) != ' ') { i--; n++; }
        if (n == 0) return false;
        ic.deleteSurroundingText(n, 0);
        return true;
    }

    /** Sends a hardware key event with optional meta state mask. */
    void sendKey(int keyCode, int metaState) {
        InputConnection ic = ic();
        if (ic == null) return;
        long t = System.currentTimeMillis();
        ic.sendKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0, metaState));
        ic.sendKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP,   keyCode, 0, metaState));
    }

    /** Returns the in-progress word at the caret (letters/digits/_ only). */
    String currentWord() {
        InputConnection ic = ic();
        if (ic == null) return "";
        CharSequence before = ic.getTextBeforeCursor(64, 0);
        if (before == null) return "";
        int i = before.length() - 1;
        StringBuilder sb = new StringBuilder();
        while (i >= 0) {
            char c = before.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') sb.insert(0, c);
            else break;
            i--;
        }
        return sb.toString();
    }

    /** Returns the word before the current one (skipping whitespace). */
    String previousWord() {
        InputConnection ic = ic();
        if (ic == null) return "";
        CharSequence before = ic.getTextBeforeCursor(64, 0);
        if (before == null) return "";
        String s = before.toString().trim();
        int lastSpace = s.lastIndexOf(' ');
        if (lastSpace < 0) {
            // Check if there's any non-word char before the current word
            int i = before.length() - 1;
            while (i >= 0 && (Character.isLetterOrDigit(before.charAt(i)) || before.charAt(i) == '_')) i--;
            while (i >= 0 && Character.isWhitespace(before.charAt(i))) i--;
            if (i < 0) return s; // single word
            int end = i + 1;
            while (i >= 0 && (Character.isLetterOrDigit(before.charAt(i)) || before.charAt(i) == '_')) i--;
            return before.subSequence(i + 1, end).toString();
        }
        return s.substring(lastSpace + 1);
    }

    /** Returns true if the caret is at the start of a sentence. */
    boolean isAtStartOfSentence() {
        InputConnection ic = ic();
        if (ic == null) return true;
        CharSequence before = ic.getTextBeforeCursor(128, 0);
        if (TextUtils.isEmpty(before)) return true;
        String s = before.toString().trim();
        if (s.isEmpty()) return true;
        char last = s.charAt(s.length() - 1);
        return last == '.' || last == '!' || last == '?' || last == '\n';
    }

    /**
     * Replaces the in-progress word with {@code suggestion + ' '}. If a
     * selection is active, replaces the selection instead.
     */
    void applySuggestion(String suggestion) {
        InputConnection ic = ic();
        if (ic == null) return;
        if (hasSelection()) {
            ic.commitText(suggestion + " ", 1);
            return;
        }
        String word = currentWord();
        if (!word.isEmpty()) ic.deleteSurroundingText(word.length(), 0);
        ic.commitText(suggestion + " ", 1);
    }
}
