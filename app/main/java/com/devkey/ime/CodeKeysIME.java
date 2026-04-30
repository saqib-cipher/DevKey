package com.codekeys.ime;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
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
import android.widget.HorizontalScrollView;
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
    /** Last backspace tap timestamp — used to detect double-tap-clear. */
    private long lastBackspaceTapMs = 0L;

    private boolean panelSymbols = false;   // ?123 panel showing instead of QWERTY
    private boolean panelEmoji   = false;   // emoji panel
    private boolean panelClipboard = false; // clipboard history panel
    private String currentLang = "GENERAL";

    /**
     * Simple counters that drive undo/redo button enable + opacity state. We
     * cannot inspect the host app's undo stack — these reflect operations we
     * have ourselves committed (text inserts, deletes, snippet inserts, etc.)
     * vs. how many of those have been "undone" via the undo button. They get
     * reset whenever the input field changes (onStartInputView).
     */
    private int undoableOps = 0;
    private int redoableOps = 0;
    private SharedPreferences prefs;
    private Vibrator vibrator;
    private AudioManager audio;

    // Last suggestion list, kept so we can rebuild the strip without re-scanning.
    private List<String> currentSuggestions = new ArrayList<>();

    // Active key-preview popup (tracked to dismiss on rapid retap).
    private PopupWindow activePreview;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    /**
     * Sticky modifier mask combining KeyEvent.META_CTRL_ON / META_ALT_ON /
     * META_SHIFT_ON / META_META_ON. Cleared after a non-modifier key press
     * (matching the standard "sticky keys" behaviour).
     */
    private int activeMetaState = 0;

    // Views
    private View keyboardView;
    private LinearLayout rowSuggestions;
    private LinearLayout rowPcKeys;
    private HorizontalScrollView pcKeysScroll;
    private LinearLayout rowNumbers, rowLetters1, rowLetters2, rowLetters3;
    private LinearLayout rowSymbols, rowSnippets, rowNav;
    private Button btnCaps, btnEnter, btnSettings, btnSymbolsPanel, btnEmoji, btnSpace;
    private Button btnUndo, btnRedo;
    private ImageButton btnArrowLeft, btnArrowRight, btnArrowUp, btnArrowDown;
    /** Modifier buttons inside the PC keys row, kept so we can refresh state. */
    private final HashMap<Integer, Button> modifierButtons = new HashMap<>();

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

    /**
     * PC keyboard row contents.
     *
     * <p>Each entry is one of:
     *   <ul>
     *     <li>{@code "MOD:CTRL"}, {@code "MOD:ALT"}, {@code "MOD:SHIFT"},
     *         {@code "MOD:META"} — sticky modifier; toggles the corresponding
     *         {@link KeyEvent#META_CTRL_ON} bit in {@link #activeMetaState}.</li>
     *     <li>{@code "KEY:<KEYCODE>"} — fires that hardware keycode through
     *         {@link InputConnection#sendKeyEvent} with the active meta mask.</li>
     *   </ul>
     */
    private static final String[][] PC_KEYS = {
        {"Esc",    "KEY:111"},  // KEYCODE_ESCAPE
        {"Tab",    "KEY:61"},   // KEYCODE_TAB
        {"Ctrl",   "MOD:CTRL"},
        {"Alt",    "MOD:ALT"},
        {"Shift",  "MOD:SHIFT"},
        {"Win",    "MOD:META"},
        {"F1",     "KEY:131"},
        {"F2",     "KEY:132"},
        {"F3",     "KEY:133"},
        {"F4",     "KEY:134"},
        {"F5",     "KEY:135"},
        {"F6",     "KEY:136"},
        {"F7",     "KEY:137"},
        {"F8",     "KEY:138"},
        {"F9",     "KEY:139"},
        {"F10",    "KEY:140"},
        {"F11",    "KEY:141"},
        {"F12",    "KEY:142"},
        {"Home",   "KEY:122"},
        {"End",    "KEY:123"},
        {"PgUp",   "KEY:92"},   // KEYCODE_PAGE_UP
        {"PgDn",   "KEY:93"},   // KEYCODE_PAGE_DOWN
        {"Ins",    "KEY:124"},  // KEYCODE_INSERT
        {"Del",    "KEY:112"}   // KEYCODE_FORWARD_DEL
    };

    /** Default emoji category shown when the panel opens. */
    private String emojiCategory = "smileys";
    /** Active emoji search query (set via on-keyboard letter taps). */
    private final StringBuilder emojiSearchQuery = new StringBuilder();
    /** Max number of recently-used emoji we remember. */
    private static final int MAX_RECENT_EMOJI = 24;

    // ─── Lifecycle ────────────────────────────────────────────────────────────
    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("codekeys_prefs", MODE_PRIVATE);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        audio    = (AudioManager) getSystemService(AUDIO_SERVICE);
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
        panelClipboard = false;
        emojiSearchQuery.setLength(0);
        // A new input field invalidates our undo counters — host's edit history
        // is per-field too.
        undoableOps = 0;
        redoableOps = 0;
        if (keyboardView != null) {
            // Re-read height + PC-keys settings: the user may have edited them.
            applyKeyboardHeight();
            buildPcKeysRow();
            applyTheme();
            updateEnterButton(info);
            buildKeyboardRows();
            refreshHistoryButtonsState();
            refreshArrowButtonsState();
        }
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd,
                                  int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                candidatesStart, candidatesEnd);
        refreshSuggestions();
        refreshArrowButtonsState();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        dismissPreview();
        super.onFinishInputView(finishingInput);
    }

    // ─── View Init ────────────────────────────────────────────────────────────
    private void initViews() {
        rowSuggestions = keyboardView.findViewById(R.id.row_suggestions);
        rowPcKeys      = keyboardView.findViewById(R.id.row_pc_keys);
        pcKeysScroll   = keyboardView.findViewById(R.id.pc_keys_scroll);
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
        applyKeyboardHeight();
        buildPcKeysRow();
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
        if (panelClipboard) {
            buildClipboardPanel();
            return;
        }
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
            // While the emoji panel is open, letter / number taps drive the
            // in-keyboard emoji search instead of typing into the editor.
            if (panelEmoji) {
                emojiSearchQuery.append(letter.toLowerCase());
                buildEmojiPanel();
                return;
            }
            String ch = (isLetter && capsState != CAPS_OFF) ? letter.toUpperCase() : letter;
            commitChar(ch);
            noteOperation();
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
        rowSnippets.removeAllViews();
        int accentColor = getAccentColor();
        for (final String[] snippet : effectiveSnippets()) {
            Button btn = makeKey(snippet[0]);
            btn.setTextSize(11f);
            btn.setTextColor(accentColor);
            btn.setOnClickListener(v -> {
                haptic(v);
                replaceSelectionOrInsert(snippet[1]);
                // Custom snippet inserts count as undo-able operations.
                noteOperation();
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

    /**
     * Emoji panel — categorized + searchable.
     *
     * <p>Layout when open:
     *   <ul>
     *     <li>Suggestion strip → live emoji search box (showing the current
     *         {@link #emojiSearchQuery}).</li>
     *     <li>Symbol row → category tabs (Recent, Smileys, Animals, …).</li>
     *     <li>Three letter rows + snippet row → emoji grid filtered by the
     *         active category or current search query.</li>
     *   </ul>
     *
     * <p>While the emoji panel is active, letter / number key taps are routed
     * into {@link #emojiSearchQuery} (filtering the grid live) instead of
     * being committed to the editor — see {@link #addLetterKey} and
     * {@link #deleteCharOrSelection}.
     */
    private void buildEmojiPanel() {
        rowNumbers.setVisibility(View.GONE);
        rowSymbols.setVisibility(View.VISIBLE);
        rowSnippets.setVisibility(View.VISIBLE);
        rowLetters1.removeAllViews();
        rowLetters2.removeAllViews();
        rowLetters3.removeAllViews();
        rowSnippets.removeAllViews();

        // ── Category tabs (rendered into the symbol row) ──────────────────
        rowSymbols.removeAllViews();
        int accent = getAccentColor();
        int textCol = getKeyTextColor();
        int keyBg = getKeyBgColor();
        for (int i = 0; i < EmojiData.CATEGORY_KEYS.length; i++) {
            final String key = EmojiData.CATEGORY_KEYS[i];
            String label = EmojiData.CATEGORY_NAMES[i];
            boolean active = key.equals(emojiCategory) && emojiSearchQuery.length() == 0;
            Button tab = makeKey(label);
            tab.setTextSize(label.length() > 2 ? 11f : 16f);
            tab.setTextColor(active ? accent : textCol);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(active ? blend(keyBg, accent, 0.35f) : keyBg);
            bg.setCornerRadius(dpToPx(10));
            tab.setBackground(bg);
            tab.setOnClickListener(v -> {
                haptic(v);
                emojiCategory = key;
                emojiSearchQuery.setLength(0);
                buildEmojiPanel();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dpToPx(48), dpToPx(40));
            lp.setMargins(2, 2, 2, 2);
            tab.setLayoutParams(lp);
            rowSymbols.addView(tab);
        }

        // ── Emoji grid ─────────────────────────────────────────────────────
        List<String> emojis;
        if (emojiSearchQuery.length() > 0) {
            emojis = EmojiData.search(emojiSearchQuery.toString());
        } else if ("recent".equals(emojiCategory)) {
            emojis = loadRecentEmojis();
            // Fallback: when no recents yet, show smileys so the panel isn't blank.
            if (emojis.isEmpty()) emojis = EmojiData.emojisFor("smileys");
        } else {
            emojis = EmojiData.emojisFor(emojiCategory);
        }

        // 4 rows total: rowSnippets + rowLetters1/2/3 → distribute emojis.
        LinearLayout[] containers = {rowSnippets, rowLetters1, rowLetters2, rowLetters3};
        int perRow = Math.max(1, (int) Math.ceil(emojis.size() / (double) containers.length));
        int idx = 0;
        for (LinearLayout container : containers) {
            container.removeAllViews();
            for (int j = 0; j < perRow && idx < emojis.size(); j++, idx++) {
                final String emoji = emojis.get(idx);
                Button btn = makeKey(emoji);
                btn.setTextSize(20f);
                btn.setOnTouchListener((v, ev) -> {
                    if (ev.getAction() == MotionEvent.ACTION_DOWN) showKeyPreview(v, emoji);
                    else if (ev.getAction() == MotionEvent.ACTION_UP
                            || ev.getAction() == MotionEvent.ACTION_CANCEL)
                        uiHandler.postDelayed(this::dismissPreview, 90L);
                    return false;
                });
                btn.setOnClickListener(v -> {
                    haptic(v);
                    replaceSelectionOrInsert(emoji);
                    noteOperation();
                    rememberRecentEmoji(emoji);
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dpToPx(48), 1f);
                lp.setMargins(2, 2, 2, 2);
                btn.setLayoutParams(lp);
                container.addView(btn);
            }
        }
        // Empty hint when search yields nothing.
        if (emojis.isEmpty() && emojiSearchQuery.length() > 0) {
            TextView hint = new TextView(this);
            hint.setText("No emoji match \"" + emojiSearchQuery + "\"");
            hint.setTextSize(12f);
            hint.setTextColor(dim(textCol));
            hint.setGravity(Gravity.CENTER);
            rowSnippets.addView(hint);
        }
        refreshCapsButtonStyle();
        // Repaint suggestion strip as the search box.
        refreshEmojiSearchStrip();
    }

    /**
     * Repaints {@link #rowSuggestions} as a live emoji-search input box. Reads
     * the current {@link #emojiSearchQuery} and provides a clear button.
     */
    private void refreshEmojiSearchStrip() {
        if (rowSuggestions == null) return;
        rowSuggestions.removeAllViews();
        int textCol = getKeyTextColor();
        int accent = getAccentColor();

        TextView prompt = new TextView(this);
        prompt.setText("🔍");
        prompt.setTextSize(14f);
        prompt.setPadding(dpToPx(10), 0, dpToPx(6), 0);
        prompt.setGravity(Gravity.CENTER_VERTICAL);
        rowSuggestions.addView(prompt);

        TextView query = new TextView(this);
        if (emojiSearchQuery.length() == 0) {
            query.setText("Type to search emoji…");
            query.setTextColor(dim(textCol));
        } else {
            query.setText(emojiSearchQuery.toString());
            query.setTextColor(accent);
        }
        query.setTextSize(13f);
        query.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        query.setLayoutParams(qlp);
        rowSuggestions.addView(query);

        if (emojiSearchQuery.length() > 0) {
            Button clear = new Button(this);
            clear.setText("✕");
            clear.setAllCaps(false);
            clear.setTextSize(13f);
            clear.setTextColor(textCol);
            clear.setBackgroundColor(0x00000000);
            clear.setOnClickListener(v -> {
                haptic(v);
                emojiSearchQuery.setLength(0);
                buildEmojiPanel();
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    dpToPx(40), ViewGroup.LayoutParams.MATCH_PARENT);
            clear.setLayoutParams(clp);
            rowSuggestions.addView(clear);
        }
    }

    /**
     * Saves an emoji to the "recents" pref list, MRU-ordered with a hard cap.
     * Stored under {@code emoji_recents} as a {@code "\u0001"}-delimited list.
     */
    private void rememberRecentEmoji(String emoji) {
        if (TextUtils.isEmpty(emoji)) return;
        ArrayList<String> existing = new ArrayList<>(loadRecentEmojis());
        existing.remove(emoji);
        existing.add(0, emoji);
        while (existing.size() > MAX_RECENT_EMOJI) existing.remove(existing.size() - 1);
        prefs.edit().putString("emoji_recents", TextUtils.join("\u0001", existing)).apply();
    }

    private List<String> loadRecentEmojis() {
        String raw = prefs.getString("emoji_recents", "");
        if (TextUtils.isEmpty(raw)) return Collections.emptyList();
        ArrayList<String> out = new ArrayList<>();
        for (String s : raw.split("\u0001")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    // ─── Nav Row ──────────────────────────────────────────────────────────────
    private void setupNavButtons() {
        if (btnCaps != null) btnCaps.setOnClickListener(v -> { haptic(v); onCapsTapped(); });

        Button btnBack = keyboardView.findViewById(R.id.btn_backspace);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> { haptic(v); onBackspaceTapped(); });
            btnBack.setOnLongClickListener(v -> { deleteWord(); return true; });
        }

        if (btnSpace != null) btnSpace.setOnClickListener(v -> { haptic(v); commitChar(" "); });

        if (btnEnter != null) btnEnter.setOnClickListener(v -> { haptic(v); performEnterAction(); });

        btnArrowLeft  = keyboardView.findViewById(R.id.btn_arrow_left);
        btnArrowRight = keyboardView.findViewById(R.id.btn_arrow_right);
        btnArrowUp    = keyboardView.findViewById(R.id.btn_arrow_up);
        btnArrowDown  = keyboardView.findViewById(R.id.btn_arrow_down);
        if (btnArrowLeft != null)  btnArrowLeft.setOnClickListener(v -> sendArrow(KeyEvent.KEYCODE_DPAD_LEFT));
        if (btnArrowRight != null) btnArrowRight.setOnClickListener(v -> sendArrow(KeyEvent.KEYCODE_DPAD_RIGHT));
        if (btnArrowUp != null)    btnArrowUp.setOnClickListener(v -> sendArrow(KeyEvent.KEYCODE_DPAD_UP));
        if (btnArrowDown != null)  btnArrowDown.setOnClickListener(v -> sendArrow(KeyEvent.KEYCODE_DPAD_DOWN));

        btnUndo = keyboardView.findViewById(R.id.btn_undo);
        btnRedo = keyboardView.findViewById(R.id.btn_redo);
        if (btnUndo != null) btnUndo.setOnClickListener(v -> { haptic(v); doUndo(); });
        if (btnRedo != null) btnRedo.setOnClickListener(v -> { haptic(v); doRedo(); });
        refreshHistoryButtonsState();
        refreshArrowButtonsState();
    }

    // ─── Undo / Redo state (counters that drive button opacity) ──────────────
    /**
     * Marks that the keyboard has just performed an undo-able operation
     * (insert, snippet, paste …). Increments {@link #undoableOps} and clears
     * any pending redo, mirroring how host editors invalidate the redo stack
     * when the user makes a fresh edit.
     */
    private void noteOperation() {
        undoableOps++;
        redoableOps = 0;
        refreshHistoryButtonsState();
    }

    /**
     * Marks that we just deleted text. Treated identically to
     * {@link #noteOperation} for the purpose of enabling the Undo button —
     * deleting is reversible by Ctrl+Z in most apps.
     */
    private void noteDeletion() {
        noteOperation();
    }

    /** Drives the actual undo: sends Ctrl+Z and shifts the redo counter. */
    private void doUndo() {
        if (undoableOps <= 0) return;
        sendUndoRedo(true);
        undoableOps--;
        redoableOps++;
        refreshHistoryButtonsState();
    }

    /** Drives the actual redo: sends Ctrl+Y and shifts the undo counter. */
    private void doRedo() {
        if (redoableOps <= 0) return;
        sendUndoRedo(false);
        redoableOps--;
        undoableOps++;
        refreshHistoryButtonsState();
    }

    /** Refreshes opacity / enabled state for the Undo/Redo buttons. */
    private void refreshHistoryButtonsState() {
        if (btnUndo != null) {
            boolean active = undoableOps > 0;
            btnUndo.setEnabled(active);
            btnUndo.setAlpha(active ? 1f : 0.35f);
        }
        if (btnRedo != null) {
            boolean active = redoableOps > 0;
            btnRedo.setEnabled(active);
            btnRedo.setAlpha(active ? 1f : 0.35f);
        }
    }

    /**
     * Refreshes opacity / enabled state for the four arrow buttons so the user
     * can see at a glance which directions still have travel left.
     *
     * <p>Caret position vs. total length is read from the host's extracted
     * text; left/up dim at start, right/down dim at end. Up/Down can't be
     * fully introspected (no way to know if there's a previous/next line),
     * so we dim both whenever the field has no content at all.
     */
    private void refreshArrowButtonsState() {
        if (btnArrowLeft == null) return;
        InputConnection ic = getCurrentInputConnection();
        int pos = -1;
        int total = -1;
        if (ic != null) {
            ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
            if (et != null && et.text != null) {
                pos = et.selectionStart;
                total = et.text.length();
            }
        }
        boolean canLeft = pos > 0 || pos < 0;
        boolean canRight = (pos < 0) || (total < 0) || pos < total;
        boolean hasText = total > 0;
        applyEnabled(btnArrowLeft, canLeft);
        applyEnabled(btnArrowRight, canRight);
        applyEnabled(btnArrowUp, hasText);
        applyEnabled(btnArrowDown, hasText);
    }

    private static void applyEnabled(View v, boolean enabled) {
        if (v == null) return;
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : 0.35f);
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
        if (ic == null) return;
        // Modifier-aware dispatch: if the user has Ctrl/Alt/Win sticky-engaged,
        // emit a hardware key event so combos like Ctrl+C work in editors.
        if (activeMetaState != 0
                && (activeMetaState & ~KeyEvent.META_SHIFT_ON) != 0
                && text != null && text.length() == 1) {
            int kc = charToKeyCode(text);
            if (kc != 0) {
                sendKeyWithMeta(kc);
                clearTransientModifiers();
                return;
            }
        }
        ic.commitText(text, 1);
        if (activeMetaState != 0) clearTransientModifiers();
    }

    /**
     * Snippet / emoji / suggestion insertion. Always replaces selection if
     * present, never collapses it silently — matches the behavior the user
     * requested. Also tracks the operation in the undo counter so the Undo
     * button enables.
     */
    private void replaceSelectionOrInsert(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        // commitText replaces the current selection range for us.
        ic.commitText(text, 1);
        noteOperation();
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
        noteOperation();
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

    /**
     * Backspace tap dispatcher.
     *
     * <ol>
     *   <li>If the emoji panel's search box has any characters, deletes from
     *       there instead of touching the editor.</li>
     *   <li>If this is a double-tap (within {@link #DOUBLE_TAP_MS}), clears
     *       the entire input field — the behavior the user asked for under
     *       "double tap on clear btn remove all text".</li>
     *   <li>Otherwise behaves as a normal single-character backspace.</li>
     * </ol>
     */
    private void onBackspaceTapped() {
        // Emoji panel: delete one char from the in-keyboard search query.
        if (panelEmoji && emojiSearchQuery.length() > 0) {
            emojiSearchQuery.deleteCharAt(emojiSearchQuery.length() - 1);
            buildEmojiPanel();
            return;
        }
        long now = System.currentTimeMillis();
        boolean isDoubleTap = (now - lastBackspaceTapMs) <= DOUBLE_TAP_MS;
        lastBackspaceTapMs = now;
        if (isDoubleTap) {
            clearAllText();
            return;
        }
        deleteCharOrSelection();
        noteDeletion();
    }

    /**
     * Removes every character from the current text field (used by the
     * double-tap-backspace shortcut). Selects the whole field then commits an
     * empty string — which works across most apps because {@code commitText}
     * replaces the active selection.
     */
    private void clearAllText() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
        if (et == null || et.text == null) {
            // Fallback: aggressive surround delete (covers most app types).
            ic.deleteSurroundingText(Integer.MAX_VALUE / 2, Integer.MAX_VALUE / 2);
            noteDeletion();
            return;
        }
        int len = et.text.length();
        if (len == 0) return;
        ic.beginBatchEdit();
        ic.setSelection(0, len);
        ic.commitText("", 1);
        ic.endBatchEdit();
        noteDeletion();
        Toast.makeText(this, "Cleared", Toast.LENGTH_SHORT).show();
    }

    private void deleteWord() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (hasSelection(ic)) { ic.commitText("", 1); noteDeletion(); return; }
        CharSequence text = ic.getTextBeforeCursor(50, 0);
        if (TextUtils.isEmpty(text)) return;
        int deleteCount = 0;
        int i = text.length() - 1;
        while (i >= 0 && text.charAt(i) == ' ') { i--; deleteCount++; }
        while (i >= 0 && text.charAt(i) != ' ') { i--; deleteCount++; }
        ic.deleteSurroundingText(deleteCount, 0);
        noteDeletion();
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
        if (hasSelection(ic)) {
            ic.commitText(suggestion + " ", 1);
            noteOperation();
            return;
        }
        CharSequence before = ic.getTextBeforeCursor(64, 0);
        String word = currentWord(before);
        if (!word.isEmpty()) ic.deleteSurroundingText(word.length(), 0);
        ic.commitText(suggestion + " ", 1);
        noteOperation();
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
     *   1. Snippet triggers (built-in + custom) for the current language whose
     *      label starts with the prefix (autocomplete).
     *   2. Tiny built-in english correction map for common typos.
     *   3. Common-English-word dictionary (see {@link CommonWords}) — provides
     *      the broad day-to-day vocabulary the user asked for.
     *   4. The literal current word (so the user can lock it in to bypass autocorrect).
     *
     * <p>The list is capped at 8 candidates to keep the strip readable while
     * still showing more than the previous limit of 6.
     */
    private List<String> computeSuggestions(String prefix) {
        if (prefix == null || prefix.isEmpty()) return Collections.emptyList();
        String lower = prefix.toLowerCase();
        ArrayList<String> out = new ArrayList<>();

        // 1. Snippet triggers (built-in + user-defined custom snippets).
        for (String[] s : effectiveSnippets()) {
            if (s[0].startsWith(lower) && !s[0].equals(lower) && !out.contains(s[0])) {
                out.add(s[0]);
            }
        }

        // 2. Common-typo autocorrect (single highest-confidence fix).
        String fix = COMMON_TYPOS.get(lower);
        if (fix != null && !out.contains(fix)) out.add(fix);

        // 3. Dictionary prefix-match (sorted by descending frequency in CommonWords).
        for (String w : CommonWords.all()) {
            if (out.size() >= 8) break;
            if (w.length() <= lower.length()) continue; // skip same-length / shorter
            if (w.startsWith(lower) && !out.contains(w)) {
                // Preserve casing of the typed prefix when the user is mid-word
                // and started with an uppercase letter.
                if (Character.isUpperCase(prefix.charAt(0))) {
                    out.add(Character.toUpperCase(w.charAt(0)) + w.substring(1));
                } else {
                    out.add(w);
                }
            }
        }

        // 4. Always include the literal word last as a "lock-in" candidate.
        if (!out.contains(prefix)) out.add(prefix);

        // Cap at 8 to keep the strip readable.
        if (out.size() > 8) out.subList(8, out.size()).clear();
        return out;
    }

    /**
     * Returns the union of built-in language snippets and any custom snippets
     * the user defined for {@link #currentLang} via Settings. Custom snippets
     * appear first so they override built-ins on tie.
     */
    private List<String[]> effectiveSnippets() {
        ArrayList<String[]> out = new ArrayList<>();
        // Custom snippets for the active language (if any).
        for (String[] s : loadCustomSnippets(currentLang)) out.add(s);

        // Built-in snippets for the active language.
        String[][] builtin = LANG_SNIPPETS.containsKey(currentLang)
                ? LANG_SNIPPETS.get(currentLang)
                : LANG_SNIPPETS.get("GENERAL");
        if (builtin != null) {
            for (String[] s : builtin) {
                boolean dup = false;
                for (String[] e : out) { if (e[0].equals(s[0])) { dup = true; break; } }
                if (!dup) out.add(s);
            }
        }
        return out;
    }

    /**
     * Loads user-defined snippets for {@code lang} from SharedPreferences.
     * Storage format: pref key {@code custom_snip_<LANG>} →
     * {@code "trigger\u0001expansion\u0002trigger\u0001expansion…"}.
     * The non-printing separators avoid colliding with snippet contents
     * (which may legitimately contain {@code |}, {@code =}, newlines).
     */
    private List<String[]> loadCustomSnippets(String lang) {
        ArrayList<String[]> out = new ArrayList<>();
        if (lang == null) return out;
        String raw = prefs.getString("custom_snip_" + lang, "");
        if (TextUtils.isEmpty(raw)) return out;
        for (String pair : raw.split("\u0002")) {
            if (pair.isEmpty()) continue;
            int sep = pair.indexOf('\u0001');
            if (sep <= 0 || sep >= pair.length() - 1) continue;
            String trigger = pair.substring(0, sep);
            String expansion = pair.substring(sep + 1);
            out.add(new String[]{trigger, expansion});
        }
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

        // Clipboard panel toggle
        content.addView(makePopupRow("📋  Clipboard", getKeyTextColor(), pw,
                () -> togglePanel(false, false, true)));

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
        togglePanel(wantSymbols, wantEmoji, false);
    }

    /**
     * Three-way panel switcher. Only one of (symbols, emoji, clipboard) can be
     * active at a time; passing all-false reverts to the QWERTY view.
     */
    private void togglePanel(boolean wantSymbols, boolean wantEmoji, boolean wantClipboard) {
        panelSymbols = wantSymbols;
        panelEmoji = wantEmoji;
        panelClipboard = wantClipboard;
        if (!wantEmoji) emojiSearchQuery.setLength(0);
        // Reflect button "active" state via background.
        if (btnSymbolsPanel != null)
            btnSymbolsPanel.setBackgroundColor(wantSymbols ? getAccentColor() : getKeyBgColor());
        if (btnEmoji != null)
            btnEmoji.setBackgroundColor(wantEmoji ? getAccentColor() : getKeyBgColor());
        buildKeyboardRows();
    }

    /**
     * Clipboard panel — lists pinned + recent clipboard entries with tap-to-paste,
     * pin / unpin (📌), and delete (✕) actions. Rendered into the suggestion
     * strip + symbol/snippet/letter rows so we use the same vertical real
     * estate as the other panels.
     */
    private void buildClipboardPanel() {
        rowNumbers.setVisibility(View.GONE);
        rowSymbols.setVisibility(View.VISIBLE);
        rowSnippets.setVisibility(View.VISIBLE);
        rowLetters1.removeAllViews();
        rowLetters2.removeAllViews();
        rowLetters3.removeAllViews();
        rowSymbols.removeAllViews();
        rowSnippets.removeAllViews();

        int textCol = getKeyTextColor();
        int accent = getAccentColor();
        int keyBg = getKeyBgColor();

        // Header strip: "Clipboard" label + "Copy selection" + "Clear all" actions
        TextView header = new TextView(this);
        header.setText("Clipboard");
        header.setTextSize(13f);
        header.setTextColor(textCol);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dpToPx(10), 0, dpToPx(10), 0);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        header.setLayoutParams(hlp);
        rowSymbols.addView(header);

        Button btnCopySel = new Button(this);
        btnCopySel.setText("⧉ Copy");
        btnCopySel.setAllCaps(false);
        btnCopySel.setTextSize(11f);
        btnCopySel.setTextColor(accent);
        btnCopySel.setBackgroundColor(blend(keyBg, accent, 0.20f));
        LinearLayout.LayoutParams cslp = new LinearLayout.LayoutParams(
                dpToPx(72), dpToPx(36));
        cslp.setMargins(2, 4, 2, 4);
        btnCopySel.setLayoutParams(cslp);
        btnCopySel.setOnClickListener(v -> {
            haptic(v);
            captureSelectionToClipboard();
            buildClipboardPanel();
        });
        rowSymbols.addView(btnCopySel);

        Button btnClear = new Button(this);
        btnClear.setText("✕ Clear");
        btnClear.setAllCaps(false);
        btnClear.setTextSize(11f);
        btnClear.setTextColor(0xFFFF6666);
        btnClear.setBackgroundColor(0x22FF0000);
        LinearLayout.LayoutParams cclp = new LinearLayout.LayoutParams(
                dpToPx(72), dpToPx(36));
        cclp.setMargins(2, 4, 2, 4);
        btnClear.setLayoutParams(cclp);
        btnClear.setOnClickListener(v -> {
            haptic(v);
            clearUnpinnedClipboard();
            buildClipboardPanel();
        });
        rowSymbols.addView(btnClear);

        // List entries — pinned first, then recents.
        List<ClipEntry> entries = loadClipboard();
        LinearLayout[] rows = {rowSnippets, rowLetters1, rowLetters2, rowLetters3};
        int rowIdx = 0;
        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No copied items yet. Long-press a symbol or use ⧉ Copy on a selection.");
            empty.setTextSize(12f);
            empty.setTextColor(dim(textCol));
            empty.setGravity(Gravity.CENTER_VERTICAL);
            empty.setPadding(dpToPx(12), 0, dpToPx(12), 0);
            rowSnippets.addView(empty);
        }
        for (final ClipEntry e : entries) {
            if (rowIdx >= rows.length) break;
            LinearLayout container = rows[rowIdx++];
            container.removeAllViews();

            // Tap area → paste
            Button paste = new Button(this);
            String preview = e.text.replace("\n", " ");
            if (preview.length() > 60) preview = preview.substring(0, 60) + "…";
            paste.setText((e.pinned ? "📌  " : "") + preview);
            paste.setAllCaps(false);
            paste.setTextSize(13f);
            paste.setTextColor(textCol);
            paste.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            paste.setPadding(dpToPx(12), 0, dpToPx(12), 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(blend(keyBg, accent, e.pinned ? 0.18f : 0.05f));
            bg.setCornerRadius(dpToPx(8));
            paste.setBackground(bg);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            plp.setMargins(2, 2, 2, 2);
            paste.setLayoutParams(plp);
            paste.setOnClickListener(v -> {
                haptic(v);
                replaceSelectionOrInsert(e.text);
            });
            container.addView(paste);

            // Pin toggle
            Button pin = new Button(this);
            pin.setText(e.pinned ? "📌" : "📍");
            pin.setAllCaps(false);
            pin.setTextSize(14f);
            pin.setBackgroundColor(blend(keyBg, accent, 0.10f));
            LinearLayout.LayoutParams pinLp = new LinearLayout.LayoutParams(
                    dpToPx(40), ViewGroup.LayoutParams.MATCH_PARENT);
            pinLp.setMargins(2, 2, 2, 2);
            pin.setLayoutParams(pinLp);
            pin.setOnClickListener(v -> {
                haptic(v);
                togglePinned(e.text);
                buildClipboardPanel();
            });
            container.addView(pin);

            // Delete
            Button del = new Button(this);
            del.setText("✕");
            del.setAllCaps(false);
            del.setTextSize(14f);
            del.setTextColor(0xFFFF6666);
            del.setBackgroundColor(0x22FF0000);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                    dpToPx(40), ViewGroup.LayoutParams.MATCH_PARENT);
            dLp.setMargins(2, 2, 2, 2);
            del.setLayoutParams(dLp);
            del.setOnClickListener(v -> {
                haptic(v);
                deleteFromClipboard(e.text);
                buildClipboardPanel();
            });
            container.addView(del);
        }

        // Suggestion strip → instructions / "back to keyboard" hint.
        if (rowSuggestions != null) {
            rowSuggestions.removeAllViews();
            TextView hint = new TextView(this);
            hint.setText("Tap an item to paste · 📍 to pin · ✕ to delete");
            hint.setTextSize(11f);
            hint.setTextColor(dim(textCol));
            hint.setGravity(Gravity.CENTER_VERTICAL);
            hint.setPadding(dpToPx(10), 0, dpToPx(10), 0);
            rowSuggestions.addView(hint);
        }
        refreshCapsButtonStyle();
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

    // ─── Clipboard history (pinned + recent, persisted) ──────────────────────
    /** Single in-keyboard clipboard entry. */
    private static final class ClipEntry {
        final String text;
        final boolean pinned;
        ClipEntry(String text, boolean pinned) { this.text = text; this.pinned = pinned; }
    }

    /** Maximum number of unpinned (recent) entries kept in history. */
    private static final int MAX_CLIPBOARD = 20;

    /**
     * Adds {@code text} to the clipboard history. Existing entries are
     * de-duplicated; pinned status survives reinsert. Persisted to prefs.
     */
    private void addToClipboard(String text) {
        if (TextUtils.isEmpty(text)) return;
        ArrayList<ClipEntry> entries = new ArrayList<>(loadClipboard());
        boolean wasPinned = false;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).text.equals(text)) {
                wasPinned = entries.get(i).pinned;
                entries.remove(i);
                break;
            }
        }
        // Insert at the top of the unpinned region (right after the last pinned).
        int insertAt = 0;
        for (ClipEntry e : entries) { if (e.pinned) insertAt++; else break; }
        entries.add(insertAt, new ClipEntry(text, wasPinned));
        // Trim unpinned region.
        int unpinned = 0;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (!entries.get(i).pinned) {
                unpinned++;
                if (unpinned > MAX_CLIPBOARD) entries.remove(i);
            }
        }
        saveClipboard(entries);
    }

    /** Toggles the pinned flag for the entry whose text equals {@code text}. */
    private void togglePinned(String text) {
        ArrayList<ClipEntry> entries = new ArrayList<>(loadClipboard());
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).text.equals(text)) {
                ClipEntry old = entries.remove(i);
                ClipEntry updated = new ClipEntry(old.text, !old.pinned);
                // Reinsert: pinned items first, recent items after.
                int insertAt = 0;
                for (ClipEntry e : entries) {
                    if (e.pinned == updated.pinned) {
                        if (updated.pinned) insertAt++;
                        else break;
                    } else if (updated.pinned) {
                        // pinned go first; stop counting once we hit unpinned
                        break;
                    } else {
                        insertAt++;
                    }
                }
                entries.add(insertAt, updated);
                break;
            }
        }
        saveClipboard(entries);
    }

    private void deleteFromClipboard(String text) {
        ArrayList<ClipEntry> entries = new ArrayList<>(loadClipboard());
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).text.equals(text)) { entries.remove(i); break; }
        }
        saveClipboard(entries);
    }

    /** Removes every unpinned entry. Pinned items survive. */
    private void clearUnpinnedClipboard() {
        ArrayList<ClipEntry> entries = new ArrayList<>(loadClipboard());
        ArrayList<ClipEntry> kept = new ArrayList<>();
        for (ClipEntry e : entries) if (e.pinned) kept.add(e);
        saveClipboard(kept);
    }

    /**
     * Pulls the user's current selection (if any) into the clipboard history.
     * If nothing is selected, falls back to the word before the caret.
     */
    private void captureSelectionToClipboard() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
        if (et == null || et.text == null) return;
        String text;
        if (et.selectionStart != et.selectionEnd) {
            int s = Math.min(et.selectionStart, et.selectionEnd);
            int e = Math.max(et.selectionStart, et.selectionEnd);
            if (s < 0 || e > et.text.length()) return;
            text = et.text.subSequence(s, e).toString();
        } else {
            CharSequence before = ic.getTextBeforeCursor(64, 0);
            text = currentWord(before);
        }
        if (TextUtils.isEmpty(text)) {
            Toast.makeText(this, "Nothing to copy.", Toast.LENGTH_SHORT).show();
            return;
        }
        addToClipboard(text);
        Toast.makeText(this, "Copied to clipboard history", Toast.LENGTH_SHORT).show();
    }

    /**
     * Reads the clipboard list from SharedPreferences.
     *
     * <p>Storage format: one record per entry, separated by {@code "\u0002"}.
     * Each record is {@code "P\u0001<text>"} for pinned or
     * {@code "U\u0001<text>"} for unpinned. The non-printing separators dodge
     * collisions with arbitrary copied content (which may contain newlines,
     * pipes, or any printable character).
     */
    private List<ClipEntry> loadClipboard() {
        String raw = prefs.getString("clipboard_history", "");
        if (TextUtils.isEmpty(raw)) return Collections.emptyList();
        ArrayList<ClipEntry> out = new ArrayList<>();
        for (String rec : raw.split("\u0002")) {
            if (rec.isEmpty()) continue;
            int sep = rec.indexOf('\u0001');
            if (sep <= 0) continue;
            String tag = rec.substring(0, sep);
            String text = rec.substring(sep + 1);
            out.add(new ClipEntry(text, "P".equals(tag)));
        }
        return out;
    }

    private void saveClipboard(List<ClipEntry> entries) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append('\u0002');
            ClipEntry e = entries.get(i);
            sb.append(e.pinned ? 'P' : 'U').append('\u0001').append(e.text);
        }
        prefs.edit().putString("clipboard_history", sb.toString()).apply();
    }

    // ─── Theme ────────────────────────────────────────────────────────────────
    private void applyTheme() {
        boolean amoled = prefs.getBoolean("amoled", false);
        boolean dark   = prefs.getBoolean("dark", true);
        int bgColor  = prefs.getInt("bg_color",  dark ? 0xFF1A1A2E : 0xFFF0F0F0);
        if (amoled) bgColor = 0xFF000000;
        keyboardView.setBackgroundColor(bgColor);
    }

    /**
     * Returns the user's keyboard-height multiplier.
     *
     * <p>Stored as one of {@code "compact"} / {@code "standard"} /
     * {@code "comfortable"} / {@code "large"} (preset names) or as a raw float
     * under {@code "key_height_scale"} when the user picks a custom value.
     */
    private float getHeightScale() {
        // Direct float pref takes priority if set.
        float raw = prefs.getFloat("key_height_scale", 1.0f);
        // Clamp to a sensible band.
        if (raw < 0.7f) raw = 0.7f;
        if (raw > 1.6f) raw = 1.6f;
        return raw;
    }

    /**
     * Resizes every row whose height is meaningful for the keyboard height.
     * The XML still owns the relative proportions; this method just scales
     * each row's {@code layoutParams.height} by {@link #getHeightScale()}.
     */
    private void applyKeyboardHeight() {
        float scale = getHeightScale();
        // Each entry: (view, base height in dp). Widths and other params are
        // untouched.
        Object[][] rows = {
            { rowSuggestions != null ? (View) rowSuggestions.getParent() : null, 40 },
            { pcKeysScroll, 38 },
            { rowNumbers,   42 },
            { rowLetters1,  48 },
            { rowLetters2,  48 },
            // Row 3 is wrapped by a parent LinearLayout that owns the height —
            // we resize that parent so caps/backspace/letters all scale together.
            { rowLetters3 != null ? (View) rowLetters3.getParent() : null, 48 },
            { rowSymbols != null ? (View) rowSymbols.getParent() : null, 46 },
            { rowSnippets != null ? (View) rowSnippets.getParent() : null, 42 },
            { rowNav,       48 },
        };
        for (Object[] entry : rows) {
            View v = (View) entry[0];
            int baseDp = (Integer) entry[1];
            if (v == null) continue;
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp == null) continue;
            lp.height = Math.round(dpToPx(baseDp) * scale);
            v.setLayoutParams(lp);
        }
    }

    // ─── PC keys row (Esc/Tab/Ctrl/Alt/Shift/Win/F1–F12/Home/End/…) ──────────
    /**
     * Populates {@link #rowPcKeys}. Visibility honors the
     * {@code show_pc_keys} pref; letting the user toggle this row from
     * Settings without rebuilding the keyboard view.
     */
    private void buildPcKeysRow() {
        if (rowPcKeys == null) return;
        modifierButtons.clear();
        rowPcKeys.removeAllViews();

        boolean show = prefs.getBoolean("show_pc_keys", false);
        if (pcKeysScroll != null) {
            pcKeysScroll.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (!show) return;

        for (final String[] entry : PC_KEYS) {
            final String label = entry[0];
            final String spec  = entry[1];
            Button btn = makeKey(label);
            btn.setTextSize(11f);
            int width = label.length() <= 2 ? dpToPx(38) : dpToPx(46);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.MATCH_PARENT);
            lp.setMargins(2, 4, 2, 4);
            btn.setLayoutParams(lp);

            if (spec.startsWith("MOD:")) {
                final int metaBit = parseModifierBit(spec.substring(4));
                modifierButtons.put(metaBit, btn);
                btn.setOnClickListener(v -> {
                    haptic(v);
                    toggleModifier(metaBit);
                });
            } else if (spec.startsWith("KEY:")) {
                final int keyCode = Integer.parseInt(spec.substring(4));
                btn.setOnClickListener(v -> {
                    haptic(v);
                    sendKeyWithMeta(keyCode);
                    clearTransientModifiers();
                });
            }
            rowPcKeys.addView(btn);
        }
        refreshModifierButtonStyles();
    }

    private static int parseModifierBit(String name) {
        switch (name) {
            case "CTRL":  return KeyEvent.META_CTRL_ON;
            case "ALT":   return KeyEvent.META_ALT_ON;
            case "SHIFT": return KeyEvent.META_SHIFT_ON;
            case "META":  return KeyEvent.META_META_ON;
            default:      return 0;
        }
    }

    /**
     * Toggles a modifier bit in the active meta state and refreshes the visual
     * feedback for each modifier button.
     */
    private void toggleModifier(int metaBit) {
        if (metaBit == 0) return;
        activeMetaState ^= metaBit;
        refreshModifierButtonStyles();
    }

    /**
     * Clears non-locked sticky modifiers (Ctrl/Alt/Shift/Win) — called after a
     * regular key has consumed them, mirroring desktop sticky-keys behaviour.
     */
    private void clearTransientModifiers() {
        if (activeMetaState == 0) return;
        activeMetaState = 0;
        refreshModifierButtonStyles();
    }

    private void refreshModifierButtonStyles() {
        int accent = getAccentColor();
        int keyBg  = getKeyBgColor();
        int textCol = getKeyTextColor();
        for (Map.Entry<Integer, Button> e : modifierButtons.entrySet()) {
            boolean on = (activeMetaState & e.getKey()) != 0;
            Button b = e.getValue();
            // Reapply rounded background with appropriate fill.
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(on ? accent : keyBg);
            bg.setCornerRadius(dpToPx(10));
            b.setBackground(bg);
            b.setTextColor(on ? 0xFF000000 : textCol);
        }
    }

    /**
     * Sends a hardware-style key press with the active meta-state applied, then
     * leaves clearing of transient modifiers to the caller.
     */
    private void sendKeyWithMeta(int keyCode) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        long t = System.currentTimeMillis();
        ic.sendKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN,
                keyCode, 0, activeMetaState));
        ic.sendKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP,
                keyCode, 0, activeMetaState));
    }

    /**
     * Maps a single character to its hardware keycode where applicable. Used
     * when a modifier (Ctrl/Alt/Win) is active so that combos like Ctrl+C are
     * dispatched as real key events instead of plain text.
     */
    private static int charToKeyCode(String ch) {
        if (ch == null || ch.length() != 1) return 0;
        char c = ch.charAt(0);
        if (c >= 'a' && c <= 'z') return KeyEvent.KEYCODE_A + (c - 'a');
        if (c >= 'A' && c <= 'Z') return KeyEvent.KEYCODE_A + (c - 'A');
        if (c >= '0' && c <= '9') return KeyEvent.KEYCODE_0 + (c - '0');
        switch (c) {
            case ' ':  return KeyEvent.KEYCODE_SPACE;
            case '\n': return KeyEvent.KEYCODE_ENTER;
            case '\t': return KeyEvent.KEYCODE_TAB;
            case '.':  return KeyEvent.KEYCODE_PERIOD;
            case ',':  return KeyEvent.KEYCODE_COMMA;
            case '/':  return KeyEvent.KEYCODE_SLASH;
            case '\\': return KeyEvent.KEYCODE_BACKSLASH;
            case ';':  return KeyEvent.KEYCODE_SEMICOLON;
            case '\'': return KeyEvent.KEYCODE_APOSTROPHE;
            case '[':  return KeyEvent.KEYCODE_LEFT_BRACKET;
            case ']':  return KeyEvent.KEYCODE_RIGHT_BRACKET;
            case '-':  return KeyEvent.KEYCODE_MINUS;
            case '=':  return KeyEvent.KEYCODE_EQUALS;
            case '`':  return KeyEvent.KEYCODE_GRAVE;
            default:   return 0;
        }
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

    // ─── Haptic / sound ───────────────────────────────────────────────────────
    private void haptic(View v) {
        if (prefs.getBoolean("haptic", true)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (vibrator != null)
                    vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
        }
        playKeySound(v);
    }

    /**
     * Plays a system key-click sound via {@link AudioManager#playSoundEffect}.
     * Gated by the {@code key_sound} preference (off by default).
     *
     * <p>The volume is configurable through {@code key_sound_volume} (0–100,
     * default 50). When 0, no sound is played even if the toggle is on.
     */
    private void playKeySound(View v) {
        if (!prefs.getBoolean("key_sound", false)) return;
        if (audio == null) return;
        int volPct = prefs.getInt("key_sound_volume", 50);
        if (volPct <= 0) return;
        float vol = Math.min(1f, Math.max(0f, volPct / 100f));
        int effect = AudioManager.FX_KEYPRESS_STANDARD;
        if (v != null) {
            int id = v.getId();
            if (id == R.id.btn_backspace) effect = AudioManager.FX_KEYPRESS_DELETE;
            else if (id == R.id.btn_enter) effect = AudioManager.FX_KEYPRESS_RETURN;
            else if (id == R.id.btn_space) effect = AudioManager.FX_KEYPRESS_SPACEBAR;
        }
        try {
            audio.playSoundEffect(effect, vol);
        } catch (Exception ignored) {
            // Some devices reject custom volumes; fall back to default volume.
            try { audio.playSoundEffect(effect); } catch (Exception ignored2) {}
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
