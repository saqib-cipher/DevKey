package com.codekeys.ime;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * SettingsActivity — preferences screen for CodeKeys.
 *
 * Layout lives in {@code res/layout/settings_activity.xml}. This activity wires
 * up runtime-themed widgets, the theme grid, and the custom language preset
 * editor.
 *
 * <p>The custom-language list is persisted as a single SharedPreferences string
 * key {@code custom_langs} (pipe-separated names) so the IME service can read
 * it without any extra schema knowledge.
 */
public class SettingsActivity extends AppCompatActivity {

    /** SharedPreferences key under which custom language names are stored. */
    private static final String PREF_CUSTOM_LANGS = "custom_langs";

    private SharedPreferences prefs;

    // Cached views from the inflated XML.
    private ScrollView root;
    private LinearLayout preferencesContainer;
    private LinearLayout themesContainer;
    private LinearLayout langButtonsRow;
    private LinearLayout customLangList;
    private LinearLayout snippetRefBox;
    private LinearLayout enableCard;
    private View titleSwatch;
    private TextView title, subtitle, footer;
    private Button btnEnableIme, btnPickIme, btnAddLang, btnReset;
    private EditText editNewLang;

    // ── Theme palette: { bgColor, keyColor, textColor, accentColor } ──────────
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
        "Dark Blue", "AMOLED Black", "Monokai", "Dracula",
        "Solarized Dark", "Deep Green", "VS Code Dark", "Light"
    };
    private static final boolean[] THEME_IS_DARK = {
        true, true, true, true, true, true, true, false
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("codekeys_prefs", MODE_PRIVATE);
        setContentView(R.layout.settings_activity);
        bindViews();
        applyTheme();
        wireListeners();
        renderPreferences();
        renderThemes();
        renderLanguages();
        renderCustomLanguages();
        renderSnippetReference();
    }

    // ─── View binding ─────────────────────────────────────────────────────────
    private void bindViews() {
        root                 = findViewById(R.id.settings_scroll);
        preferencesContainer = findViewById(R.id.preferences_container);
        themesContainer      = findViewById(R.id.themes_container);
        langButtonsRow       = findViewById(R.id.lang_buttons_row);
        customLangList       = findViewById(R.id.custom_lang_list);
        snippetRefBox        = findViewById(R.id.snippet_ref_box);
        enableCard           = findViewById(R.id.enable_card);
        titleSwatch          = findViewById(R.id.title_swatch);
        title                = findViewById(R.id.title);
        subtitle             = findViewById(R.id.subtitle);
        footer               = findViewById(R.id.footer);
        btnEnableIme         = findViewById(R.id.btn_enable_ime);
        btnPickIme           = findViewById(R.id.btn_pick_ime);
        btnAddLang           = findViewById(R.id.btn_add_lang);
        btnReset             = findViewById(R.id.btn_reset);
        editNewLang          = findViewById(R.id.edit_new_lang);
    }

    private void wireListeners() {
        btnEnableIme.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));

        btnPickIme.setOnClickListener(v -> {
            InputMethodManager imm =
                    (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });

        btnAddLang.setOnClickListener(v -> addCustomLanguageFromInput());

        btnReset.setOnClickListener(v -> {
            prefs.edit().clear().apply();
            Toast.makeText(this, "Settings reset.", Toast.LENGTH_SHORT).show();
            recreate();
        });
    }

    /** Applies the user's selected theme to the static layout views. */
    private void applyTheme() {
        int bg     = prefs.getInt("bg_color",     0xFF1A1A2E);
        int accent = prefs.getInt("accent_color", 0xFF00E5FF);
        int textCol = prefs.getInt("text_color",  0xFFE8E8FF);

        root.setBackgroundColor(bg);
        ((View) root.getChildAt(0)).setBackgroundColor(bg);
        titleSwatch.setBackgroundColor(accent);
        title.setTextColor(textCol);
        subtitle.setTextColor(dim(textCol));
        footer.setTextColor(dim(textCol));

        enableCard.setBackgroundColor(blend(bg, accent, 0.08f));
        btnEnableIme.setBackgroundColor(blend(bg, accent, 0.15f));
        btnEnableIme.setTextColor(accent);
        btnPickIme.setBackgroundColor(blend(bg, 0xFFFFFFFF, 0.05f));
        btnPickIme.setTextColor(textCol);

        snippetRefBox.setBackgroundColor(blend(bg, 0xFFFFFFFF, 0.03f));
    }

    // ─── Preferences (toggles) ────────────────────────────────────────────────
    private void renderPreferences() {
        preferencesContainer.removeAllViews();
        int textCol = prefs.getInt("text_color", 0xFFE8E8FF);
        int bg      = prefs.getInt("bg_color",   0xFF1A1A2E);
        int accent  = prefs.getInt("accent_color", 0xFF00E5FF);

        preferencesContainer.addView(buildToggle("Haptic Feedback",
                "Vibrate on each key press",
                "haptic", true, textCol, bg, accent));

        preferencesContainer.addView(buildToggle("Dark Mode",
                "Use dark keyboard background",
                "dark", true, textCol, bg, accent));

        preferencesContainer.addView(buildToggle("AMOLED Mode",
                "Pure black background (saves battery on OLED screens)",
                "amoled", false, textCol, bg, accent));

        preferencesContainer.addView(buildToggle("Auto-close Brackets",
                "Type ( → inserts ()  |  Type { → inserts {}  |  Type [ → inserts []",
                "auto_close", true, textCol, bg, accent));

        preferencesContainer.addView(buildToggle("Show Suggestions",
                "Display autocomplete and correction strip on top",
                "show_suggestions", true, textCol, bg, accent));

        preferencesContainer.addView(buildToggle("Key Sound",
                "Play system click sound on each key press",
                "key_sound", false, textCol, bg, accent));

        preferencesContainer.addView(buildToggle("Show PC Keys Row",
                "Adds Esc, Tab, Ctrl, Alt, Shift, Win, F1–F12, Home/End/PgUp/PgDn",
                "show_pc_keys", false, textCol, bg, accent));

        preferencesContainer.addView(buildKeyboardHeightSelector(textCol, bg, accent));
        preferencesContainer.addView(buildKeySoundVolumeSelector(textCol, bg, accent));
    }

    /**
     * Inline "Keyboard Size" selector — four preset buttons writing a float
     * {@code key_height_scale} preference consumed by the IME service.
     */
    private View buildKeyboardHeightSelector(int textCol, int bg, int accent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(blend(bg, 0xFFFFFFFF, 0.04f));
        card.setPadding(dp(16), dp(12), dp(16), dp(14));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, dp(2), 0, dp(2));
        card.setLayoutParams(clp);

        TextView label = new TextView(this);
        label.setText("Keyboard Size");
        label.setTextSize(15f);
        label.setTextColor(textCol);
        card.addView(label);

        TextView desc = new TextView(this);
        desc.setText("Scale the keyboard's row heights — useful on large or small screens.");
        desc.setTextSize(11f);
        desc.setTextColor(dim(textCol));
        desc.setPadding(0, 0, 0, dp(8));
        card.addView(desc);

        final String[] names    = { "Compact", "Standard", "Comfortable", "Large" };
        final float[]  scales   = { 0.85f,     1.0f,       1.15f,         1.30f  };

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        float current = prefs.getFloat("key_height_scale", 1.0f);
        for (int i = 0; i < names.length; i++) {
            final float scale = scales[i];
            boolean sel = Math.abs(current - scale) < 0.01f;
            Button b = new Button(this);
            b.setText(names[i]);
            b.setAllCaps(false);
            b.setTextSize(12f);
            b.setTextColor(sel ? accent : textCol);
            b.setBackgroundColor(sel ? blend(bg, accent, 0.22f) : blend(bg, 0xFFFFFFFF, 0.05f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            lp.setMargins(2, 0, 2, 0);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> {
                prefs.edit().putFloat("key_height_scale", scale).apply();
                renderPreferences();
            });
            row.addView(b);
        }
        card.addView(row);
        return card;
    }

    /**
     * Coarse 5-step volume selector for key-press sounds. Stored as an int
     * percent {@code key_sound_volume} (0–100). Hidden visually when sound is
     * off — but still rendered so the user can pre-set their preferred level.
     */
    private View buildKeySoundVolumeSelector(int textCol, int bg, int accent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(blend(bg, 0xFFFFFFFF, 0.04f));
        card.setPadding(dp(16), dp(12), dp(16), dp(14));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, dp(2), 0, dp(2));
        card.setLayoutParams(clp);

        TextView label = new TextView(this);
        label.setText("Key Sound Volume");
        label.setTextSize(15f);
        label.setTextColor(textCol);
        card.addView(label);

        TextView desc = new TextView(this);
        desc.setText("Loudness of the click. Has no effect when Key Sound is off.");
        desc.setTextSize(11f);
        desc.setTextColor(dim(textCol));
        desc.setPadding(0, 0, 0, dp(8));
        card.addView(desc);

        final String[] names = { "Off", "Low", "Med", "High", "Max" };
        final int[]    vols  = { 0,     25,    50,    75,     100  };

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        int current = prefs.getInt("key_sound_volume", 50);
        for (int i = 0; i < names.length; i++) {
            final int vol = vols[i];
            boolean sel = current == vol;
            Button b = new Button(this);
            b.setText(names[i]);
            b.setAllCaps(false);
            b.setTextSize(12f);
            b.setTextColor(sel ? accent : textCol);
            b.setBackgroundColor(sel ? blend(bg, accent, 0.22f) : blend(bg, 0xFFFFFFFF, 0.05f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            lp.setMargins(2, 0, 2, 0);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> {
                prefs.edit().putInt("key_sound_volume", vol).apply();
                renderPreferences();
            });
            row.addView(b);
        }
        card.addView(row);
        return card;
    }

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

    // ─── Themes ───────────────────────────────────────────────────────────────
    private void renderThemes() {
        themesContainer.removeAllViews();
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

            int[] swatches = { theme[0], theme[1], theme[2], theme[3] };
            for (int c : swatches) {
                View s = new View(this);
                s.setBackgroundColor(c);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(20), dp(20));
                slp.setMargins(0, 0, dp(4), 0);
                s.setLayoutParams(slp);
                row.addView(s);
            }

            TextView nameView = new TextView(this);
            nameView.setText(name);
            nameView.setTextSize(13f);
            nameView.setTextColor(theme[2]);
            nameView.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            nameView.setPadding(dp(10), 0, 0, 0);
            row.addView(nameView);

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

            themesContainer.addView(row);
        }
    }

    // ─── Language buttons (built-ins + custom) ────────────────────────────────
    private void renderLanguages() {
        langButtonsRow.removeAllViews();
        int textCol = prefs.getInt("text_color", 0xFFE8E8FF);
        int bg      = prefs.getInt("bg_color",   0xFF1A1A2E);
        int accent  = prefs.getInt("accent_color", 0xFF00E5FF);

        String current = prefs.getString("lang", "GENERAL");
        for (final String lang : getAllLanguages()) {
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
            langButtonsRow.addView(btn);
        }
    }

    // ─── Custom language presets editor ───────────────────────────────────────
    private void renderCustomLanguages() {
        customLangList.removeAllViews();
        int textCol = prefs.getInt("text_color", 0xFFE8E8FF);
        int bg      = prefs.getInt("bg_color",   0xFF1A1A2E);
        int accent  = prefs.getInt("accent_color", 0xFF00E5FF);

        editNewLang.setTextColor(textCol);
        editNewLang.setHintTextColor(dim(textCol));

        List<String> custom = getCustomLanguages();
        if (custom.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("No custom presets yet.");
            hint.setTextSize(11f);
            hint.setTextColor(dim(textCol));
            customLangList.addView(hint);
            return;
        }

        for (final String name : custom) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundColor(blend(bg, 0xFFFFFFFF, 0.05f));
            row.setPadding(dp(12), dp(10), dp(8), dp(10));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, 0, 0, dp(2));
            row.setLayoutParams(rlp);

            TextView nameView = new TextView(this);
            nameView.setText(name);
            nameView.setTextSize(13f);
            nameView.setTextColor(textCol);
            nameView.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(nameView);

            Button rm = new Button(this);
            rm.setText("Remove");
            rm.setAllCaps(false);
            rm.setTextSize(11f);
            rm.setTextColor(0xFFFF6666);
            rm.setBackgroundColor(0x22FF0000);
            rm.setOnClickListener(v -> {
                removeCustomLanguage(name);
                renderCustomLanguages();
                renderLanguages();
            });
            row.addView(rm);

            customLangList.addView(row);
        }
    }

    private void addCustomLanguageFromInput() {
        if (editNewLang == null) return;
        String name = editNewLang.getText().toString().trim();
        if (TextUtils.isEmpty(name)) {
            Toast.makeText(this, "Enter a name first.", Toast.LENGTH_SHORT).show();
            return;
        }
        // Sanitize: uppercase + strip pipe/whitespace.
        name = name.toUpperCase().replace("|", "").trim();
        if (Arrays.asList("GENERAL", "C", "JAVA", "PYTHON", "JS").contains(name)) {
            Toast.makeText(this, "That name is built-in.", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> existing = getCustomLanguages();
        if (existing.contains(name)) {
            Toast.makeText(this, "Already added.", Toast.LENGTH_SHORT).show();
            return;
        }
        existing.add(name);
        prefs.edit().putString(PREF_CUSTOM_LANGS, TextUtils.join("|", existing)).apply();
        editNewLang.setText("");
        renderCustomLanguages();
        renderLanguages();
        Toast.makeText(this, "Added " + name, Toast.LENGTH_SHORT).show();
    }

    private void removeCustomLanguage(String name) {
        List<String> existing = getCustomLanguages();
        existing.remove(name);
        prefs.edit().putString(PREF_CUSTOM_LANGS, TextUtils.join("|", existing)).apply();

        // If the removed language was the active one, fall back to GENERAL.
        if (name.equals(prefs.getString("lang", "GENERAL"))) {
            prefs.edit().putString("lang", "GENERAL").apply();
        }
    }

    private List<String> getCustomLanguages() {
        ArrayList<String> out = new ArrayList<>();
        String raw = prefs.getString(PREF_CUSTOM_LANGS, "");
        if (TextUtils.isEmpty(raw)) return out;
        for (String s : raw.split("\\|")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private List<String> getAllLanguages() {
        ArrayList<String> out = new ArrayList<>();
        out.addAll(Arrays.asList("GENERAL", "C", "JAVA", "PYTHON", "JS"));
        for (String c : getCustomLanguages()) if (!out.contains(c)) out.add(c);
        return out;
    }

    // ─── Snippet reference ────────────────────────────────────────────────────
    private void renderSnippetReference() {
        snippetRefBox.removeAllViews();
        int textCol = prefs.getInt("text_color", 0xFFE8E8FF);
        int accent  = prefs.getInt("accent_color", 0xFF00E5FF);

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

            snippetRefBox.addView(row);
        }

        TextView hint = new TextView(this);
        hint.setText("Tap snippet buttons in the keyboard's snippet row to insert templates. "
                + "Switch language from the keyboard's ⚙ menu.");
        hint.setTextSize(11f);
        hint.setTextColor(dim(textCol));
        hint.setPadding(0, dp(8), 0, 0);
        snippetRefBox.addView(hint);
    }

    // ─── Color helpers ────────────────────────────────────────────────────────
    private int dp(int val) {
        return Math.round(val * getResources().getDisplayMetrics().density);
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
