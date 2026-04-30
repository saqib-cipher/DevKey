package com.codekeys.ime;

import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public class CodeKeysIME extends InputMethodService {

    // ─── State ────────────────────────────────────────────────────────────────
    private boolean isCaps = false;
    private boolean isSymbolMode = false;
    private String currentLang = "GENERAL"; // GENERAL | C | JAVA | PYTHON | JS
    private SharedPreferences prefs;
    private Vibrator vibrator;

    // Clipboard
    private final List<String> clipboard = new ArrayList<>();

    // Views
    private View keyboardView;
    private LinearLayout rowLetters1, rowLetters2, rowLetters3;
    private LinearLayout rowSymbols, rowSnippets, rowNav;
    private TextView langLabel;

    // ─── Language Snippets ────────────────────────────────────────────────────
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

    // ─── Symbol Rows per Language ─────────────────────────────────────────────
    private static final HashMap<String, String[]> LANG_SYMBOLS = new HashMap<>();
    static {
        String[] base = {"{","}","(",")","[","]","<",">",";",":","\"","'","`","|","\\","/","=","+","-","*","&","%","$","#","@","!"};
        LANG_SYMBOLS.put("GENERAL", base);
        LANG_SYMBOLS.put("C",    new String[]{"{","}","(",")","[","]","*","&",";","\"","'","#","<",">","=","+","-","/","%","!","~","^","|","\\","?",","});
        LANG_SYMBOLS.put("JAVA",  new String[]{"{","}","(",")","[","]",";",".",",","\"","'","@","=","+","-","*","/","!","?","<",">","&","|","^","~","%"});
        LANG_SYMBOLS.put("PYTHON",new String[]{":","(",")","{","}","[","]","=","\"","'","#","*","+","-","/","\\",".","@","%","^","&","|","~","<",">","!"});
        LANG_SYMBOLS.put("JS",   new String[]{"{","}","(",")","[","]",";",".","\"","'","`","=","=>","+","-","*","/","!","?","&&","||",":",",","@","#","%"});
    }

    // ─── Auto-close pairs ────────────────────────────────────────────────────
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
    }

    // ─── View Init ────────────────────────────────────────────────────────────
    private void initViews() {
        rowLetters1 = keyboardView.findViewById(R.id.row_letters1);
        rowLetters2 = keyboardView.findViewById(R.id.row_letters2);
        rowLetters3 = keyboardView.findViewById(R.id.row_letters3);
        rowSymbols  = keyboardView.findViewById(R.id.row_symbols);
        rowSnippets = keyboardView.findViewById(R.id.row_snippets);
        rowNav      = keyboardView.findViewById(R.id.row_nav);
        langLabel   = keyboardView.findViewById(R.id.lang_label);

        // Nav buttons
        setupNavButtons();

        // Lang switcher
        langLabel.setText(currentLang);
        langLabel.setOnClickListener(v -> cycleLang());
    }

    // ─── Build Dynamic Rows ───────────────────────────────────────────────────
    private void buildKeyboardRows() {
        buildQwertyRows();
        buildSymbolRow();
        buildSnippetRow();
    }

    private void buildQwertyRows() {
        String[] row1 = {"q","w","e","r","t","y","u","i","o","p"};
        String[] row2 = {"a","s","d","f","g","h","j","k","l"};
        String[] row3 = {"z","x","c","v","b","n","m"};

        rowLetters1.removeAllViews();
        rowLetters2.removeAllViews();
        rowLetters3.removeAllViews();

        for (String k : row1) addLetterKey(rowLetters1, k);
        for (String k : row2) addLetterKey(rowLetters2, k);
        for (String k : row3) addLetterKey(rowLetters3, k);
    }

    private void addLetterKey(LinearLayout parent, final String letter) {
        Button btn = makeKey(isCaps ? letter.toUpperCase() : letter);
        btn.setOnClickListener(v -> {
            haptic(v);
            String ch = isCaps ? letter.toUpperCase() : letter;
            commitText(ch);
        });
        btn.setOnLongClickListener(v -> {
            haptic(v);
            String ch = isCaps ? letter.toLowerCase() : letter.toUpperCase();
            commitText(ch);
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dpToPx(48), 1f);
        lp.setMargins(2,2,2,2);
        btn.setLayoutParams(lp);
        parent.addView(btn);
    }

    private void buildSymbolRow() {
        String[] symbols = LANG_SYMBOLS.getOrDefault(currentLang, LANG_SYMBOLS.get("GENERAL"));
        rowSymbols.removeAllViews();
        if (symbols == null) return;
        for (final String sym : symbols) {
            Button btn = makeKey(sym);
            btn.setTextSize(12f);
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
            lp.setMargins(2,2,2,2);
            btn.setLayoutParams(lp);
            rowSymbols.addView(btn);
        }
    }

    private void buildSnippetRow() {
        String[][] snippets = LANG_SNIPPETS.getOrDefault(currentLang, LANG_SNIPPETS.get("GENERAL"));
        rowSnippets.removeAllViews();
        if (snippets == null) return;
        for (final String[] snippet : snippets) {
            Button btn = makeKey(snippet[0]);
            btn.setTextSize(11f);
            int accentColor = getAccentColor();
            btn.setTextColor(accentColor);
            btn.setOnClickListener(v -> {
                haptic(v);
                commitText(snippet[1]);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dpToPx(56), dpToPx(38));
            lp.setMargins(2,2,2,2);
            btn.setLayoutParams(lp);
            rowSnippets.addView(btn);
        }
    }

    // ─── Nav Row ──────────────────────────────────────────────────────────────
    private void setupNavButtons() {
        // Caps
        Button btnCaps = keyboardView.findViewById(R.id.btn_caps);
        if (btnCaps != null) btnCaps.setOnClickListener(v -> { haptic(v); toggleCaps(); });

        // Backspace
        Button btnBack = keyboardView.findViewById(R.id.btn_backspace);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> { haptic(v); deleteChar(); });
            btnBack.setOnLongClickListener(v -> { deleteWord(); return true; });
        }

        // Space
        Button btnSpace = keyboardView.findViewById(R.id.btn_space);
        if (btnSpace != null) btnSpace.setOnClickListener(v -> { haptic(v); commitText(" "); });

        // Enter
        Button btnEnter = keyboardView.findViewById(R.id.btn_enter);
        if (btnEnter != null) btnEnter.setOnClickListener(v -> { haptic(v); commitText("\n"); });

        // Tab
        Button btnTab = keyboardView.findViewById(R.id.btn_tab);
        if (btnTab != null) btnTab.setOnClickListener(v -> { haptic(v); commitText("\t"); });

        // Arrows
        ImageButton btnLeft  = keyboardView.findViewById(R.id.btn_arrow_left);
        ImageButton btnRight = keyboardView.findViewById(R.id.btn_arrow_right);
        ImageButton btnUp    = keyboardView.findViewById(R.id.btn_arrow_up);
        ImageButton btnDown  = keyboardView.findViewById(R.id.btn_arrow_down);
        if (btnLeft != null)  btnLeft.setOnClickListener(v -> sendArrow(KeyEvent.KEYCODE_DPAD_LEFT));
        if (btnRight != null) btnRight.setOnClickListener(v -> sendArrow(KeyEvent.KEYCODE_DPAD_RIGHT));
        if (btnUp != null)    btnUp.setOnClickListener(v -> sendArrow(KeyEvent.KEYCODE_DPAD_UP));
        if (btnDown != null)  btnDown.setOnClickListener(v -> sendArrow(KeyEvent.KEYCODE_DPAD_DOWN));

        // Undo / Redo
        Button btnUndo = keyboardView.findViewById(R.id.btn_undo);
        Button btnRedo = keyboardView.findViewById(R.id.btn_redo);
        if (btnUndo != null) btnUndo.setOnClickListener(v -> sendUndoRedo(true));
        if (btnRedo != null) btnRedo.setOnClickListener(v -> sendUndoRedo(false));

        // Numbers row (0-9)
        String[] nums = {"1","2","3","4","5","6","7","8","9","0"};
        LinearLayout rowNums = keyboardView.findViewById(R.id.row_numbers);
        if (rowNums != null) {
            for (final String n : nums) {
                Button btn = makeKey(n);
                btn.setOnClickListener(v -> { haptic(v); commitText(n); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dpToPx(42), 1f);
                lp.setMargins(2,2,2,2);
                btn.setLayoutParams(lp);
                rowNums.addView(btn);
            }
        }
    }

    // ─── Input Helpers ────────────────────────────────────────────────────────
    private void commitText(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(text, 1);
    }

    private void insertSymbolWithAutoClose(String sym) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (AUTO_CLOSE.containsKey(sym)) {
            String close = AUTO_CLOSE.get(sym);
            ic.commitText(sym + close, 1);
            // Move cursor back inside
            ic.deleteSurroundingText(0, 0);
            // Set selection to middle
            CharSequence textBeforeCursor = ic.getTextBeforeCursor(1000, 0);
            if (textBeforeCursor != null) {
                int pos = textBeforeCursor.length();
                ic.setSelection(pos, pos);
            }
        } else {
            ic.commitText(sym, 1);
        }
    }

    private void deleteChar() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.deleteSurroundingText(1, 0);
    }

    private void deleteWord() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence text = ic.getTextBeforeCursor(50, 0);
        if (TextUtils.isEmpty(text)) return;
        int deleteCount = 0;
        int i = text.length() - 1;
        // skip trailing spaces
        while (i >= 0 && text.charAt(i) == ' ') { i--; deleteCount++; }
        // delete word
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

    // ─── Caps & Lang ─────────────────────────────────────────────────────────
    private void toggleCaps() {
        isCaps = !isCaps;
        buildQwertyRows();
        Button btnCaps = keyboardView.findViewById(R.id.btn_caps);
        if (btnCaps != null) {
            int bg = isCaps ? getAccentColor() : getKeyBgColor();
            btnCaps.setBackgroundColor(bg);
        }
    }

    private void cycleLang() {
        String[] langs = {"GENERAL","C","JAVA","PYTHON","JS"};
        int idx = Arrays.asList(langs).indexOf(currentLang);
        currentLang = langs[(idx + 1) % langs.length];
        prefs.edit().putString("lang", currentLang).apply();
        langLabel.setText(currentLang);
        buildSymbolRow();
        buildSnippetRow();
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
        int keyColor = prefs.getInt("key_color", dark ? 0xFF252545 : 0xFFFFFFFF);

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
        btn.setBackgroundColor(getKeyBgColor());
        btn.setPadding(2, 2, 2, 2);
        btn.setAllCaps(false);
        return btn;
    }

    // ─── Haptic ───────────────────────────────────────────────────────────────
    private void haptic(View v) {
        if (!prefs.getBoolean("haptic", true)) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (vibrator != null)
                vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    // ─── Utils ────────────────────────────────────────────────────────────────
    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
