package com.codekeys.ime;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private SharedPreferences prefs;

    private static final int[][] THEMES = {
        { 0xFF1A1A2E, 0xFF252545, 0xFFE8E8FF, 0xFF00E5FF },
        { 0xFF000000, 0xFF111111, 0xFFFFFFFF, 0xFF00FF88 },
        { 0xFF272822, 0xFF3E3D32, 0xFFF8F8F2, 0xFFE6DB74 },
        { 0xFF282A36, 0xFF44475A, 0xFFF8F8F2, 0xFFBD93F9 },
        { 0xFF002B36, 0xFF073642, 0xFF839496, 0xFF2AA198 },
        { 0xFF0D1F0D, 0xFF1A3A1A, 0xFFCCFFCC, 0xFF44FF44 },
        { 0xFF1E1E1E, 0xFF2D2D2D, 0xFFD4D4D4, 0xFF569CD6 },
        { 0xFFF5F5F5, 0xFFFFFFFF, 0xFF222222, 0xFF1565C0 },
    };

    private static final String[] THEME_NAMES = {
        "Dark Blue", "AMOLED Black", "Monokai",
        "Dracula", "Solarized Dark", "Deep Green",
        "VS Code Dark", "Light"
    };

    private static final boolean[] THEME_IS_DARK = {
        true, true, true, true, true, true, true, false
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("codekeys_prefs", MODE_PRIVATE);
        setContentView(buildUI());
    }

    private ScrollView buildUI() {
        int bg      = prefs.getInt("bg_color",     0xFF1A1A2E);
        int accent  = prefs.getInt("accent_color", 0xFF00E5FF);
        int textCol = prefs.getInt("text_color",   0xFFE8E8FF);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(bg);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setPadding(dp(20), dp(20), dp(20), dp(40));

        // ── Title bar ──────────────────────────────────────────────────────
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setPadding(0, 0, 0, dp(24));

        View swatch = new View(this);
        swatch.setBackgroundColor(accent);
        LinearLayout.LayoutParams swatchLp = new LinearLayout.LayoutParams(dp(6), dp(36));
        swatchLp.setMargins(0, 0, dp(14), 0);
        swatch.setLayoutParams(swatchLp);
        titleBar.addView(swatch);

        LinearLayout titleText = new LinearLayout(this);
        titleText.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("CodeKeys");
        title.setTextSize(26f);
        title.setTextColor(textCol);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleText.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Coding Keyboard Settings");
        subtitle.setTextSize(12f);
        subtitle.setTextColor(dim(textCol));
        titleText.addView(subtitle);

        titleBar.addView(titleText);
        root.addView(titleBar);

        // ── Enable IME card ────────────────────────────────────────────────
        root.addView(buildEnableCard(accent, textCol, bg));

        // ── Toggles ────────────────────────────────────────────────────────
        addHeader(root, "PREFERENCES", textCol);

        root.addView(buildToggle("Haptic Feedback",
            "Vibrate on each key press",
            "haptic", true, textCol, bg, accent));

        root.addView(buildToggle("Dark Mode",
            "Use dark keyboard background",
            "dark", true, textCol, bg, accent));

        root.addView(buildToggle("AMOLED Mode",
            "Pure black background (saves battery on OLED screens)",
            "amoled", false, textCol, bg, accent));

        root.addView(buildToggle("Auto-close Brackets",
            "Type ( → inserts ()  |  Type { → inserts {}  |  Type [ → inserts []",
            "auto_close", true, textCol, bg, accent));

        // ── Themes ────────────────────────────────────────────────────────
        addHeader(root, "THEMES", textCol);
        root.addView(buildThemeGrid(bg, textCol, accent));

        // ── Language ──────────────────────────────────────────────────────
        addHeader(root, "DEFAULT LANGUAGE MODE", textCol);
        root.addView(buildLangRow(textCol, bg, accent));

        // ── Snippets reference ────────────────────────────────────────────
        addHeader(root, "SNIPPET REFERENCE", textCol);
        root.addView(buildSnippetRef(textCol, bg, accent));

        // ── Reset ─────────────────────────────────────────────────────────
        addHeader(root, "RESET", textCol);
        root.addView(buildResetBtn());

        // ── Footer ────────────────────────────────────────────────────────
        root.addView(buildFooter(textCol));

        sv.addView(root);
        return sv;
    }

    // ─── Enable IME Card ──────────────────────────────────────────────────────
    private View buildEnableCard(int accent, int textCol, int bg) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(blend(bg, accent, 0.08f));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(20));
        card.setLayoutParams(lp);

        Button step1 = new Button(this);
        step1.setText("① Enable CodeKeys in System Settings  →");
        step1.setAllCaps(false);
        step1.setTextSize(13f);
        step1.setTextColor(accent);
        step1.setBackgroundColor(blend(bg, accent, 0.15f));
        LinearLayout.LayoutParams b1lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        b1lp.setMargins(0, 0, 0, dp(8));
        step1.setLayoutParams(b1lp);
        step1.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));

        Button step2 = new Button(this);
        step2.setText("② Switch to CodeKeys (open IME picker)  →");
        step2.setAllCaps(false);
        step2.setTextSize(13f);
        step2.setTextColor(textCol);
        step2.setBackgroundColor(blend(bg, 0xFFFFFFFF, 0.05f));
        step2.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        step2.setOnClickListener(v -> {
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });

        TextView hint = new TextView(this);
        hint.setText("Tap ① to enable, then ② to activate CodeKeys as your keyboard.");
        hint.setTextSize(11f);
        hint.setTextColor(dim(textCol));
        hint.setPadding(0, dp(10), 0, 0);

        card.addView(step1);
        card.addView(step2);
        card.addView(hint);
        return card;
    }

    // ─── Toggle Row ───────────────────────────────────────────────────────────
    private View buildToggle(String label, String desc, final String key, boolean def,
                             int textCol, int bg, int accent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(blend(bg, 0xFFFFFFFF, 0.04f));
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(2));
        row.setLayoutParams(lp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView lv = new TextView(this);
        lv.setText(label);
        lv.setTextSize(15f);
        lv.setTextColor(textCol);
        texts.addView(lv);

        TextView dv = new TextView(this);
        dv.setText(desc);
        dv.setTextSize(11f);
        dv.setTextColor(dim(textCol));
        texts.addView(dv);

        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean(key, def));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            sw.getThumbDrawable().setTint(accent);
        }
        sw.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean(key, checked).apply());

        row.addView(texts);
        row.addView(sw);
        return row;
    }

    // ─── Theme Grid ───────────────────────────────────────────────────────────
    private View buildThemeGrid(int bg, int textCol, int accent) {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        int currentBg = prefs.getInt("bg_color", 0xFF1A1A2E);

        for (int i = 0; i < THEMES.length; i++) {
            final int[] theme = THEMES[i];
            final String name = THEME_NAMES[i];
            final boolean isDark = THEME_IS_DARK[i];
            boolean active = (currentBg == theme[0]);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundColor(theme[0]);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, 0, 0, dp(3));
            row.setLayoutParams(rlp);

            // Four color swatches
            int[] swatches = { theme[0], theme[1], theme[2], theme[3] };
            for (int c : swatches) {
                View s = new View(this);
                s.setBackgroundColor(c);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(20), dp(20));
                slp.setMargins(0, 0, dp(4), 0);
                s.setLayoutParams(slp);
                row.addView(s);
            }

            // Theme name
            TextView nameView = new TextView(this);
            nameView.setText(name);
            nameView.setTextSize(13f);
            nameView.setTextColor(theme[2]);
            nameView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            nameView.setPadding(dp(10), 0, 0, 0);
            row.addView(nameView);

            // Active check
            if (active) {
                TextView check = new TextView(this);
                check.setText("✓ active");
                check.setTextSize(11f);
                check.setTextColor(theme[3]);
                row.addView(check);
            }

            row.setOnClickListener(v -> {
                prefs.edit()
                    .putInt("bg_color",     theme[0])
                    .putInt("key_color",    theme[1])
                    .putInt("text_color",   theme[2])
                    .putInt("accent_color", theme[3])
                    .putBoolean("dark",  isDark)
                    .putBoolean("amoled", theme[0] == 0xFF000000)
                    .apply();
                Toast.makeText(this,
                    name + " applied — restart keyboard to see changes",
                    Toast.LENGTH_SHORT).show();
                recreate();
            });

            grid.addView(row);
        }
        return grid;
    }

    // ─── Language Row ─────────────────────────────────────────────────────────
    private View buildLangRow(int textCol, int bg, int accent) {
        String[] langs = { "GENERAL", "C", "JAVA", "PYTHON", "JS" };
        String current = prefs.getString("lang", "GENERAL");

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, 0, 0, dp(4));
        row.setLayoutParams(rlp);

        for (final String lang : langs) {
            boolean sel = lang.equals(current);
            Button btn = new Button(this);
            btn.setText(lang);
            btn.setAllCaps(false);
            btn.setTextSize(12f);
            btn.setTextColor(sel ? accent : textCol);
            btn.setBackgroundColor(sel ? blend(bg, accent, 0.22f) : blend(bg, 0xFFFFFFFF, 0.05f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            lp.setMargins(2, 0, 2, 0);
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> {
                prefs.edit().putString("lang", lang).apply();
                Toast.makeText(this, "Default: " + lang, Toast.LENGTH_SHORT).show();
                recreate();
            });
            row.addView(btn);
        }
        return row;
    }

    // ─── Snippet Reference ────────────────────────────────────────────────────
    private View buildSnippetRef(int textCol, int bg, int accent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(blend(bg, 0xFFFFFFFF, 0.03f));
        box.setPadding(dp(14), dp(14), dp(14), dp(14));

        String[][] rows = {
            { "GENERAL", "tab  todo  fixme  note  url" },
            { "C",       "if  for  while  fn  main  inc  pf  sf" },
            { "JAVA",    "if  for  forea  while  class  fn  sys  try" },
            { "PYTHON",  "if  for  forin  while  def  class  print  imp" },
            { "JS",      "if  for  forea  fn  arrow  const  log  prom" },
        };

        for (String[] r : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, 0, 0, dp(8));
            row.setLayoutParams(rlp);

            TextView tag = new TextView(this);
            tag.setText(r[0]);
            tag.setTextSize(11f);
            tag.setTextColor(accent);
            tag.setTypeface(Typeface.MONOSPACE);
            tag.setLayoutParams(new LinearLayout.LayoutParams(dp(64),
                LinearLayout.LayoutParams.WRAP_CONTENT));
            row.addView(tag);

            TextView snips = new TextView(this);
            snips.setText(r[1]);
            snips.setTextSize(11f);
            snips.setTextColor(dim(textCol));
            snips.setTypeface(Typeface.MONOSPACE);
            row.addView(snips);

            box.addView(row);
        }

        TextView hint = new TextView(this);
        hint.setText("Tap snippet buttons in the keyboard's scrollable snippet row to insert full templates.\nSwitch language by tapping GEN / C / JAVA / PYTHON / JS label on the keyboard.");
        hint.setTextSize(11f);
        hint.setTextColor(dim(textCol));
        hint.setPadding(0, dp(8), 0, 0);
        box.addView(hint);

        return box;
    }

    // ─── Reset Button ─────────────────────────────────────────────────────────
    private View buildResetBtn() {
        Button btn = new Button(this);
        btn.setText("Reset All Settings to Default");
        btn.setAllCaps(false);
        btn.setTextSize(14f);
        btn.setTextColor(0xFFFF6666);
        btn.setBackgroundColor(0x22FF0000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        lp.setMargins(0, 0, 0, dp(4));
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> {
            prefs.edit().clear().apply();
            Toast.makeText(this, "Settings reset.", Toast.LENGTH_SHORT).show();
            recreate();
        });
        return btn;
    }

    // ─── Footer ───────────────────────────────────────────────────────────────
    private View buildFooter(int textCol) {
        TextView tv = new TextView(this);
        tv.setText("CodeKeys v1.0  •  Built for developers");
        tv.setTextSize(11f);
        tv.setTextColor(dim(textCol));
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(24), 0, 0);
        tv.setLayoutParams(lp);
        return tv;
    }

    // ─── Section Header ───────────────────────────────────────────────────────
    private void addHeader(LinearLayout parent, String text, int textCol) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(10f);
        tv.setTextColor(dim(textCol));
        tv.setLetterSpacing(0.15f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(22), 0, dp(6));
        tv.setLayoutParams(lp);
        parent.addView(tv);
    }

    // ─── Color Helpers ────────────────────────────────────────────────────────
    private int dp(int val) {
        return Math.round(val * getResources().getDisplayMetrics().density);
    }

    /** Mutes a color toward gray for secondary text. */
    private int dim(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >>  8) & 0xFF;
        int b =  color        & 0xFF;
        return 0xFF000000 | (((r + 100) / 2) << 16) | (((g + 100) / 2) << 8) | ((b + 100) / 2);
    }

    /** Linear-interpolate between two ARGB colors. t=0 → base, t=1 → over. */
    private int blend(int base, int over, float t) {
        int br = (base >> 16) & 0xFF, bg = (base >> 8) & 0xFF, bb = base & 0xFF;
        int or = (over >> 16) & 0xFF, og = (over >> 8) & 0xFF, ob = over & 0xFF;
        return 0xFF000000
            | ((int)(br + (or - br) * t) << 16)
            | ((int)(bg + (og - bg) * t) << 8)
            |  (int)(bb + (ob - bb) * t);
    }
}
