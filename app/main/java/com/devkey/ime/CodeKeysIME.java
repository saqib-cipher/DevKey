package com.codekeys.ime;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CodeKeysIME — coding-focused soft keyboard.
 *
 * <h2>Architecture</h2>
 * State that used to live as scattered fields on this class is now split into
 * focused modules, leaving this file as a thin orchestrator:
 * <ul>
 *   <li>{@link InputEngine} — text manipulation primitives (commit, delete,
 *       selection-aware backspace, suggestion application).</li>
 *   <li>{@link SuggestionEngine} — dictionary + frequency ranking.</li>
 *   <li>{@link EmojiEngine} — categorised emoji set + recents + search.</li>
 *   <li>{@link ClipboardStore} — persisted clipboard history.</li>
 *   <li>{@link UIRenderer} — view factory used by every panel.</li>
 * </ul>
 *
 * <p>Panel state is a single {@link PanelMode} value (enum) instead of a set
 * of mutually-exclusive booleans. {@link #switchPanel(PanelMode)} swaps the
 * active view inside the {@code panel_container} {@link FrameLayout} with a
 * short cross-fade, so the keyboard never tears down its entire view tree on
 * a panel change.
 */
public class CodeKeysIME extends InputMethodService {

    // ─── Constants ────────────────────────────────────────────────────────────
    private static final int CAPS_OFF      = 0;
    private static final int CAPS_SINGLE   = 1; // one shot, reverts on next letter
    private static final int CAPS_LOCKED   = 2; // sticky until toggled off
    private static final long DOUBLE_TAP_MS = 300L;

    // ─── State ────────────────────────────────────────────────────────────────
    private int capsState = CAPS_OFF;
    private long lastCapsTapMs = 0L;

    private PanelMode panelMode = PanelMode.KEYBOARD;
    private String currentLang = "GENERAL";
    private int undoableOps = 0;
    private int redoableOps = 0;

    private SharedPreferences prefs;
    private Vibrator vibrator;
    private AudioManager audio;
    private ClipboardManager systemClipboard;
    private ClipboardManager.OnPrimaryClipChangedListener clipboardListener;

    // Modules
    private InputEngine inputEngine;
    private SuggestionEngine suggestionEngine;
    private EmojiEngine emojiEngine;
    private ClipboardStore clipboardStore;
    private UIRenderer ui;

    private List<String> currentSuggestions = new ArrayList<>();
    private PopupWindow activePreview;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private int activeMetaState = 0;

    // ─── Clipboard surfacing ──────────────────────────────────────────────────
    /**
     * Chips queued to ride at the head of the suggestion row. Populated whenever
     * the system clipboard changes (new copy detected) — the full clip plus any
     * extracted links / numbers / emails appear as separate chips. Cleared when
     * the user taps any chip or when the keyboard rebinds to a fresh editor.
     */
    private final List<String> pendingClipChips = new ArrayList<>();

    // Patterns used to break a clip into sub-chips (URL / number / email).
    private static final Pattern URL_PATTERN =
            Pattern.compile("\\b((?:https?://|www\\.)[\\w.-]+(?:/[\\w./?=&%#-]*)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER_PATTERN =
            Pattern.compile("[+]?\\d[\\d ()-]{2,}\\d|\\b\\d+(?:\\.\\d+)?\\b");
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");

    // Views — top-level
    private View keyboardView;
    private LinearLayout rowSuggestions;
    private HorizontalScrollView suggestionScroll;
    private FrameLayout panelContainer;
    private LinearLayout rowPcKeys;
    private HorizontalScrollView pcKeysScroll;

    // Cached panel views
    private View keyboardPanelView;
    private View emojiPanelView;
    private View clipboardPanelView;
    /** Wrapper used in EMOJI mode that stacks emoji grid + main QWERTY for search. */
    private LinearLayout emojiCombinedView;

    // Bottom action row
    private Button btnEnter, btnSpace;
    // The remaining action-row buttons are ImageButtons (Tabler vector icons)
    // — kept as ImageButton so findViewById casts cleanly. The IME tints them
    // at runtime to track the active theme's text/accent colours.
    private ImageButton btnSettings, btnSymbolsPanel, btnEmoji;
    private ImageButton btnUndo, btnRedo;
    private ImageButton btnArrowLeft, btnArrowRight, btnArrowUp, btnArrowDown;

    // Keyboard panel children (cached when the panel is built)
    private LinearLayout rowSymbols, rowSnippets, rowNumbers;
    private LinearLayout rowLetters1, rowLetters2, rowLetters3;
    private ImageButton btnCaps, btnBackspace;

    /** Modifier buttons inside the PC keys row, kept so we can refresh state. */
    private final HashMap<Integer, Button> modifierButtons = new HashMap<>();

    // ─── Languages ────────────────────────────────────────────────────────────
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
        LANG_SYMBOLS.put("C",      new String[]{"{","}","(",")","[","]","*","&",";","\"","'","#","<",">","=","+","-","/","%","!","~","^","|","\\","?",","});
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

    /**
     * Symbol panel (?123) layout — rendered into the existing letter rows.
     * The number row (1..0) is intentionally OMITTED here because the keyboard
     * already shows the dedicated `row_numbers` strip permanently above the
     * letters; doubling them up wasted vertical space and confused users.
     */
    private static final String[][] SYMBOL_PANEL = {
        {"~","`","|","\\","{","}","[","]","<",">"},
        {"@","#","$","_","&","-","+","(",")","/"},
        {"*","\"","'",":",";","!","?",",",".","="}
    };

    /** PC keyboard row contents — see original notes preserved in git history. */
    private static final String[][] PC_KEYS = {
        {"Esc",    "KEY:111"}, {"Tab",  "KEY:61"},
        {"Ctrl",   "MOD:CTRL"},{"Alt",  "MOD:ALT"},{"Shift","MOD:SHIFT"},{"Win","MOD:META"},
        {"F1","KEY:131"},{"F2","KEY:132"},{"F3","KEY:133"},{"F4","KEY:134"},
        {"F5","KEY:135"},{"F6","KEY:136"},{"F7","KEY:137"},{"F8","KEY:138"},
        {"F9","KEY:139"},{"F10","KEY:140"},{"F11","KEY:141"},{"F12","KEY:142"},
        {"Home","KEY:122"},{"End","KEY:123"},{"PgUp","KEY:92"},{"PgDn","KEY:93"},
        {"Ins","KEY:124"},{"Del","KEY:112"}
    };

    // ─── Lifecycle ────────────────────────────────────────────────────────────
    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("codekeys_prefs", MODE_PRIVATE);
        // Seed prefs with defaults shipped in assets/settings_defaults.json
        // before reading lang/etc. — safe on every launch (only fills in
        // keys the user has never set).
        AssetDefaults.seedDefaults(this, prefs);
        // Hot-load snippets/symbols from assets (overrides hardcoded
        // defaults when the JSON is present and well-formed).
        loadAssetOverrides();
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        audio    = (AudioManager) getSystemService(AUDIO_SERVICE);
        currentLang = prefs.getString("lang", "GENERAL");
        inputEngine      = new InputEngine(this);
        suggestionEngine = new SuggestionEngine(prefs);
        emojiEngine      = new EmojiEngine(prefs);
        clipboardStore   = new ClipboardStore(prefs);
        ui               = new UIRenderer(this);
        registerSystemClipboardListener();
    }

    @Override
    public void onDestroy() {
        unregisterSystemClipboardListener();
        super.onDestroy();
    }

    /**
     * Registers a listener with the system {@link ClipboardManager} so that
     * anything copied anywhere on the device (browsers, other apps, long-press
     * copy menus, etc.) is mirrored into our in-keyboard {@link ClipboardStore}.
     *
     * <p>While the IME is the active input method, the system grants it
     * foreground status, which is required to read the primary clip on
     * Android 10+. Read failures (e.g. password-protected clips) are silently
     * ignored.
     */
    private void registerSystemClipboardListener() {
        systemClipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (systemClipboard == null) return;
        clipboardListener = () -> {
            try {
                if (systemClipboard == null || !systemClipboard.hasPrimaryClip()) return;
                ClipData clip = systemClipboard.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) return;
                CharSequence text = clip.getItemAt(0).coerceToText(this);
                if (TextUtils.isEmpty(text)) return;
                String s = text.toString();
                clipboardStore.add(s);
                // Surface the fresh clip (and any obvious sub-chunks: URLs,
                // emails, numbers) on the suggestion strip so the user can
                // paste with one tap without opening the clipboard panel.
                addPendingClipChip(s);
                java.util.regex.Matcher m;
                m = URL_PATTERN.matcher(s);
                while (m.find()) addPendingClipChip(m.group());
                m = EMAIL_PATTERN.matcher(s);
                while (m.find()) addPendingClipChip(m.group());
                m = NUMBER_PATTERN.matcher(s);
                while (m.find()) addPendingClipChip(m.group().trim());
                if (panelMode == PanelMode.CLIPBOARD) refreshClipboardPanel();
                refreshSuggestions();
            } catch (Exception ignored) {
                // Some apps mark clips as sensitive — reading throws. Skip.
            }
        };
        try {
            systemClipboard.addPrimaryClipChangedListener(clipboardListener);
        } catch (Exception ignored) {}
    }

    /**
     * Adds {@code s} to {@link #pendingClipChips} if it's a non-trivial,
     * non-duplicate string. Caps the list at 6 entries so the suggestion
     * strip doesn't push regular candidates off-screen.
     */
    private void addPendingClipChip(String s) {
        if (TextUtils.isEmpty(s)) return;
        s = s.trim();
        if (s.length() < 2 || s.length() > 200) return;
        if (pendingClipChips.contains(s)) return;
        pendingClipChips.add(0, s);
        while (pendingClipChips.size() > 6) {
            pendingClipChips.remove(pendingClipChips.size() - 1);
        }
    }

    private void unregisterSystemClipboardListener() {
        if (systemClipboard != null && clipboardListener != null) {
            try { systemClipboard.removePrimaryClipChangedListener(clipboardListener); }
            catch (Exception ignored) {}
        }
        clipboardListener = null;
        systemClipboard = null;
    }

    /**
     * Loads optional overrides from {@code /assets} so users can ship custom
     * snippets and symbol sets without rebuilding the app. Hardcoded defaults
     * (the static initializers above) serve as fallback for any language not
     * present in the JSON; partial overrides are merged in-place.
     */
    private void loadAssetOverrides() {
        try {
            Map<String, List<String[]>> assetSnips = AssetDefaults.loadSnippets(this);
            if (assetSnips != null) {
                for (Map.Entry<String, List<String[]>> e : assetSnips.entrySet()) {
                    List<String[]> rows = e.getValue();
                    if (rows == null || rows.isEmpty()) continue;
                    String[][] arr = new String[rows.size()][];
                    for (int i = 0; i < rows.size(); i++) arr[i] = rows.get(i);
                    LANG_SNIPPETS.put(e.getKey(), arr);
                }
            }
        } catch (Exception ignored) {}
        try {
            Map<String, String[]> assetSyms = AssetDefaults.loadLangSymbols(this);
            if (assetSyms != null) {
                for (Map.Entry<String, String[]> e : assetSyms.entrySet()) {
                    if (e.getValue() != null && e.getValue().length > 0) {
                        LANG_SYMBOLS.put(e.getKey(), e.getValue());
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * Hides the entire suggestion strip (header bar) when the user has turned
     * off "Show Suggestions" in settings. Setting visibility to GONE collapses
     * the layout space rather than just hiding the chips.
     */
    private void applySuggestionVisibility() {
        if (suggestionScroll == null) return;
        boolean show = prefs.getBoolean("show_suggestions", true);
        suggestionScroll.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    @Override
    public View onCreateInputView() {
        keyboardView = LayoutInflater.from(this).inflate(R.layout.keyboard_main, null);
        rowSuggestions   = keyboardView.findViewById(R.id.row_suggestions);
        suggestionScroll = keyboardView.findViewById(R.id.suggestion_scroll);
        panelContainer   = keyboardView.findViewById(R.id.panel_container);
        rowPcKeys        = keyboardView.findViewById(R.id.row_pc_keys);
        pcKeysScroll     = keyboardView.findViewById(R.id.pc_keys_scroll);

        bindBottomActionRow();
        buildPcKeysRow();
        applySuggestionVisibility();

        // Reset cached panel views so new theme/scale settings are applied to
        // freshly-inflated children.
        keyboardPanelView = null;
        emojiPanelView = null;
        clipboardPanelView = null;

        applyTheme();
        switchPanel(PanelMode.KEYBOARD);
        return keyboardView;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        if (capsState == CAPS_SINGLE) capsState = CAPS_OFF;
        emojiEngine.clearSearch();
        undoableOps = 0;
        redoableOps = 0;
        // Re-register the system clipboard listener if needed and capture the
        // current clip. On API 29+ this only succeeds while the IME has focus.
        if (clipboardListener == null) registerSystemClipboardListener();
        captureCurrentSystemClip();
        if (keyboardView != null) {
            buildPcKeysRow();
            applySuggestionVisibility();
            updateEnterButton(info);
            switchPanel(PanelMode.KEYBOARD);
            applyTheme();
            refreshHistoryButtonsState();
            refreshArrowButtonsState();
        }
    }

    /** Reads whatever is currently on the system clipboard right now. */
    private void captureCurrentSystemClip() {
        if (systemClipboard == null) return;
        try {
            if (!systemClipboard.hasPrimaryClip()) return;
            ClipData clip = systemClipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            if (!TextUtils.isEmpty(text)) clipboardStore.add(text.toString());
        } catch (Exception ignored) {}
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd,
                                  int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                candidatesStart, candidatesEnd);
        if (panelMode == PanelMode.KEYBOARD || panelMode == PanelMode.SYMBOLS) {
            refreshSuggestions();
        }
        refreshArrowButtonsState();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        dismissPreview();
        super.onFinishInputView(finishingInput);
    }

    // ─── Bottom action row ────────────────────────────────────────────────────
    private void bindBottomActionRow() {
        btnSettings     = keyboardView.findViewById(R.id.btn_settings);
        btnSymbolsPanel = keyboardView.findViewById(R.id.btn_symbols_panel);
        btnEmoji        = keyboardView.findViewById(R.id.btn_emoji);
        btnSpace        = keyboardView.findViewById(R.id.btn_space);
        btnEnter        = keyboardView.findViewById(R.id.btn_enter);
        btnUndo         = keyboardView.findViewById(R.id.btn_undo);
        btnRedo         = keyboardView.findViewById(R.id.btn_redo);
        btnArrowLeft    = keyboardView.findViewById(R.id.btn_arrow_left);
        btnArrowRight   = keyboardView.findViewById(R.id.btn_arrow_right);
        btnArrowUp      = keyboardView.findViewById(R.id.btn_arrow_up);
        btnArrowDown    = keyboardView.findViewById(R.id.btn_arrow_down);

        btnSettings.setOnClickListener(v -> { haptic(v); showSettingsLanguagePopup(v); });
        btnSymbolsPanel.setOnClickListener(v -> {
            haptic(v);
            switchPanel(panelMode == PanelMode.SYMBOLS ? PanelMode.KEYBOARD : PanelMode.SYMBOLS);
        });
        btnEmoji.setOnClickListener(v -> {
            haptic(v);
            switchPanel(panelMode == PanelMode.EMOJI ? PanelMode.KEYBOARD : PanelMode.EMOJI);
        });
        btnSpace.setOnClickListener(v -> {
            haptic(v);
            // While the clipboard panel is open, the giant space bar doubles as
            // an "back to keyboard" button — committing whitespace there isn't
            // what the user wants and the explicit ABC button is too small.
            if (panelMode == PanelMode.CLIPBOARD) {
                switchPanel(PanelMode.KEYBOARD);
            } else {
                onSpace();
            }
        });
        btnEnter.setOnClickListener(v -> { haptic(v); performEnterAction(); });
        btnUndo.setOnClickListener(v -> { haptic(v); doUndo(); });
        btnRedo.setOnClickListener(v -> { haptic(v); doRedo(); });
        btnArrowLeft.setOnClickListener(v -> sendArrow(KeyEvent.KEYCODE_DPAD_LEFT));
        btnArrowRight.setOnClickListener(v -> sendArrow(KeyEvent.KEYCODE_DPAD_RIGHT));
        btnArrowUp.setOnClickListener(v -> sendArrow(KeyEvent.KEYCODE_DPAD_UP));
        btnArrowDown.setOnClickListener(v -> sendArrow(KeyEvent.KEYCODE_DPAD_DOWN));

        updateEnterButton(getCurrentInputEditorInfo());
        refreshHistoryButtonsState();
        refreshArrowButtonsState();
    }

    // ─── Panel switching (single source of truth) ────────────────────────────
    /**
     * Swaps the active panel inside {@link #panelContainer} with a short fade
     * transition. Panel views are inflated lazily and cached, so subsequent
     * switches reuse the same layout instance instead of rebuilding from XML.
     */
    private void switchPanel(PanelMode target) {
        if (panelContainer == null) return;
        panelMode = target;
        if (target != PanelMode.EMOJI) emojiEngine.clearSearch();
        View view;
        switch (target) {
            case EMOJI:
                view = buildEmojiCombinedView();
                refreshEmojiPanel();
                break;
            case CLIPBOARD:
                if (clipboardPanelView == null) clipboardPanelView = ui.inflateClipboardPanel(panelContainer);
                view = clipboardPanelView;
                refreshClipboardPanel();
                break;
            case SYMBOLS:
                if (keyboardPanelView == null) keyboardPanelView = ui.inflateKeyboardPanel(panelContainer);
                view = keyboardPanelView;
                bindKeyboardPanelChildren();
                fillSymbolsLayout();
                break;
            case KEYBOARD:
            default:
                if (keyboardPanelView == null) keyboardPanelView = ui.inflateKeyboardPanel(panelContainer);
                view = keyboardPanelView;
                bindKeyboardPanelChildren();
                fillKeyboardLayout();
                break;
        }
        // Replace child only if it actually changed — avoids unnecessary fade flicker.
        if (panelContainer.getChildCount() != 1 || panelContainer.getChildAt(0) != view) {
            panelContainer.removeAllViews();
            if (view.getParent() instanceof ViewGroup) {
                ((ViewGroup) view.getParent()).removeView(view);
            }
            panelContainer.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            view.setAlpha(0f);
            view.animate().alpha(1f).setDuration(140).start();
        }

        // Reflect button "active" state — re-styling all static keys keeps
        // the rounded-radius / stroke look consistent while the active panel
        // changes its highlight.
        styleStaticKeys();
        // The space bar's label changes role with the panel: in clipboard mode
        // it acts as an "back to keyboard" button instead of inserting space.
        if (btnSpace != null) {
            btnSpace.setText(panelMode == PanelMode.CLIPBOARD ? "ABC — back to keyboard" : "space");
        }

        applyKeyboardHeight();
        if (panelMode == PanelMode.KEYBOARD || panelMode == PanelMode.SYMBOLS) {
            refreshSuggestions();
        } else if (panelMode == PanelMode.EMOJI) {
            renderEmojiSearchInStrip();
        } else if (panelMode == PanelMode.CLIPBOARD) {
            renderClipboardHintInStrip();
        }
    }

    /**
     * Builds (or refreshes) the EMOJI mode wrapper that stacks the emoji grid
     * panel on top of a stripped-down QWERTY keyboard. Letter taps on the
     * keyboard route into the emoji search via {@link #commitChar(String)}; the
     * keyboard's backspace pops search characters too. Non-letter rows
     * (symbol toolbar, snippet row, number row) are hidden so the panel stays
     * keyboard-height while still offering full QWERTY input for searching.
     */
    private View buildEmojiCombinedView() {
        if (emojiPanelView == null) emojiPanelView = ui.inflateEmojiPanel(panelContainer);
        if (keyboardPanelView == null) keyboardPanelView = ui.inflateKeyboardPanel(panelContainer);
        bindKeyboardPanelChildren();
        // Refill QWERTY (letters only) so caps state is up-to-date.
        buildQwertyRows();
        refreshCapsButtonStyle();
        // Hide rows that aren't useful while searching emoji.
        if (rowSymbols != null) ((View) rowSymbols.getParent()).setVisibility(View.GONE);
        if (rowSnippets != null) ((View) rowSnippets.getParent()).setVisibility(View.GONE);
        if (rowNumbers != null) rowNumbers.setVisibility(View.GONE);
        // Compress the emoji grid so the combined panel still fits the
        // keyboard footprint instead of pushing the bottom action row off the
        // screen. Tabs + search + grid + QWERTY ≈ a normal keyboard height.
        View emojiGridScroll = emojiPanelView.findViewById(R.id.emoji_grid_scroll);
        if (emojiGridScroll != null) {
            ViewGroup.LayoutParams lp = emojiGridScroll.getLayoutParams();
            if (lp != null) { lp.height = dp(120); emojiGridScroll.setLayoutParams(lp); }
        }
        if (emojiCombinedView == null) {
            emojiCombinedView = new LinearLayout(this);
            emojiCombinedView.setOrientation(LinearLayout.VERTICAL);
        }
        // Detach panels from any current parent before re-stacking.
        detachFromParent(emojiPanelView);
        detachFromParent(keyboardPanelView);
        emojiCombinedView.removeAllViews();
        emojiCombinedView.addView(emojiPanelView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        emojiCombinedView.addView(keyboardPanelView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return emojiCombinedView;
    }

    private static void detachFromParent(View v) {
        if (v == null) return;
        if (v.getParent() instanceof ViewGroup) {
            ((ViewGroup) v.getParent()).removeView(v);
        }
    }

    // ─── Keyboard / Symbols panel ────────────────────────────────────────────
    private void bindKeyboardPanelChildren() {
        if (keyboardPanelView == null) return;
        rowSymbols   = keyboardPanelView.findViewById(R.id.row_symbols);
        rowSnippets  = keyboardPanelView.findViewById(R.id.row_snippets);
        rowNumbers   = keyboardPanelView.findViewById(R.id.row_numbers);
        rowLetters1  = keyboardPanelView.findViewById(R.id.row_letters1);
        rowLetters2  = keyboardPanelView.findViewById(R.id.row_letters2);
        rowLetters3  = keyboardPanelView.findViewById(R.id.row_letters3);
        btnCaps      = keyboardPanelView.findViewById(R.id.btn_caps);
        btnBackspace = keyboardPanelView.findViewById(R.id.btn_backspace);

        if (btnCaps != null) btnCaps.setOnClickListener(v -> { haptic(v); onCapsTapped(); });
        if (btnBackspace != null) attachBackspaceHoldHandler(btnBackspace);
    }

    private void fillKeyboardLayout() {
        rowNumbers.setVisibility(View.VISIBLE);
        if (rowSymbols != null) ((View) rowSymbols.getParent()).setVisibility(View.VISIBLE);
        if (rowSnippets != null) ((View) rowSnippets.getParent()).setVisibility(View.VISIBLE);
        buildNumberRow();
        buildQwertyRows();
        buildSymbolRow();
        buildSnippetRow();
        refreshCapsButtonStyle();
    }

    private void fillSymbolsLayout() {
        // Keep numbers / lang symbols / snippets visible in symbols mode so the
        // user has every glyph in reach without flipping back to QWERTY.
        rowNumbers.setVisibility(View.VISIBLE);
        if (rowSymbols != null) ((View) rowSymbols.getParent()).setVisibility(View.VISIBLE);
        if (rowSnippets != null) ((View) rowSnippets.getParent()).setVisibility(View.VISIBLE);
        rowLetters1.removeAllViews();
        rowLetters2.removeAllViews();
        rowLetters3.removeAllViews();

        // Lang-aware top toolbar + numbers row stay populated.
        buildSymbolRow();
        buildNumberRow();

        // Extra symbol rows in the letter slots — combine the static
        // SYMBOL_PANEL with whatever extras the active language defines so the
        // user sees more glyphs than the cramped GENERAL set.
        String[][] rows = buildSymbolKeyRows();
        LinearLayout[] containers = {rowLetters1, rowLetters2, rowLetters3};
        for (int i = 0; i < rows.length && i < containers.length; i++) {
            for (final String s : rows[i]) {
                Button btn = ui.makeKey(s, getKeyBgColor(), getKeyTextColor());
                btn.setOnTouchListener(previewToucher(s));
                btn.setOnClickListener(v -> { haptic(v); insertSymbolWithAutoClose(s); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 0, 1f);
                lp.height = LinearLayout.LayoutParams.MATCH_PARENT;
                lp.setMargins(dp(2), dp(2), dp(2), dp(2));
                btn.setLayoutParams(lp);
                containers[i].addView(btn);
            }
        }
        buildSnippetRow();
        // Caps doesn't apply to symbols — repurpose that slot as a glyph
        // insert ("•") so we don't waste a key. The button is an ImageButton,
        // so we tint the existing arrow icon to the accent color to indicate
        // its repurposed state; refreshCapsButtonStyle() is not called here
        // so the styling sticks until the panel switches back.
        if (btnCaps != null) {
            btnCaps.setImageResource(R.drawable.arrow_big_up);
            btnCaps.setColorFilter(getAccentColor());
            btnCaps.setBackground(ui.roundedFill(getKeyBgColor(), dp(10)));
            btnCaps.setOnClickListener(v -> { haptic(v); insertSymbolWithAutoClose("•"); });
        }
    }

    /**
     * Builds the three rows of symbols shown in the QWERTY slots while in
     * symbols mode. The static {@link #SYMBOL_PANEL} provides the base set;
     * any glyph in the active language's symbol set that isn't already on
     * screen (toolbar / numbers / static panel) is appended so the user has a
     * superset, not just a duplicate of the lang toolbar.
     */
    private String[][] buildSymbolKeyRows() {
        String[][] base = SYMBOL_PANEL;
        String[] langSymbols = LANG_SYMBOLS.containsKey(currentLang)
                ? LANG_SYMBOLS.get(currentLang) : LANG_SYMBOLS.get("GENERAL");
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (String[] row : base) for (String s : row) seen.add(s);
        for (String n : new String[]{"1","2","3","4","5","6","7","8","9","0"}) seen.add(n);
        ArrayList<String> extras = new ArrayList<>();
        if (langSymbols != null) {
            for (String s : langSymbols) {
                if (!seen.contains(s)) { extras.add(s); seen.add(s); }
            }
        }
        // Append a small set of useful extra glyphs the user might want.
        for (String s : new String[]{"~","^","¬","°","±","€","£","¥","§","¶"}) {
            if (!seen.contains(s)) { extras.add(s); seen.add(s); }
        }
        if (extras.isEmpty()) return base;
        // Distribute extras across rows 1 and 2 so no single row blows past
        // ~12 keys (each row would otherwise become unusably narrow).
        ArrayList<String> r0 = new ArrayList<>();
        for (String s : base[0]) r0.add(s);
        ArrayList<String> r1 = new ArrayList<>();
        for (String s : base[1]) r1.add(s);
        ArrayList<String> r2 = new ArrayList<>();
        for (String s : base[2]) r2.add(s);
        for (String e : extras) {
            ArrayList<String> target = r1.size() <= r2.size() ? r1 : r2;
            if (target.size() >= 12) {
                if (r1.size() < 12) target = r1;
                else if (r2.size() < 12) target = r2;
                else if (r0.size() < 12) target = r0;
                else break;
            }
            target.add(e);
        }
        return new String[][]{
                r0.toArray(new String[0]),
                r1.toArray(new String[0]),
                r2.toArray(new String[0])
        };
    }

    private void buildNumberRow() {
        rowNumbers.removeAllViews();
        String[] nums = {"1","2","3","4","5","6","7","8","9","0"};
        for (final String n : nums) addLetterKey(rowNumbers, n, false);
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

        Button btn = ui.makeKey(label, getKeyBgColor(), getKeyTextColor());
        btn.setOnTouchListener(previewToucher(label));
        btn.setOnClickListener(v -> {
            haptic(v);
            String ch = (isLetter && capsState != CAPS_OFF) ? letter.toUpperCase() : letter;
            commitChar(ch);
            noteOperation();
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 0, 1f);
        lp.height = LinearLayout.LayoutParams.MATCH_PARENT;
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
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
            Button btn = ui.makeKey(sym, getKeyBgColor(), getKeyTextColor());
            btn.setTextSize(12f);
            btn.setOnTouchListener(previewToucher(sym));
            btn.setOnClickListener(v -> { haptic(v); insertSymbolWithAutoClose(sym); });
            btn.setOnLongClickListener(v -> {
                haptic(v);
                clipboardStore.add(sym);
                Toast.makeText(this, "Copied: " + sym, Toast.LENGTH_SHORT).show();
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(38));
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            btn.setLayoutParams(lp);
            rowSymbols.addView(btn);
        }
    }

    private void buildSnippetRow() {
        rowSnippets.removeAllViews();
        int accentColor = getAccentColor();
        for (final String[] snippet : effectiveSnippets()) {
            Button btn = ui.makeKey(snippet[0], getKeyBgColor(), accentColor);
            btn.setTextSize(11f);
            btn.setOnClickListener(v -> {
                haptic(v);
                replaceSelectionOrInsert(snippet[1]);
                noteOperation();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(56), dp(34));
            lp.setMargins(dp(2), dp(2), dp(2), dp(2));
            btn.setLayoutParams(lp);
            rowSnippets.addView(btn);
        }
    }

    // ─── Emoji panel ─────────────────────────────────────────────────────────
    private void refreshEmojiPanel() {
        if (emojiPanelView == null) return;
        ui.fillEmojiPanel(emojiPanelView, emojiEngine,
                getKeyBgColor(), getKeyTextColor(), getAccentColor(),
                /* onCategoryTap */ key -> {
                    emojiEngine.setCategory(key);
                    refreshEmojiPanel();
                },
                /* onEmojiTap */ emoji -> {
                    haptic(emojiPanelView);
                    inputEngine.commit(emoji);
                    emojiEngine.rememberRecent(emoji);
                    noteOperation();
                },
                /* onBackspaceSearch */ () -> {
                    haptic(emojiPanelView);
                    if (emojiEngine.isSearching()) {
                        emojiEngine.popSearchChar();
                    } else if (inputEngine.deleteOne()) {
                        // No active search — fall back to deleting the last
                        // committed character, so the in-panel ⌫ key never
                        // feels broken even when the search box is empty.
                        noteDeletion();
                    }
                    refreshEmojiPanel();
                },
                /* onClearSearch */ () -> {
                    haptic(emojiPanelView);
                    emojiEngine.clearSearch();
                    refreshEmojiPanel();
                });
    }

    /**
     * The emoji search bar now lives at the bottom of the emoji panel itself,
     * so the suggestion strip stays out of the way while emoji is open. We
     * just blank it out (no "type to search" hint, no chips) to keep the look
     * uncluttered and consistent with Gboard.
     */
    private void renderEmojiSearchInStrip() {
        if (rowSuggestions == null) return;
        rowSuggestions.removeAllViews();
    }

    // ─── Clipboard panel ─────────────────────────────────────────────────────
    private void refreshClipboardPanel() {
        if (clipboardPanelView == null) return;
        ui.fillClipboardPanel(clipboardPanelView, clipboardStore.all(),
                getKeyBgColor(), getKeyTextColor(), getAccentColor(),
                /* onPaste */ entry -> {
                    haptic(clipboardPanelView);
                    inputEngine.commit(entry.text);
                    noteOperation();
                },
                /* onPin */ entry -> {
                    haptic(clipboardPanelView);
                    clipboardStore.togglePin(entry.text);
                    refreshClipboardPanel();
                },
                /* onDelete */ entry -> {
                    haptic(clipboardPanelView);
                    clipboardStore.remove(entry.text);
                    refreshClipboardPanel();
                },
                /* onCopySelection */ () -> {
                    haptic(clipboardPanelView);
                    captureSelectionToClipboard();
                    refreshClipboardPanel();
                },
                /* onClearAll */ () -> {
                    haptic(clipboardPanelView);
                    clipboardStore.clearUnpinned();
                    refreshClipboardPanel();
                });
    }

    private void renderClipboardHintInStrip() {
        if (rowSuggestions == null) return;
        rowSuggestions.removeAllViews();
        TextView hint = new TextView(this);
        hint.setText("Tap to paste · 📌 to pin · ✕ to delete");
        hint.setTextSize(11f);
        hint.setTextColor(dim(getKeyTextColor()));
        hint.setGravity(Gravity.CENTER_VERTICAL);
        hint.setPadding(dp(10), 0, dp(10), 0);
        rowSuggestions.addView(hint);
    }

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
            text = inputEngine.currentWord();
        }
        if (TextUtils.isEmpty(text)) {
            Toast.makeText(this, "Nothing to copy.", Toast.LENGTH_SHORT).show();
            return;
        }
        clipboardStore.add(text);
        Toast.makeText(this, "Copied to clipboard history", Toast.LENGTH_SHORT).show();
    }

    // ─── Suggestions strip (M3 chips) ────────────────────────────────────────
    private void refreshSuggestions() {
        if (rowSuggestions == null) return;
        // When the user turned off "Show Suggestions" we skip building chips
        // entirely — the parent scroll view is already GONE so layout space
        // is reclaimed; this just avoids wasted work on every key press.
        if (!prefs.getBoolean("show_suggestions", true)) {
            rowSuggestions.removeAllViews();
            return;
        }
        rowSuggestions.removeAllViews();
        String word = inputEngine.currentWord();
        currentSuggestions = suggestionEngine.compute(word, effectiveSnippets());
        int textCol = getKeyTextColor();
        int accent = getAccentColor();
        int chipBg = blend(getKeyBgColor(), 0xFF000000, 0.18f);
        int bestBg = blend(chipBg, accent, 0.45f);
        int clipBg = blend(chipBg, accent, 0.6f);

        // Clipboard chips ride at the head of the row so the freshly-copied
        // content is one tap away. Each link / number / email parsed out of
        // the clip becomes its own chip beside the full clip.
        if (!pendingClipChips.isEmpty()) {
            for (final String clip : pendingClipChips) {
                String label = clip.length() > 28 ? clip.substring(0, 28) + "…" : clip;
                Button chip = ui.makeChip("📋 " + label, clipBg, textCol, true);
                chip.setOnClickListener(v -> {
                    haptic(v);
                    inputEngine.commit(clip);
                    pendingClipChips.clear();
                    refreshSuggestions();
                    noteOperation();
                });
                rowSuggestions.addView(chip);
            }
        }

        if (currentSuggestions.isEmpty()) {
            // Skip the bullet placeholder when clipboard chips are already showing.
            if (pendingClipChips.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText("·");
                empty.setTextSize(14f);
                empty.setTextColor(dim(textCol));
                empty.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
                lp.setMargins(dp(8), 0, 0, 0);
                empty.setLayoutParams(lp);
                rowSuggestions.addView(empty);
            }
            return;
        }

        for (int i = 0; i < currentSuggestions.size(); i++) {
            final String s = currentSuggestions.get(i);
            boolean best = (i == 0) && !s.equalsIgnoreCase(word);
            Button chip = ui.makeChip(s, best ? bestBg : chipBg,
                    best ? 0xFF000000 : textCol, best);
            chip.setOnClickListener(v -> {
                haptic(v);
                inputEngine.applySuggestion(s);
                suggestionEngine.learn(s);
                noteOperation();
            });
            rowSuggestions.addView(chip);
        }
    }

    // ─── Caps state machine ──────────────────────────────────────────────────
    private void onCapsTapped() {
        long now = System.currentTimeMillis();
        boolean isDoubleTap = (now - lastCapsTapMs) <= DOUBLE_TAP_MS;
        lastCapsTapMs = now;
        if (isDoubleTap) {
            capsState = CAPS_LOCKED;
        } else {
            switch (capsState) {
                case CAPS_OFF:    capsState = CAPS_SINGLE; break;
                case CAPS_SINGLE: capsState = CAPS_OFF;    break;
                case CAPS_LOCKED: capsState = CAPS_OFF;    break;
            }
        }
        buildQwertyRows();
        refreshCapsButtonStyle();
    }

    private void refreshCapsButtonStyle() {
        if (btnCaps == null) return;
        int accent = getAccentColor();
        int keyBg = getKeyBgColor();
        int radius = dp(getKeyRadiusDp());
        int strokeW = dp(getKeyStrokeWidthDp());
        int strokeC = getKeyStrokeColor();
        switch (capsState) {
            case CAPS_OFF:
                btnCaps.setImageResource(R.drawable.arrow_big_up);
                btnCaps.setColorFilter(getKeyTextColor());
                btnCaps.setBackground(ui.roundedFill(keyBg, radius, strokeW, strokeC));
                break;
            case CAPS_SINGLE:
                btnCaps.setImageResource(R.drawable.arrow_big_up);
                btnCaps.setColorFilter(accent);
                btnCaps.setBackground(ui.roundedFill(blend(keyBg, accent, 0.35f), radius, strokeW, strokeC));
                break;
            case CAPS_LOCKED:
                btnCaps.setImageResource(R.drawable.capslock);
                btnCaps.setColorFilter(0xFF000000);
                btnCaps.setBackground(ui.roundedFill(accent, radius, strokeW, strokeC));
                break;
        }
    }

    // ─── Backspace: hold-to-delete with acceleration ─────────────────────────
    /**
     * Attaches Gboard-style backspace gestures to {@link #btnBackspace}:
     *
     * <ul>
     *   <li><b>Hold to delete</b> — keep the finger on the button and
     *       characters disappear at an accelerating cadence (starts ~70ms
     *       between deletes, ramps to ~25ms). The loop stops immediately on
     *       release.</li>
     *   <li><b>Swipe left to delete words</b> — drag left from the button by
     *       ~28dp per word. Each threshold crossed deletes the word before the
     *       caret. Selection is honoured: if a selection is active, the first
     *       deletion clears the selection instead of the previous word.</li>
     * </ul>
     *
     * <p>The previous "double-tap to clear all" shortcut has been removed —
     * the new gestures cover the same intent without the accidental-clear
     * footgun the user reported.
     */
    private void attachBackspaceHoldHandler(View btn) {
        final long[] startTime = {0};
        final float[] downX = {0};
        final int[] wordsDeletedDuringSwipe = {0};
        final boolean[] swiping = {false};
        final int swipeUnitPx = dp(28);
        final Runnable[] loop = new Runnable[1];
        loop[0] = new Runnable() {
            @Override
            public void run() {
                if (swiping[0]) return; // swipe path takes over
                if (!performBackspaceDelete()) {
                    startTime[0] = 0;
                    return;
                }
                noteDeletion();
                long elapsed = System.currentTimeMillis() - startTime[0];
                long delay = elapsed < 600 ? 70 : (elapsed < 1500 ? 45 : 25);
                uiHandler.postDelayed(this, delay);
            }
        };
        btn.setOnTouchListener((v, ev) -> {
            int action = ev.getAction();
            if (action == MotionEvent.ACTION_DOWN) {
                haptic(v);
                // The main-keyboard backspace doubles as the emoji-search
                // backspace while the emoji panel is open: routing here keeps
                // the panel layout free of a redundant clear key and matches
                // the way letter taps already feed the search query.
                startTime[0] = System.currentTimeMillis();
                downX[0] = ev.getX();
                wordsDeletedDuringSwipe[0] = 0;
                swiping[0] = false;
                // Initial single-character delete fires immediately on tap.
                if (performBackspaceDelete()) noteDeletion();
                // Schedule the accelerating loop after a short pause so single
                // taps don't start the repeat behaviour.
                uiHandler.postDelayed(loop[0], 320);
                return true;
            } else if (action == MotionEvent.ACTION_MOVE) {
                float dx = ev.getX() - downX[0];
                if (dx < -swipeUnitPx) {
                    swiping[0] = true;
                    uiHandler.removeCallbacks(loop[0]);
                    int wantDeletions = (int) (-dx / swipeUnitPx);
                    while (wordsDeletedDuringSwipe[0] < wantDeletions) {
                        if (!performBackspaceDeleteWord()) break;
                        wordsDeletedDuringSwipe[0]++;
                        noteDeletion();
                    }
                }
                return true;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                    || action == MotionEvent.ACTION_OUTSIDE) {
                uiHandler.removeCallbacks(loop[0]);
                startTime[0] = 0;
                swiping[0] = false;
                return true;
            }
            return false;
        });
    }

    /**
     * Backspace dispatcher: routes a single delete to whichever target the
     * active panel claims. Emoji panel pops the search query (or, once empty,
     * defers to the host EditText so the user is never stuck); everything
     * else deletes from the host EditText directly.
     *
     * @return true if a delete actually occurred (so the caller can ramp the
     *         hold-to-delete cadence).
     */
    private boolean performBackspaceDelete() {
        if (panelMode == PanelMode.EMOJI && emojiEngine.isSearching()) {
            emojiEngine.popSearchChar();
            refreshEmojiPanel();
            return true;
        }
        return inputEngine.deleteOne();
    }

    /** Word-level analogue of {@link #performBackspaceDelete} for swipe-left. */
    private boolean performBackspaceDeleteWord() {
        if (panelMode == PanelMode.EMOJI && emojiEngine.isSearching()) {
            emojiEngine.clearSearch();
            refreshEmojiPanel();
            return true;
        }
        return inputEngine.deleteWord();
    }

    // ─── Input helpers ────────────────────────────────────────────────────────
    private void commitChar(String text) {
        // Emoji panel: route letter taps into the search query.
        if (panelMode == PanelMode.EMOJI && text != null && text.length() == 1) {
            char c = text.charAt(0);
            if (Character.isLetterOrDigit(c)) {
                emojiEngine.appendSearch(Character.toLowerCase(c));
                refreshEmojiPanel();
                return;
            }
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (activeMetaState != 0
                && (activeMetaState & ~KeyEvent.META_SHIFT_ON) != 0
                && text != null && text.length() == 1) {
            int kc = charToKeyCode(text);
            if (kc != 0) {
                inputEngine.sendKey(kc, activeMetaState);
                clearTransientModifiers();
                return;
            }
        }
        ic.commitText(text, 1);
        if (activeMetaState != 0) clearTransientModifiers();
    }

    /** Snippet / suggestion / clipboard insertion. Always replaces selection. */
    private void replaceSelectionOrInsert(String text) {
        inputEngine.commit(text);
        noteOperation();
    }

    private void insertSymbolWithAutoClose(String sym) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        boolean autoCloseEnabled = prefs.getBoolean("auto_close", true);
        if (autoCloseEnabled && AUTO_CLOSE.containsKey(sym) && !inputEngine.hasSelection()) {
            String close = AUTO_CLOSE.get(sym);
            ic.beginBatchEdit();
            ic.commitText(sym + close, 1);
            int newPos = inputEngine.cursorPosition() - close.length();
            if (newPos >= 0) ic.setSelection(newPos, newPos);
            ic.endBatchEdit();
        } else {
            ic.commitText(sym, 1);
        }
        noteOperation();
    }

    /** Space tap: commits a space and learns the previous word's frequency. */
    private void onSpace() {
        String word = inputEngine.currentWord();
        if (!TextUtils.isEmpty(word) && word.length() >= 2) suggestionEngine.learn(word);
        commitChar(" ");
        noteOperation();
    }

    private void sendArrow(int keyCode) {
        inputEngine.sendKey(keyCode, 0);
    }

    // ─── Undo / Redo ──────────────────────────────────────────────────────────
    private void noteOperation() {
        undoableOps++;
        redoableOps = 0;
        refreshHistoryButtonsState();
    }
    private void noteDeletion() { noteOperation(); }
    private void doUndo() {
        if (undoableOps <= 0) return;
        inputEngine.sendKey(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON);
        undoableOps--;
        redoableOps++;
        refreshHistoryButtonsState();
    }
    private void doRedo() {
        if (redoableOps <= 0) return;
        inputEngine.sendKey(KeyEvent.KEYCODE_Y, KeyEvent.META_CTRL_ON);
        redoableOps--;
        undoableOps++;
        refreshHistoryButtonsState();
    }
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

    private void refreshArrowButtonsState() {
        if (btnArrowLeft == null) return;
        int pos = inputEngine.cursorPosition();
        int total = inputEngine.totalLength();
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

    // ─── IME action / Enter button ───────────────────────────────────────────
    private void performEnterAction() {
        EditorInfo ei = getCurrentInputEditorInfo();
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) { commitChar("\n"); return; }
        int actionId = (ei != null) ? (ei.imeOptions & EditorInfo.IME_MASK_ACTION) : EditorInfo.IME_ACTION_UNSPECIFIED;
        boolean isMultiline = (ei != null) && ((ei.inputType & android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0);
        boolean noEnterAction = (ei != null) && ((ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0);
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
        String label = null;
        int action = (ei != null) ? (ei.imeOptions & EditorInfo.IME_MASK_ACTION) : 0;
        boolean noEnterAction = (ei != null) && ((ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0);
        boolean isMultiline = (ei != null) && ((ei.inputType & android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0);
        if (!noEnterAction && !isMultiline) {
            switch (action) {
                case EditorInfo.IME_ACTION_GO:       label = "Go";   break;
                case EditorInfo.IME_ACTION_NEXT:     label = "Next"; break;
                case EditorInfo.IME_ACTION_PREVIOUS: label = "Prev"; break;
                case EditorInfo.IME_ACTION_DONE:     label = "Done"; break;
                case EditorInfo.IME_ACTION_SEARCH:   label = "Search"; break;
                case EditorInfo.IME_ACTION_SEND:     label = "Send"; break;
                default: break;
            }
        }
        if (label == null) {
            // Default → use the corner-down-left vector icon.
            applyEnterIcon(getAccentColor());
        } else {
            btnEnter.setCompoundDrawables(null, null, null, null);
            btnEnter.setCompoundDrawablesRelative(null, null, null, null);
            btnEnter.setPadding(0, 0, 0, 0);
            btnEnter.setGravity(Gravity.CENTER);
            btnEnter.setText(label);
            btnEnter.setTextSize(label.length() > 3 ? 12f : 14f);
        }
    }

    // ─── Key preview popup ────────────────────────────────────────────────────
    /** Package-private so the emoji panel can re-use the same preview behaviour. */
    View.OnTouchListener keyPreviewToucher(final String label) {
        return previewToucher(label);
    }

    private View.OnTouchListener previewToucher(final String label) {
        return (v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) showKeyPreview(v, label);
            else if (ev.getAction() == MotionEvent.ACTION_UP
                    || ev.getAction() == MotionEvent.ACTION_CANCEL)
                uiHandler.postDelayed(this::dismissPreview, 90L);
            return false;
        };
    }

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
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), blend(getAccentColor(), 0xFFFFFFFF, 0.2f));
        tv.setBackground(bg);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        int width  = Math.max(anchor.getWidth() + dp(12), dp(48));
        int height = anchor.getHeight() + dp(28);
        PopupWindow pw = new PopupWindow(tv, width, height, false);
        pw.setOutsideTouchable(false);
        pw.setTouchable(false);
        pw.setFocusable(false);
        int yOffset = -anchor.getHeight() - height + dp(4);
        try {
            pw.showAsDropDown(anchor, -dp(6), yOffset);
            activePreview = pw;
        } catch (Exception ignored) { /* anchor may be detached */ }
    }

    private void dismissPreview() {
        if (activePreview != null) {
            try { activePreview.dismiss(); } catch (Exception ignored) {}
            activePreview = null;
        }
    }

    // ─── Settings / language popup ───────────────────────────────────────────
    private void showSettingsLanguagePopup(View anchor) {
        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(blend(getKeyBgColor(), 0xFFFFFFFF, 0.06f));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), blend(getAccentColor(), 0xFF000000, 0.5f));
        content.setBackground(bg);
        content.setPadding(dp(6), dp(6), dp(6), dp(6));

        TextView header = new TextView(this);
        header.setText("Language");
        header.setTextSize(11f);
        header.setTextColor(dim(getKeyTextColor()));
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setLetterSpacing(0.1f);
        header.setPadding(dp(12), dp(8), dp(12), dp(4));
        content.addView(header);

        final PopupWindow pw = new PopupWindow(content,
                dp(230), ViewGroup.LayoutParams.WRAP_CONTENT, true);
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
                        if (panelMode == PanelMode.KEYBOARD) {
                            buildSymbolRow();
                            buildSnippetRow();
                        }
                    }));
        }

        addPopupDivider(content);

        // Quick panel jumps.
        content.addView(makePopupRow("📋  Clipboard", getKeyTextColor(), pw,
                () -> switchPanel(PanelMode.CLIPBOARD)));
        content.addView(makePopupRow("☺  Emoji", getKeyTextColor(), pw,
                () -> switchPanel(PanelMode.EMOJI)));

        addPopupDivider(content);

        // Switch input method (system IME picker).
        content.addView(makePopupRow("🌐  Switch keyboard", getKeyTextColor(), pw, this::showImePicker));

        content.addView(makePopupRow("⚙  Settings…", getAccentColor(), pw, () -> {
            Intent it = new Intent(this, SettingsActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        }));

        try {
            pw.showAsDropDown(anchor, 0, -dp(8) - measure(content));
        } catch (Exception ignored) { /* anchor detached */ }
    }

    private void addPopupDivider(LinearLayout content) {
        View div = new View(this);
        div.setBackgroundColor(blend(getKeyTextColor(), 0xFF000000, 0.7f));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        divLp.setMargins(dp(8), dp(4), dp(8), dp(4));
        div.setLayoutParams(divLp);
        content.addView(div);
    }

    private View makePopupRow(String label, int color, PopupWindow owner, Runnable onClick) {
        TextView row = new TextView(this);
        row.setText(label);
        row.setTextSize(14f);
        row.setTextColor(color);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
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
        v.measure(View.MeasureSpec.makeMeasureSpec(dp(230), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.UNSPECIFIED);
        return v.getMeasuredHeight();
    }

    /** Triggers the system IME picker so the user can swap to another keyboard. */
    private void showImePicker() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showInputMethodPicker();
    }

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

    /** Returns built-in + user-defined snippets for the active language. */
    private List<String[]> effectiveSnippets() {
        ArrayList<String[]> out = new ArrayList<>();
        for (String[] s : loadCustomSnippets(currentLang)) out.add(s);
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

    private List<String[]> loadCustomSnippets(String lang) {
        ArrayList<String[]> out = new ArrayList<>();
        if (lang == null) return out;
        String raw = prefs.getString("custom_snip_" + lang, "");
        if (TextUtils.isEmpty(raw)) return out;
        for (String pair : raw.split("\u0002")) {
            if (pair.isEmpty()) continue;
            int sep = pair.indexOf('\u0001');
            if (sep <= 0 || sep >= pair.length() - 1) continue;
            out.add(new String[]{pair.substring(0, sep), pair.substring(sep + 1)});
        }
        return out;
    }

    // ─── Theme / sizing ──────────────────────────────────────────────────────
    private void applyTheme() {
        boolean amoled = prefs.getBoolean("amoled", false);
        boolean dark   = prefs.getBoolean("dark", true);
        int bgColor  = prefs.getInt("bg_color", dark ? 0xFF1A1A2E : 0xFFF0F0F0);
        if (amoled) bgColor = 0xFF000000;

        // Apply keyboard background — solid color, gradient, or custom image.
        // AMOLED forces solid black regardless of mode so OLED batteries win.
        applyKeyboardBackground(bgColor, amoled);

        // Restyle every key surface (bottom row + caps/backspace + suggestion
        // strip background) so that user-configurable radius / stroke / text
        // size flow through every visible button consistently.
        styleStaticKeys();
    }

    /**
     * Paints the keyboard root with whatever background the user has chosen.
     * Modes (stored under {@code kb_bg_mode}):
     * <ul>
     *   <li>{@code "solid"} (default) — single bg color from theme.</li>
     *   <li>{@code "gradient"} — vertical linear gradient between
     *       {@code kb_bg_gradient_start} and {@code kb_bg_gradient_end}.</li>
     *   <li>{@code "image"} — user-picked image at {@code kb_bg_image_uri}.
     *       Falls back to solid if the URI can't be loaded.</li>
     * </ul>
     * AMOLED short-circuits to pure black.
     */
    private void applyKeyboardBackground(int solidColor, boolean amoled) {
        if (keyboardView == null) return;
        if (amoled) {
            keyboardView.setBackgroundColor(0xFF000000);
            return;
        }
        String mode = prefs.getString("kb_bg_mode", "solid");
        if ("gradient".equals(mode)) {
            int start = prefs.getInt("kb_bg_gradient_start", solidColor);
            int end   = prefs.getInt("kb_bg_gradient_end",
                    blend(solidColor, 0xFF000000, 0.30f));
            GradientDrawable g = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{start, end});
            keyboardView.setBackground(g);
            return;
        }
        if ("image".equals(mode)) {
            String uriStr = prefs.getString("kb_bg_image_uri", "");
            if (!TextUtils.isEmpty(uriStr)) {
                try {
                    android.net.Uri uri = android.net.Uri.parse(uriStr);
                    java.io.InputStream is = getContentResolver().openInputStream(uri);
                    if (is != null) {
                        android.graphics.Bitmap bmp =
                                android.graphics.BitmapFactory.decodeStream(is);
                        is.close();
                        if (bmp != null) {
                            android.graphics.drawable.BitmapDrawable bd =
                                    new android.graphics.drawable.BitmapDrawable(
                                            getResources(), bmp);
                            // Scale to fill the keyboard like a "cover" crop.
                            bd.setGravity(Gravity.FILL);
                            keyboardView.setBackground(bd);
                            return;
                        }
                    }
                } catch (Exception ignored) {
                    // Fall through to solid bg.
                }
            }
        }
        keyboardView.setBackgroundColor(solidColor);
    }

    /**
     * Programmatically styles the always-visible buttons (suggestion strip
     * background, bottom action row keys) so that they share the user-chosen
     * corner radius, stroke, and text size with the dynamically-built keys.
     * Called after every theme apply.
     */
    private void styleStaticKeys() {
        int keyBg   = getKeyBgColor();
        int textCol = getKeyTextColor();
        int accent  = getAccentColor();
        int radius  = dp(getKeyRadiusDp());
        int strokeW = dp(getKeyStrokeWidthDp());
        int strokeC = getKeyStrokeColor();

        // Bottom action row: all flat-color buttons get the unified rounded
        // background so radius / stroke matches the QWERTY keys.
        styleActionButton(btnSettings,     keyBg, accent,  radius, strokeW, strokeC);
        styleActionButton(btnSymbolsPanel, panelMode == PanelMode.SYMBOLS ? accent : keyBg,
                panelMode == PanelMode.SYMBOLS ? 0xFF000000 : textCol,
                radius, strokeW, strokeC);
        styleActionButton(btnEmoji,
                panelMode == PanelMode.EMOJI ? accent : keyBg,
                panelMode == PanelMode.EMOJI ? 0xFF000000 : textCol,
                radius, strokeW, strokeC);
        styleActionButton(btnUndo,         keyBg, textCol, radius, strokeW, strokeC);
        styleActionButton(btnSpace,        keyBg, textCol, radius, strokeW, strokeC);
        styleActionButton(btnRedo,         keyBg, textCol, radius, strokeW, strokeC);
        styleActionButton(btnEnter,        keyBg, accent,  radius, strokeW, strokeC);
        styleArrowButton(btnArrowLeft,  keyBg, radius, strokeW, strokeC);
        styleArrowButton(btnArrowRight, keyBg, radius, strokeW, strokeC);
        styleArrowButton(btnArrowUp,    keyBg, radius, strokeW, strokeC);
        styleArrowButton(btnArrowDown,  keyBg, radius, strokeW, strokeC);

        // Caps + backspace inside the keyboard panel (only present when the
        // keyboard panel is bound).
        styleActionButton(btnBackspace, keyBg, textCol, radius, strokeW, strokeC);
        // Caps gets its own state-driven look in refreshCapsButtonStyle(); we
        // only refresh the corner radius / stroke here.
        if (btnCaps != null) {
            // Re-call refreshCapsButtonStyle so its rounded fill picks up the
            // current radius. The fill color matches the caps state.
            refreshCapsButtonStyle();
        }

        // Suggestion strip: subtle rounded surface so it visually pairs with
        // the keys instead of looking like a flat bar.
        if (rowSuggestions != null) {
            View parent = (View) rowSuggestions.getParent();
            if (parent != null) {
                parent.setBackground(ui.roundedFill(
                        blend(keyBg, 0xFF000000, 0.18f), radius, strokeW, strokeC));
            }
        }
    }

    /** Applies the unified rounded fill + text styling to an action button.
     *  Accepts either Button (text) or ImageButton (icon) and routes the
     *  foreground colour to setTextColor / setColorFilter accordingly. */
    private void styleActionButton(View b, int bg, int textCol,
                                   int radius, int strokeW, int strokeC) {
        if (b == null) return;
        b.setBackground(ui.roundedFill(bg, radius, strokeW, strokeC));
        if (b instanceof Button) {
            ((Button) b).setTextColor(textCol);
        } else if (b instanceof ImageButton) {
            ((ImageButton) b).setColorFilter(textCol);
        }
    }

    /** Renders the enter key with the corner_down_left icon tinted to {@code tintColor}. */
    private void applyEnterIcon(int tintColor) {
        if (btnEnter == null) return;
        android.graphics.drawable.Drawable d =
                getResources().getDrawable(R.drawable.corner_down_left);
        if (d == null) return;
        d = d.mutate();
        d.setColorFilter(tintColor, android.graphics.PorterDuff.Mode.SRC_IN);
        int sz = dp(20);
        d.setBounds(0, 0, sz, sz);
        btnEnter.setCompoundDrawables(d, null, null, null);
        btnEnter.setCompoundDrawablesRelative(null, null, null, null);
        btnEnter.setText("");
        btnEnter.setGravity(Gravity.CENTER);
    }

    private void styleArrowButton(ImageButton b, int bg,
                                  int radius, int strokeW, int strokeC) {
        if (b == null) return;
        b.setBackground(ui.roundedFill(bg, radius, strokeW, strokeC));
    }

    /** Returns the user-configured key-height multiplier, clamped to a sensible band. */
    private float getHeightScale() {
        float raw = prefs.getFloat("key_height_scale", 1.0f);
        if (raw < 0.7f) raw = 0.7f;
        if (raw > 1.6f) raw = 1.6f;
        return raw;
    }

    /**
     * Resizes every panel row whose height is meaningful for the keyboard
     * height. The XML still owns the relative proportions; this method just
     * scales each row's {@code layoutParams.height} by {@link #getHeightScale()}.
     */
    private void applyKeyboardHeight() {
        float scale = getHeightScale();
        scaleViewHeight(rowSuggestions, 42, scale);
        scaleViewHeight(pcKeysScroll, 38, scale);
        if (panelMode == PanelMode.KEYBOARD || panelMode == PanelMode.SYMBOLS) {
            // Keyboard rows
            scaleViewHeight(keyboardPanelView != null ? keyboardPanelView.findViewById(R.id.symbol_scroll) : null, 44, scale);
            scaleViewHeight(keyboardPanelView != null ? keyboardPanelView.findViewById(R.id.snippet_scroll) : null, 40, scale);
            scaleViewHeight(rowNumbers, 42, scale);
            scaleViewHeight(rowLetters1, 48, scale);
            scaleViewHeight(rowLetters2, 48, scale);
            scaleViewHeight(keyboardPanelView != null ? keyboardPanelView.findViewById(R.id.row_letters3_wrap) : null, 48, scale);
        }
        scaleViewHeight(keyboardView != null ? keyboardView.findViewById(R.id.row_nav) : null, 48, scale);
    }

    private void scaleViewHeight(View v, int baseDp, float scale) {
        if (v == null) return;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) return;
        lp.height = Math.round(dp(baseDp) * scale);
        v.setLayoutParams(lp);
    }

    // ─── PC keys row ─────────────────────────────────────────────────────────
    private void buildPcKeysRow() {
        if (rowPcKeys == null) return;
        modifierButtons.clear();
        rowPcKeys.removeAllViews();
        boolean show = prefs.getBoolean("show_pc_keys", false);
        if (pcKeysScroll != null) pcKeysScroll.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;

        for (final String[] entry : PC_KEYS) {
            final String label = entry[0];
            final String spec  = entry[1];
            Button btn = ui.makeKey(label, getKeyBgColor(), getKeyTextColor());
            btn.setTextSize(11f);
            int width = label.length() <= 2 ? dp(38) : dp(46);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.MATCH_PARENT);
            lp.setMargins(dp(2), dp(4), dp(2), dp(4));
            btn.setLayoutParams(lp);
            if (spec.startsWith("MOD:")) {
                final int metaBit = parseModifierBit(spec.substring(4));
                modifierButtons.put(metaBit, btn);
                btn.setOnClickListener(v -> { haptic(v); toggleModifier(metaBit); });
            } else if (spec.startsWith("KEY:")) {
                final int keyCode = Integer.parseInt(spec.substring(4));
                btn.setOnClickListener(v -> {
                    haptic(v);
                    inputEngine.sendKey(keyCode, activeMetaState);
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

    private void toggleModifier(int metaBit) {
        if (metaBit == 0) return;
        activeMetaState ^= metaBit;
        refreshModifierButtonStyles();
    }
    private void clearTransientModifiers() {
        if (activeMetaState == 0) return;
        activeMetaState = 0;
        refreshModifierButtonStyles();
    }
    private void refreshModifierButtonStyles() {
        int accent = getAccentColor();
        int keyBg  = getKeyBgColor();
        int textCol = getKeyTextColor();
        for (java.util.Map.Entry<Integer, Button> e : modifierButtons.entrySet()) {
            boolean on = (activeMetaState & e.getKey()) != 0;
            Button b = e.getValue();
            b.setBackground(ui.roundedFill(on ? accent : keyBg, dp(10)));
            b.setTextColor(on ? 0xFF000000 : textCol);
        }
    }

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

    // ─── Color / theme accessors used by every module ────────────────────────
    int getKeyBgColor() {
        // AMOLED override — every key renders pure black so the keyboard
        // visually merges with the (also-black) background and only the labels
        // and (transparent) strokes are visible. This is the OLED-saving look.
        if (prefs.getBoolean("amoled", false)) return 0xFF000000;
        boolean dark = prefs.getBoolean("dark", true);
        return prefs.getInt("key_color", dark ? 0xFF252545 : 0xFFFFFFFF);
    }
    int getKeyTextColor() {
        boolean dark = prefs.getBoolean("dark", true);
        return prefs.getInt("text_color", dark ? 0xFFE8E8FF : 0xFF222222);
    }
    int getAccentColor() {
        return prefs.getInt("accent_color", 0xFF00E5FF);
    }

    /** User-configurable corner radius for every keyboard surface (dp). */
    int getKeyRadiusDp() {
        int v = prefs.getInt("key_radius_dp", 12);
        if (v < 0) v = 0;
        if (v > 40) v = 40;
        return v;
    }
    /** User-configurable label text size for letter / symbol keys (sp). */
    float getKeyTextSizeSp() {
        int v = prefs.getInt("key_text_size_sp", 14);
        if (v < 8) v = 8;
        if (v > 28) v = 28;
        return (float) v;
    }
    /** Stroke width applied to every key surface (dp). 0 = no border. */
    int getKeyStrokeWidthDp() {
        int v = prefs.getInt("key_stroke_width_dp", 0);
        if (v < 0) v = 0;
        if (v > 6) v = 6;
        return v;
    }
    /**
     * Stroke color applied to every key surface. AMOLED forces a fully
     * transparent stroke regardless of user setting — see the user's request:
     * "amoled selected, whole keyboard show as pure black keys with stroke
     * transparent".
     */
    int getKeyStrokeColor() {
        if (prefs.getBoolean("amoled", false)) return 0x00000000;
        return prefs.getInt("key_stroke_color", 0x00000000);
    }

    // ─── Haptic / sound ──────────────────────────────────────────────────────
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
            try { audio.playSoundEffect(effect); } catch (Exception ignored2) {}
        }
    }

    // ─── Color helpers / utils ───────────────────────────────────────────────
    int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private int dim(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >>  8) & 0xFF;
        int b =  color        & 0xFF;
        return 0xFF000000 | (((r + 100) / 2) << 16) | (((g + 100) / 2) << 8) | ((b + 100) / 2);
    }

    static int blend(int base, int over, float t) {
        int br = (base >> 16) & 0xFF, bgC = (base >> 8) & 0xFF, bb = base & 0xFF;
        int or = (over >> 16) & 0xFF, og = (over >> 8) & 0xFF, ob = over & 0xFF;
        return 0xFF000000
                | ((int)(br + (or - br) * t) << 16)
                | ((int)(bgC + (og - bgC) * t) << 8)
                |  (int)(bb + (ob - bb) * t);
    }
}
