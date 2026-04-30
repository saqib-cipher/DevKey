package com.codekeys.ime;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CodeKeysIME — coding-focused soft keyboard.
 *
 * This revision adds:
 *   • Material-3-expressive key-preview popup on every text/symbol key tap.
 *   • Smart Shift: tap once → next letter uppercase then auto-revert; double-tap
 *     → caps-lock until tapped again (Gboard-style).
 *   • Selection-safe operations: caps toggle, suggestion taps, snippet inserts,
 *     and clipboard paste no longer collapse a current selection.
 *   • Suggestion strip on top with autocomplete + simple correction candidates.
 *   • EditorInfo IME action support — Enter shows Go / Next / Done / Search /
 *     Send / Previous and dispatches the appropriate IME action.
 *   • Tab button removed; dedicated Symbols (?123) & Emoji (☺) panels.
 *   • Settings/language picker triggered from a single ⚙ button → bottom-left
 *     popup menu listing all supported languages plus a “Settings…” entry that
 *     launches {@link SettingsActivity}.
 */
public class CodeKeysIME extends InputMethodService {

    // ─── Constants ────────────────────────────────────────────────────────────
    /** Caps-state machine values. */
    private static final int CAPS_OFF      = 0;
    private static final int CAPS_SINGLE   = 1; // one shot, reverts on next letter
    private static final int CAPS_LOCKED   = 2; // sticky until toggled off

    private static final long DOUBLE_TAP_MS = 300L;

    // ─── State ────────────────────────────────────────────────────────────────
    private int capsState = CAPS_OFF;
    private long lastCapsTapMs = 0L;

    private boolean panelSymbols = false;   // ?123 panel showing instead of QWERTY
    private boolean panelEmoji   = false;   // emoji panel
    private String currentLang = "GENERAL";
    private SharedPreferences prefs;
    private Vibrator vibrator;

    private final List<String> clipboard = new ArrayList<>();

    // Last suggestion list, kept so we can rebuild the strip without re-scanning.
    private List<String> currentSuggestions = new ArrayList<>();

    // Active key-preview popup (tracked to dismiss on rapid retap).
    private PopupWindow activePreview;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // Views
    private View keyboardView;
    private LinearLayout rowSuggestions;
    private LinearLayout rowNumbers, rowLetters1, rowLetters2, rowLetters3;
    private LinearLayout rowSymbols, rowSnippets, rowNav;
    private Button btnCaps, btnEnter, btnSettings, btnSymbolsPanel, btnEmoji, btnSpace;

    // ─── Languages ────────────────────────────────────────────────────────────
    /**
     * Built-in language list. The user can add custom presets through
     * {@link SettingsActivity}; those are loaded on top in
     * {@link #getAllLanguages()}.
     */
    private static final String[] BUILTIN_LANGS = {"GENERAL", "C", "JAVA", "PYTHON", "JS"};

    private static final HashMap<String, String[][]> LANG_SNIPPETS = new HashMap<>();
    static {
        LANG_SNIPPETS.put("C", new String[][]{
            {"if",    "if () {\n\t\n}"},
            {"for",   "for (int i = 0; i < n; i++) {\n\t\n}"},
            {"while", "while () {\n\t\n}"},
            {"fn",    "void functionName() {\n\t\n}"},
            {"inc",   "#include <stdio.h>"},
            {"main",  "int main() {\n\t\n\treturn 0;\n}"},
            {"pf",    "printf(\"\");"},
            {"sf",    "scanf(\"%d\", &x);"}
        });
        LANG_SNIPPETS.put("JAVA", new String[][]{
            {"if",    "if () {\n\t\n}"},
            {"for",   "for (int i = 0; i < n; i++) {\n\t\n}"},
            {"forea", "for (Object item : collection) {\n\t\n}"},
            {"while", "while () {\n\t\n}"},
            {"class", "public class ClassName {\n\t\n}"},
            {"fn",    "public void methodName() {\n\t\n}"},
            {"sys",   "System.out.println(\"\");"},
            {"try",   "try {\n\t\n} catch (Exception e) {\n\te.printStackTrace();\n}"}
        });
        LANG_SNIPPETS.put("PYTHON", new String[][]{
            {"if",    "if :\n\t"},
            {"for",   "for i in range():\n\t"},
            {"forin", "for item in collection:\n\t"},
            {"while", "while :\n\t"},
            {"def",   "def function_name():\n\t"},
            {"class", "class ClassName:\n\tdef __init__(self):\n\t\t"},
            {"print", "print(\"\")"},
            {"imp",   "import "}
        });
        LANG_SNIPPETS.put("JS", new String[][]{
            {"if",    "if () {\n\t\n}"},
            {"for",   "for (let i = 0; i < n; i++) {\n\t\n}"},
            {"forea", "array.forEach((item) => {\n\t\n});"},
            {"fn",    "function name() {\n\t\n}"},
            {"arrow", "const fn = () => {\n\t\n};"},
            {"const", "const  = "},
            {"log",   "console.log(\"\");"},
            {"prom",  "new Promise((resolve, reject) => {\n\t\n});"}
        });
        LANG_SNIPPETS.put("GENERAL", new String[][]{
            {"tab",   "\t"},
            {"url",   "https://"},
            {"todo",  "// TODO: "},
            {"fixme", "// FIXME: "},
            {"note",  "// NOTE: "}
        });
    }

    private static final HashMap<String, String[]> LANG_SYMBOLS = new HashMap<>();
    static {
        String[] base = {"{","}","(",")","[","]","<",">",";",":","\"","'","`","|","\\","/","=","+","-","*","&","%","$","#","@","!"};
        LANG_SYMBOLS.put("GENERAL", base);
        LANG_SYMBOLS.put("C",     new String[]{"{","}","(",")","[","]","*","&",";","\"","'","#","<",">","=","+","-","/","%","!","~","^","|","\\","?",","});
        LANG_SYMBOLS.put("JAVA",   new String[]{"{","}","(",")","[","]",";",".",",","\"","'","@","=","+","-","*","/","!","?","<",">","&","|","^","~","%"});
        LANG_SYMBOLS.put("PYTHON", new String[]{":","(",")","{","}","[","]","=","\"","'","#","*","+","-","/","\\",".","@","%","^","&","|","~","<",">","!"});
        LANG_SYMBOLS.put("JS",     new String[]{"{","}","(",")","[","]",";",".","\"","'","`","=","=>","+","-","*","/","!","?","&&","||",":",",","@","#","%"});
    }

    private static final HashMap<String, String> AUTO_CLOSE = new HashMap<>();
    static {
        AUTO_CLOSE.put("(", ")");
        AUTO_CLOSE.put("{", "}");
        AUTO_CLOSE.put("[", "]");
        AUTO_CLOSE.put("\"", "\"");
        AUTO_CLOSE.put("'", "'");
        AUTO_CLOSE.put("`", "`");
        AUTO_CLOSE.put("<", ">");
    }

    /** Symbol panel (?123) layout, rows of strings to render in three rows. */
    private static final String[][] SYMBOL_PANEL = {
        {"1","2","3","4","5","6","7","8","9","0"},
        {"@","#","$","_","&","-","+","(",")","/"},
        {"*","\"","'",":",";","!","?","<",">","="}
    };

    /** A small curated emoji list — keeps us off the system emoji font path. */
    private static final String[] EMOJI_SET = {
        "😀","😄","😅","😂","🤣","😊","😍","😘","😎","🤔",
        "🙃","🙂","😇","🥲","🥳","😴","😭","😡","🤯","🤖",
        "👍","👎","👏","🙏","💪","🤝","✌️","👀","💯","🔥",
        "✅","❌","⭐","✨","🎉","🎯","💡","💻","📱","⌨️",
        "❤️","💔","💖","💙","💚","💛","💜","🧡","🤍","🖤"
    };

    // ─── Lifecycle ────────────────────────────────────────────────────────────
    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("codekeys_prefs", MODE_PRIVATE);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        currentLang = prefs.getString("lang", "GENERAL");
    }

    @Override
    public View onCreateInputView() {
        keyboardView = LayoutInflater.from(this).inflate(R.layout.keyboard_main, null);
        initViews();
        buildKeyboardRows();
        applyTheme();
        return keyboardView;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        // New field — drop sticky single-shift, refresh enter label, clear panels.
        if (capsState == CAPS_SINGLE) capsState = CAPS_OFF;
        panelSymbols = false;
        panelEmoji = false;
        if (keyboardView != null) {
            updateEnterButton(info);
            buildKeyboardRows();
        }
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd,
                                  int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                candidatesStart, candidatesEnd);
        refreshSuggestions();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        dismissPreview();
        super.onFinishInputView(finishingInput);
    }

    // ─── View Init ────────────────────────────────────────────────────────────
    private void initViews() {
        rowSuggestions = keyboardView.findViewById(R.id.row_suggestions);
        rowNumbers   = keyboardView.findViewById(R.id.row_numbers);
        rowLetters1  = keyboardView.findViewById(R.id.row_letters1);
        rowLetters2  = keyboardView.findViewById(R.id.row_letters2);
        rowLetters3  = keyboardView.findViewById(R.id.row_letters3);
        rowSymbols   = keyboardView.findViewById(R.id.row_symbols);
        rowSnippets  = keyboardView.findViewById(R.id.row_snippets);
        rowNav       = keyboardView.findViewById(R.id.row_nav);

        btnCaps         = keyboardView.findViewById(R.id.btn_caps);
        btnEnter        = keyboardView.findViewById(R.id.btn_enter);
        btnSettings     = keyboardView.findViewById(R.id.btn_settings);
        btnSymbolsPanel = keyboardView.findViewById(R.id.btn_symbols_panel);
        btnEmoji        = keyboardView.findViewById(R.id.btn_emoji);
        btnSpace        = keyboardView.findViewById(R.id.btn_space);

        setupNavButtons();
        updateEnterButton(getCurrentInputEditorInfo());

        // Single ⚙ button: tap → language + settings popup, anchored bottom-left.
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> {
                haptic(v);
                showSettingsLanguagePopup(v);
            });
        }
        if (btnSymbolsPanel != null) {
            btnSymbolsPanel.setOnClickListener(v -> {
                haptic(v);
                togglePanel(/*symbols*/ !panelSymbols, /*emoji*/ false);
            });
        }
        if (btnEmoji != null) {
            btnEmoji.setOnClickListener(v -> {
                haptic(v);
                togglePanel(/*symbols*/ false, /*emoji*/ !panelEmoji);
            });
        }
    }

    // ─── Build Dynamic Rows ───────────────────────────────────────────────────
    private void buildKeyboardRows() {
        if (panelEmoji) {
            buildEmojiPanel();
            return;
        }
        if (panelSymbols) {
            buildSymbolPanel();
            return;
        }
        buildNumberRow();
        buildQwertyRows();
        buildSymbolRow();
        buildSnippetRow();
        rowSymbols.setVisibility(View.VISIBLE);
        rowSnippets.setVisibility(View.VISIBLE);
        rowNumbers.setVisibility(View.VISIBLE);
        refreshSuggestions();
        refreshCapsButtonStyle();
    }

    private void buildNumberRow() {
        if (rowNumbers == null) return;
        rowNumbers.removeAllViews();
        String[] nums = {"1","2","3","4","5","6","7","8","9","0"};
        for (final String n : nums) addLetterKey(rowNumbers, n, /*isLetter*/ false);
    }

    private void buildQwertyRows() {
        String[] row1 = {"q","w","e","r","t","y","u","i","o","p"};
        String[] row2 = {"a","s","d","f","g","h","j","k","l"};
        String[] row3 = {"z","x","c","v","b","n","m"};

        rowLetters1.removeAllViews();
        rowLetters2.removeAllViews();
        rowLetters3.removeAllViews();

        for (String k : row1) addLetterKey(rowLetters1, k, true);
        for (String k : row2) addLetterKey(rowLetters2, k, true);
        for (String k : row3) addLetterKey(rowLetters3, k, true);
    }

    private void addLetterKey(LinearLayout parent, final String letter, boolean isLetter) {
        final boolean caps = isLetter && capsState != CAPS_OFF;
        final String label = caps ? letter.toUpperCase() : letter;

        Button btn = makeKey(label);
        btn.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                showKeyPreview(v, label);
            } else if (ev.getAction() == MotionEvent.ACTION_UP
                    || ev.getAction() == MotionEvent.ACTION_CANCEL) {
                // Slight delay so the preview is visible on quick taps, M3-feel.
                uiHandler.postDelayed(this::dismissPreview, 90L);
            }
            return false;
        });
        btn.setOnClickListener(v -> {
            haptic(v);
            String ch = (isLetter && capsState != CAPS_OFF) ? letter.toUpperCase() : letter;
            commitChar(ch);
            // Single-shift consumed.
            if (isLetter && capsState == CAPS_SINGLE) {
                capsState = CAPS_OFF;
                buildQwertyRows();
                refreshCapsButtonStyle();
            }
        });
        btn.setOnLongClickListener(v -> {
            haptic(v);
            String ch = isLetter
                ? (caps ? letter.toLowerCase() : letter.toUpperCase())
                : letter;
            commitChar(ch);
            return true;
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dpToPx(48), 1f);
        lp.setMargins(2, 2, 2, 2);
        btn.setLayoutParams(lp);
        parent.addView(btn);
    }

    private void buildSymbolRow() {
        String[] symbols = LANG_SYMBOLS.containsKey(currentLang)
                ? LANG_SYMBOLS.get(currentLang)
                : LANG_SYMBOLS.get("GENERAL");
        rowSymbols.removeAllViews();
        if (symbols == null) return;
        for (final String sym : symbols) {
            Button btn = makeKey(sym);
            btn.setTextSize(12f);
            btn.setOnTouchListener((v, ev) -> {
                if (ev.getAction() == MotionEvent.ACTION_DOWN) showKeyPreview(v, sym);
                else if (ev.getAction() == MotionEvent.ACTION_UP
                        || ev.getAction() == MotionEvent.ACTION_CANCEL)
                    uiHandler.postDelayed(this::dismissPreview, 90L);
                return false;
            });
            btn.setOnClickListener(v -> {
                haptic(v);
                insertSymbolWithAutoClose(sym);
            });
            btn.setOnLongClickListener(v -> {
                haptic(v);
                addToClipboard(sym);
                Toast.makeText(this, "Copied: " + sym, Toast.LENGTH_SHORT).show();
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dpToPx(38), dpToPx(42));
            lp.setMargins(2, 2, 2, 2);
            btn.setLayoutParams(lp);
            rowSymbols.addView(btn);
        }
    }

    private void buildSnippetRow() {
        String[][] snippets = LANG_SNIPPETS.containsKey(currentLang)
                ? LANG_SNIPPETS.get(currentLang)
                : LANG_SNIPPETS.get("GENERAL");
        rowSnippets.removeAllViews();
        if (snippets == null) return;
        int accentColor = getAccentColor();
        for (final String[] snippet : snippets) {
            Button btn = makeKey(snippet[0]);
            btn.setTextSize(11f);
            btn.setTextColor(accentColor);
            btn.setOnClickListener(v -> {
                haptic(v);
                replaceSelectionOrInsert(snippet[1]);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dpToPx(56), dpToPx(38));
            lp.setMargins(2, 2, 2, 2);
            btn.setLayoutParams(lp);
            rowSnippets.addView(btn);
        }
    }

    /** Symbols-only ?123 panel; rebuilds letter rows with symbol grid. */
    private void buildSymbolPanel() {
        rowNumbers.setVisibility(View.GONE);
        rowSymbols.setVisibility(View.GONE);
        rowSnippets.setVisibility(View.GONE);
        rowLetters1.removeAllViews();
        rowLetters2.removeAllViews();
        rowLetters3.removeAllViews();

        LinearLayout[] containers = {rowLetters1, rowLetters2, rowLetters3};
        for (int i = 0; i < SYMBOL_PANEL.length && i < containers.length; i++) {
            LinearLayout container = containers[i];
            for (final String s : SYMBOL_PANEL[i]) {
                Button btn = makeKey(s);
                btn.setOnTouchListener((v, ev) -> {
                    if (ev.getAction() == MotionEvent.ACTION_DOWN) showKeyPreview(v, s);
                    else if (ev.getAction() == MotionEvent.ACTION_UP
                            || ev.getAction() == MotionEvent.ACTION_CANCEL)
                        uiHandler.postDelayed(this::dismissPreview, 90L);
                    return false;
                });
                btn.setOnClickListener(v -> { haptic(v); insertSymbolWithAutoClose(s); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dpToPx(48), 1f);
                lp.setMargins(2, 2, 2, 2);
                btn.setLayoutParams(lp);
                container.addView(btn);
            }
        }
        refreshCapsButtonStyle();
    }

    /** Emoji panel rendered into the snippet/scrollable area. */
    private void buildEmojiPanel() {
        rowNumbers.setVisibility(View.GONE);
        rowSymbols.setVisibility(View.GONE);
        rowSnippets.setVisibility(View.VISIBLE);
        rowLetters1.removeAllViews();
        rowLetters2.removeAllViews();
        rowLetters3.removeAllViews();
        rowSnippets.removeAllViews();

        // Render emoji into the 3 letter rows, distributed evenly.
        LinearLayout[] containers = {rowLetters1, rowLetters2, rowLetters3};
        int perRow = (int) Math.ceil(EMOJI_SET.length / (double) containers.length);
        int idx = 0;
        for (LinearLayout container : containers) {
            for (int j = 0; j < perRow && idx < EMOJI_SET.length; j++, idx++) {
                final String emoji = EMOJI_SET[idx];
                Button btn = makeKey(emoji);
                btn.setTextSize(20f);
                btn.setOnTouchListener((v, ev) -> {
                    if (ev.getAction() == MotionEvent.ACTION_DOWN) showKeyPreview(v, emoji);
                    else if (ev.getAction() == MotionEvent.ACTION_UP
                            || ev.getAction() == MotionEvent.ACTION_CANCEL)
                        uiHandler.postDelayed(this::dismissPreview, 90L);
                    return false;
                });
                btn.setOnClickListener(v -> { haptic(v); replaceSelectionOrInsert(emoji); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dpToPx(48), 1f);
                lp.setMargins(2, 2, 2, 2);
                btn.setLayoutParams(lp);
                container.addView(btn);
            }
        }
        refreshCapsButtonStyle();
    }

    // ─── Nav Row ──────────────────────────────────────────────────────────────
    private void setupNavButtons() {
        if (btnCaps != null) btnCaps.setOnClickListener(v -> { haptic(v); onCapsTapped(); });

        Button btnBack = keyboardView.findViewById(R.id.btn_backspace);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> { haptic(v); deleteCharOrSelection(); });
            btnBack.setOnLongClickListener(v -> { deleteWord(); return true; });
        }

        if (btnSpace != null) btnSpace.setOnClickListener(v -> { haptic(v); commitChar(" "); });

        if (btnEnter != null) btnEnter.setOnClickListener(v -> { haptic(v); performEnterAction(); });

        ImageButton btnLeft  = keyboardView.findViewById(R.id.btn_arrow_left);
        ImageButton btnRight = keyboardView.findViewById(R.id.btn_arrow_right);
        ImageButton btnUp    = keyboardView.findViewById(R.id.btn_arrow_up);
        ImageButton btnDown  = keyboardView.findViewById(R.id.btn_arrow_down);
        if (btnLeft != null)  btnLeft.setOnClickListener(v -> sendArrow(KeyEvent.KEYCODE_DPAD_LEFT));
        if (btnRight != null) btnRight.setOnClickListener(v -> sendArrow(KeyEvent.KEYCODE_DPAD_RIGHT));
        if (btnUp != null)    btnUp.setOnClickListener(v -> sendArrow(KeyEvent.KEYCODE_DPAD_UP));
        if (btnDown != null)  btnDown.setOnClickListener(v -> sendArrow(KeyEvent.KEYCODE_DPAD_DOWN));

        Button btnUndo = keyboardView.findViewById(R.id.btn_undo);
        Button btnRedo = keyboardView.findViewById(R.id.btn_redo);
        if (btnUndo != null) btnUndo.setOnClickListener(v -> sendUndoRedo(true));
        if (btnRedo != null) btnRedo.setOnClickListener(v -> sendUndoRedo(false));
    }

    // ─── Caps state machine (single-shift / caps-lock) ───────────────────────
    private void onCapsTapped() {
        long now = System.currentTimeMillis();
        boolean isDoubleTap = (now - lastCapsTapMs) <= DOUBLE_TAP_MS;
        lastCapsTapMs = now;

        if (isDoubleTap) {
            // Double-tap → caps lock until tapped again.
            capsState = CAPS_LOCKED;
        } else {
            // Cycle through OFF → SINGLE → OFF (LOCKED is exited via single tap).
            switch (capsState) {
                case CAPS_OFF:    capsState = CAPS_SINGLE; break;
                case CAPS_SINGLE: capsState = CAPS_OFF;    break;
                case CAPS_LOCKED: capsState = CAPS_OFF;    break;
            }
        }
        // Important: caps must NOT clobber a current selection. We only rebuild
        // the visible letter labels — the InputConnection is left alone.
        buildQwertyRows();
        refreshCapsButtonStyle();
    }

    private void refreshCapsButtonStyle() {
        if (btnCaps == null) return;
        int accent = getAccentColor();
        int keyBg = getKeyBgColor();
        switch (capsState) {
            case CAPS_OFF:
                btnCaps.setText("⇧");
                btnCaps.setTextColor(getKeyTextColor());
                btnCaps.setBackgroundColor(keyBg);
                break;
            case CAPS_SINGLE:
                btnCaps.setText("⇧");
                btnCaps.setTextColor(accent);
                btnCaps.setBackgroundColor(blend(keyBg, accent, 0.35f));
                break;
            case CAPS_LOCKED:
                btnCaps.setText("⇪");
                btnCaps.setTextColor(0xFF000000);
                btnCaps.setBackgroundColor(accent);
                break;
        }
    }

    // ─── Input helpers ────────────────────────────────────────────────────────
    /**
     * Commits a character. If a selection exists, replaces it (the platform's
     * commitText already does this — but we keep the wrapper for symmetry with
     * other helpers in this file).
     */
    private void commitChar(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(text, 1);
    }

    /**
     * Snippet / emoji insertion. Always replaces selection if present, never
     * collapses it silently — matches the behavior the user requested.
     */
    private void replaceSelectionOrInsert(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        // commitText replaces the current selection range for us.
        ic.commitText(text, 1);
    }

    private void insertSymbolWithAutoClose(String sym) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        boolean autoCloseEnabled = prefs.getBoolean("auto_close", true);
        if (autoCloseEnabled && AUTO_CLOSE.containsKey(sym) && !hasSelection(ic)) {
            String close = AUTO_CLOSE.get(sym);
            ic.beginBatchEdit();
            ic.commitText(sym + close, 1);
            // Move caret between the pair: shift left by close.length()
            CharSequence before = ic.getTextBeforeCursor(1, 0);
            if (!TextUtils.isEmpty(close) && before != null) {
                int newPos = positionOfCursor(ic) - close.length();
                if (newPos >= 0) ic.setSelection(newPos, newPos);
            }
            ic.endBatchEdit();
        } else {
            // commitText handles selection replacement automatically.
            ic.commitText(sym, 1);
        }
    }

    /**
     * Backspace must NOT silently delete a selection without acknowledging it
     * (that's what the user means by "selection doesn't get controlled").
     * Behavior: if selection exists, delete the selection only. Otherwise
     * delete one character before the caret.
     */
    private void deleteCharOrSelection() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (hasSelection(ic)) {
            ic.commitText("", 1); // replaces selection with empty
        } else {
            ic.deleteSurroundingText(1, 0);
        }
    }

    private void deleteWord() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (hasSelection(ic)) { ic.commitText("", 1); return; }
        CharSequence text = ic.getTextBeforeCursor(50, 0);
        if (TextUtils.isEmpty(text)) return;
        int deleteCount = 0;
        int i = text.length() - 1;
        while (i >= 0 && text.charAt(i) == ' ') { i--; deleteCount++; }
        while (i >= 0 && text.charAt(i) != ' ') { i--; deleteCount++; }
        ic.deleteSurroundingText(deleteCount, 0);
    }

    private void sendArrow(int keyCode) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        }
    }

    private void sendUndoRedo(boolean undo) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        int meta = KeyEvent.META_CTRL_ON;
        int code = undo ? KeyEvent.KEYCODE_Z : KeyEvent.KEYCODE_Y;
        ic.sendKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, code, 0, meta));
        ic.sendKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_UP,   code, 0, meta));
    }

    private static boolean hasSelection(InputConnection ic) {
        ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
        return et != null && et.selectionStart != et.selectionEnd;
    }

    private static int positionOfCursor(InputConnection ic) {
        ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
        return et == null ? -1 : et.selectionStart;
    }

    // ─── IME action / Enter button label ──────────────────────────────────────
    private void performEnterAction() {
        EditorInfo ei = getCurrentInputEditorInfo();
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) { commitChar("\n"); return; }

        int actionId = (ei != null) ? (ei.imeOptions & EditorInfo.IME_MASK_ACTION) : EditorInfo.IME_ACTION_UNSPECIFIED;
        boolean isMultiline = (ei != null) && ((ei.inputType & android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0);
        boolean noEnterAction = (ei != null) && ((ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0);

        // For multi-line / disabled-action fields, Enter inserts newline.
        if (isMultiline || noEnterAction
                || actionId == EditorInfo.IME_ACTION_UNSPECIFIED
                || actionId == EditorInfo.IME_ACTION_NONE) {
            ic.commitText("\n", 1);
            return;
        }
        ic.performEditorAction(actionId);
    }

    private void updateEnterButton(EditorInfo ei) {
        if (btnEnter == null) return;
        String label = "↵";
        int action = (ei != null) ? (ei.imeOptions & EditorInfo.IME_MASK_ACTION) : 0;
        boolean noEnterAction = (ei != null) && ((ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0);
        boolean isMultiline = (ei != null) && ((ei.inputType & android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0);
        if (!noEnterAction && !isMultiline) {
            switch (action) {
                case EditorInfo.IME_ACTION_GO:       label = "Go";       break;
                case EditorInfo.IME_ACTION_NEXT:     label = "Next";     break;
                case EditorInfo.IME_ACTION_PREVIOUS: label = "Prev";     break;
                case EditorInfo.IME_ACTION_DONE:     label = "Done";     break;
                case EditorInfo.IME_ACTION_SEARCH:   label = "🔍";        break;
                case EditorInfo.IME_ACTION_SEND:     label = "Send";     break;
                default: /* keep enter glyph */                          break;
            }
        }
        btnEnter.setText(label);
        btnEnter.setTextSize(label.length() > 1 ? 12f : 16f);
    }

    // ─── Suggestions strip ────────────────────────────────────────────────────
    private void refreshSuggestions() {
        if (rowSuggestions == null) return;
        rowSuggestions.removeAllViews();
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            currentSuggestions = Collections.emptyList();
            return;
        }
        CharSequence before = ic.getTextBeforeCursor(64, 0);
        String word = currentWord(before);
        currentSuggestions = computeSuggestions(word);

        int textCol = getKeyTextColor();
        int accent = getAccentColor();
        int bg = blend(getKeyBgColor(), 0xFF000000, 0.18f);

        // Always show at least the clipboard hint or current word fallback.
        if (currentSuggestions.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("·");
            empty.setTextSize(14f);
            empty.setTextColor(dim(textCol));
            empty.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            lp.setMargins(dpToPx(8), 0, 0, 0);
            empty.setLayoutParams(lp);
            rowSuggestions.addView(empty);
            return;
        }

        for (final String s : currentSuggestions) {
            Button btn = new Button(this);
            btn.setText(s);
            btn.setAllCaps(false);
            btn.setTextSize(13f);
            btn.setTextColor(s.equals(word) ? accent : textCol);
            btn.setBackgroundColor(bg);
            btn.setPadding(dpToPx(10), 0, dpToPx(10), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dpToPx(34));
            lp.setMargins(dpToPx(3), dpToPx(3), dpToPx(3), dpToPx(3));
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> { haptic(v); applySuggestion(s); });
            rowSuggestions.addView(btn);
        }
    }

    /**
     * Replaces the currently-being-typed word with {@code suggestion}. Honors
     * any active selection (replaces it instead of the prefix word).
     */
    private void applySuggestion(String suggestion) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (hasSelection(ic)) { ic.commitText(suggestion + " ", 1); return; }
        CharSequence before = ic.getTextBeforeCursor(64, 0);
        String word = currentWord(before);
        if (!word.isEmpty()) ic.deleteSurroundingText(word.length(), 0);
        ic.commitText(suggestion + " ", 1);
    }

    private static String currentWord(CharSequence before) {
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

    /**
     * Builds an ordered suggestion list for the given prefix. Sources, in order:
     *   1. Snippet triggers for the current language whose label starts with the
     *      prefix (autocomplete).
     *   2. Tiny built-in english correction map for common typos.
     *   3. The literal current word (so the user can lock it in to bypass autocorrect).
     */
    private List<String> computeSuggestions(String prefix) {
        if (prefix == null || prefix.isEmpty()) return Collections.emptyList();
        String lower = prefix.toLowerCase();
        ArrayList<String> out = new ArrayList<>();

        String[][] snippets = LANG_SNIPPETS.containsKey(currentLang)
                ? LANG_SNIPPETS.get(currentLang)
                : LANG_SNIPPETS.get("GENERAL");
        if (snippets != null) {
            for (String[] s : snippets) {
                if (s[0].startsWith(lower) && !s[0].equals(lower) && !out.contains(s[0])) {
                    out.add(s[0]);
                }
            }
        }
        String fix = COMMON_TYPOS.get(lower);
        if (fix != null && !out.contains(fix)) out.add(fix);

        // Always include the literal word last as a "lock-in" candidate.
        if (!out.contains(prefix)) out.add(prefix);

        // Cap at 6 to keep the strip readable.
        if (out.size() > 6) out.subList(6, out.size()).clear();
        return out;
    }

    /** Tiny, intentionally-small autocorrect map — the user can extend it later. */
    private static final Map<String, String> COMMON_TYPOS = new LinkedHashMap<>();
    static {
        COMMON_TYPOS.put("teh", "the");
        COMMON_TYPOS.put("recieve", "receive");
        COMMON_TYPOS.put("seperate", "separate");
        COMMON_TYPOS.put("definately", "definitely");
        COMMON_TYPOS.put("untill", "until");
        COMMON_TYPOS.put("acn", "can");
        COMMON_TYPOS.put("adn", "and");
        COMMON_TYPOS.put("nad", "and");
        COMMON_TYPOS.put("retrun", "return");
        COMMON_TYPOS.put("fucntion", "function");
        COMMON_TYPOS.put("flase", "false");
        COMMON_TYPOS.put("ture", "true");
    }

    // ─── Settings / Language popup ────────────────────────────────────────────
    /**
     * Builds and shows a M3-styled popup anchored above-left of the ⚙ button.
     * Lists the user's available languages plus a "Settings…" entry that opens
     * {@link SettingsActivity}.
     */
    private void showSettingsLanguagePopup(View anchor) {
        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(blend(getKeyBgColor(), 0xFFFFFFFF, 0.06f));
        bg.setCornerRadius(dpToPx(14));
        bg.setStroke(dpToPx(1), blend(getAccentColor(), 0xFF000000, 0.5f));
        content.setBackground(bg);
        content.setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6));

        // Header
        TextView header = new TextView(this);
        header.setText("Language");
        header.setTextSize(11f);
        header.setTextColor(dim(getKeyTextColor()));
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setLetterSpacing(0.1f);
        header.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(4));
        content.addView(header);

        // Build the popup window first so each row can dismiss it cleanly.
        final PopupWindow pw = new PopupWindow(content,
                dpToPx(220), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setOutsideTouchable(true);
        pw.setFocusable(true);

        for (final String lang : getAllLanguages()) {
            content.addView(makePopupRow(
                    (lang.equals(currentLang) ? "● " : "  ") + lang,
                    lang.equals(currentLang) ? getAccentColor() : getKeyTextColor(),
                    pw,
                    () -> {
                        currentLang = lang;
                        prefs.edit().putString("lang", lang).apply();
                        buildKeyboardRows();
                    }));
        }

        // Divider
        View div = new View(this);
        div.setBackgroundColor(blend(getKeyTextColor(), 0xFF000000, 0.7f));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dpToPx(1)));
        divLp.setMargins(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
        div.setLayoutParams(divLp);
        content.addView(div);

        content.addView(makePopupRow("⚙  Settings…", getAccentColor(), pw, () -> {
            Intent it = new Intent(this, SettingsActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        }));

        // Anchor at bottom-left: the popup grows upward from the ⚙ button.
        try {
            pw.showAsDropDown(anchor, 0, -dpToPx(8) - measure(content));
        } catch (Exception ignored) {
            // Anchor may be detached; fail silently.
        }
    }

    private View makePopupRow(String label, int color, PopupWindow owner, Runnable onClick) {
        TextView row = new TextView(this);
        row.setText(label);
        row.setTextSize(14f);
        row.setTextColor(color);
        row.setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10));
        row.setClickable(true);
        row.setBackgroundColor(0x00000000);
        row.setOnClickListener(v -> {
            try { owner.dismiss(); } catch (Exception ignored) {}
            onClick.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row.setLayoutParams(lp);
        return row;
    }

    private int measure(View v) {
        v.measure(View.MeasureSpec.makeMeasureSpec(dpToPx(220), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.UNSPECIFIED);
        return v.getMeasuredHeight();
    }

    /**
     * Returns the union of built-in languages and any custom presets the user
     * has defined in settings (stored as a "|"-separated list in SharedPreferences).
     */
    private List<String> getAllLanguages() {
        ArrayList<String> all = new ArrayList<>(Arrays.asList(BUILTIN_LANGS));
        String custom = prefs.getString("custom_langs", "");
        if (!TextUtils.isEmpty(custom)) {
            for (String c : custom.split("\\|")) {
                String name = c.trim();
                if (!name.isEmpty() && !all.contains(name)) all.add(name);
            }
        }
        return all;
    }

    // ─── Panels ───────────────────────────────────────────────────────────────
    private void togglePanel(boolean wantSymbols, boolean wantEmoji) {
        panelSymbols = wantSymbols;
        panelEmoji = wantEmoji;
        // Reflect button "active" state via background.
        if (btnSymbolsPanel != null)
            btnSymbolsPanel.setBackgroundColor(wantSymbols ? getAccentColor() : getKeyBgColor());
        if (btnEmoji != null)
            btnEmoji.setBackgroundColor(wantEmoji ? getAccentColor() : getKeyBgColor());
        buildKeyboardRows();
    }

    // ─── Key Preview Popup (M3-expressive) ────────────────────────────────────
    private void showKeyPreview(View anchor, String label) {
        dismissPreview();
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(22f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(getKeyTextColor());
        tv.setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(blend(getKeyBgColor(), getAccentColor(), 0.25f));
        bg.setCornerRadius(dpToPx(16));
        bg.setStroke(dpToPx(1), blend(getAccentColor(), 0xFFFFFFFF, 0.2f));
        tv.setBackground(bg);
        tv.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));

        int width  = Math.max(anchor.getWidth() + dpToPx(12), dpToPx(48));
        int height = anchor.getHeight() + dpToPx(28);

        PopupWindow pw = new PopupWindow(tv, width, height, false);
        pw.setOutsideTouchable(false);
        pw.setTouchable(false);
        pw.setFocusable(false);
        // Position above the key — like Gboard's tap preview.
        int yOffset = -anchor.getHeight() - height + dpToPx(4);
        try {
            pw.showAsDropDown(anchor, -dpToPx(6), yOffset);
            activePreview = pw;
        } catch (Exception ignored) {
            // showAsDropDown can throw if the anchor isn't attached; fail silent.
        }
    }

    private void dismissPreview() {
        if (activePreview != null) {
            try { activePreview.dismiss(); } catch (Exception ignored) {}
            activePreview = null;
        }
    }

    // ─── Clipboard ────────────────────────────────────────────────────────────
    private void addToClipboard(String text) {
        if (!clipboard.contains(text)) {
            clipboard.add(0, text);
            if (clipboard.size() > 10) clipboard.remove(clipboard.size() - 1);
        }
    }

    // ─── Theme ────────────────────────────────────────────────────────────────
    private void applyTheme() {
        boolean amoled = prefs.getBoolean("amoled", false);
        boolean dark   = prefs.getBoolean("dark", true);
        int bgColor  = prefs.getInt("bg_color",  dark ? 0xFF1A1A2E : 0xFFF0F0F0);
        if (amoled) bgColor = 0xFF000000;
        keyboardView.setBackgroundColor(bgColor);
    }

    private int getKeyBgColor() {
        boolean dark = prefs.getBoolean("dark", true);
        return prefs.getInt("key_color", dark ? 0xFF252545 : 0xFFFFFFFF);
    }

    private int getKeyTextColor() {
        boolean dark = prefs.getBoolean("dark", true);
        return prefs.getInt("text_color", dark ? 0xFFE8E8FF : 0xFF222222);
    }

    private int getAccentColor() {
        return prefs.getInt("accent_color", 0xFF00E5FF);
    }

    // ─── Key Factory ─────────────────────────────────────────────────────────
    private Button makeKey(String label) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextSize(14f);
        btn.setTextColor(getKeyTextColor());
        // Rounded M3-style background for each key.
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getKeyBgColor());
        bg.setCornerRadius(dpToPx(10));
        btn.setBackground(bg);
        btn.setPadding(2, 2, 2, 2);
        btn.setAllCaps(false);
        return btn;
    }

    // ─── Haptic ───────────────────────────────────────────────────────────────
    private void haptic(View v) {
        if (!prefs.getBoolean("haptic", true)) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (vibrator != null)
                vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    // ─── Color helpers / utils ────────────────────────────────────────────────
    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private int dim(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >>  8) & 0xFF;
        int b =  color        & 0xFF;
        return 0xFF000000 | (((r + 100) / 2) << 16) | (((g + 100) / 2) << 8) | ((b + 100) / 2);
    }

    private int blend(int base, int over, float t) {
        int br = (base >> 16) & 0xFF, bgC = (base >> 8) & 0xFF, bb = base & 0xFF;
        int or = (over >> 16) & 0xFF, og = (over >> 8) & 0xFF, ob = over & 0xFF;
        return 0xFF000000
                | ((int)(br + (or - br) * t) << 16)
                | ((int)(bgC + (og - bgC) * t) << 8)
                |  (int)(bb + (ob - bb) * t);
    }
}
