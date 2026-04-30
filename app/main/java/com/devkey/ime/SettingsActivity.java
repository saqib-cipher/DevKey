package com.codekeys.ime;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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

import android.app.AlertDialog;
import android.text.InputType;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
    private Button btnInfo, btnExport, btnImport, btnClearPreview;
    private EditText editNewLang, editPreview;
    private LinearLayout previewCard, backupCard;

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
        btnInfo              = findViewById(R.id.btn_info);
        btnExport            = findViewById(R.id.btn_export);
        btnImport            = findViewById(R.id.btn_import);
        btnClearPreview      = findViewById(R.id.btn_clear_preview);
        editPreview          = findViewById(R.id.edit_preview);
        previewCard          = findViewById(R.id.preview_card);
        backupCard           = findViewById(R.id.backup_card);
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

        if (btnInfo != null) btnInfo.setOnClickListener(v -> showInfoDialog());
        if (btnExport != null) btnExport.setOnClickListener(v -> exportSettingsJson());
        if (btnImport != null) btnImport.setOnClickListener(v -> showImportDialog());
        if (btnClearPreview != null) btnClearPreview.setOnClickListener(v -> {
            if (editPreview != null) editPreview.setText("");
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

        if (previewCard != null) previewCard.setBackgroundColor(blend(bg, 0xFFFFFFFF, 0.04f));
        if (editPreview != null) {
            editPreview.setTextColor(textCol);
            editPreview.setHintTextColor(dim(textCol));
            editPreview.setBackgroundColor(blend(bg, 0xFF000000, 0.25f));
        }
        if (btnClearPreview != null) {
            btnClearPreview.setTextColor(dim(textCol));
            btnClearPreview.setBackgroundColor(blend(bg, 0xFF000000, 0.25f));
        }
        if (btnInfo != null) {
            btnInfo.setTextColor(accent);
            btnInfo.setBackgroundColor(blend(bg, accent, 0.15f));
        }
        if (backupCard != null) backupCard.setBackgroundColor(blend(bg, 0xFFFFFFFF, 0.04f));
        if (btnExport != null) {
            btnExport.setTextColor(accent);
            btnExport.setBackgroundColor(blend(bg, accent, 0.15f));
        }
        if (btnImport != null) {
            btnImport.setTextColor(textCol);
            btnImport.setBackgroundColor(blend(bg, 0xFFFFFFFF, 0.05f));
        }
    }

    // ─── Info / help ──────────────────────────────────────────────────────────
    private void showInfoDialog() {
        String body =
                "CodeKeys is a developer-focused on-screen keyboard.\n\n" +
                "• Suggestions strip — autocomplete with corrections.\n" +
                "• Snippet row — language-aware code templates (fn, for, if…).\n" +
                "• Symbol row — frequently typed symbols, swipeable.\n" +
                "• Emoji panel — search emoji by name (🔍).\n" +
                "• Clipboard panel — recent copies with pin & paste.\n" +
                "• PC keys row — Esc, Tab, Ctrl, F-keys, Home/End/PgUp/PgDn.\n" +
                "• Themes — eight presets, instant accent recolour.\n" +
                "• Custom snippets — add your own per-language triggers.\n" +
                "• Backup & Restore — export/import as JSON (this screen).\n\n" +
                "Setup:\n" +
                "  ① Tap “Enable CodeKeys in System Settings”\n" +
                "  ② Tap “Switch to CodeKeys (open IME picker)”\n\n" +
                "Tip: long-press a snippet to see a preview of what it inserts.";

        new AlertDialog.Builder(this)
                .setTitle("About CodeKeys")
                .setMessage(body)
                .setPositiveButton("Got it", null)
                .show();
    }

    // ─── Backup / restore (JSON) ──────────────────────────────────────────────
    /**
     * Builds a JSON document containing every CodeKeys preference. Preferences
     * the user has never set are stored under their actual current value, so
     * exports are self-contained and re-importing them deterministically
     * restores the keyboard's state.
     */
    private String buildBackupJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("app", "CodeKeys");
            root.put("version", 1);

            JSONObject all = new JSONObject();
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                Object v = entry.getValue();
                if (v == null) {
                    all.put(entry.getKey(), JSONObject.NULL);
                } else if (v instanceof Boolean) {
                    all.put(entry.getKey(), ((Boolean) v).booleanValue());
                } else if (v instanceof Integer) {
                    all.put(entry.getKey(), ((Integer) v).intValue());
                } else if (v instanceof Long) {
                    all.put(entry.getKey(), ((Long) v).longValue());
                } else if (v instanceof Float) {
                    all.put(entry.getKey(), (double) ((Float) v).floatValue());
                } else if (v instanceof Double) {
                    all.put(entry.getKey(), ((Double) v).doubleValue());
                } else {
                    all.put(entry.getKey(), v.toString());
                }
            }
            root.put("prefs", all);

            // Snippets — stored under custom_snip_<LANG> keys; we mirror them
            // into a structured array so a backup can be hand-edited safely.
            JSONArray snippets = new JSONArray();
            for (String lang : getAllLanguages()) {
                List<String[]> rows = loadCustomSnippets(lang);
                if (rows.isEmpty()) continue;
                JSONObject langObj = new JSONObject();
                langObj.put("lang", lang);
                JSONArray arr = new JSONArray();
                for (String[] s : rows) {
                    JSONObject pair = new JSONObject();
                    pair.put("trigger", s[0]);
                    pair.put("expansion", s[1]);
                    arr.put(pair);
                }
                langObj.put("entries", arr);
                snippets.put(langObj);
            }
            root.put("snippets", snippets);

            return root.toString(2);
        } catch (JSONException e) {
            return "{\"error\":\"failed to build backup: " + e.getMessage() + "\"}";
        }
    }

    private void exportSettingsJson() {
        final String json = buildBackupJson();

        ScrollView scroll = new ScrollView(this);
        EditText box = new EditText(this);
        box.setText(json);
        box.setTextSize(11f);
        box.setTypeface(Typeface.MONOSPACE);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.setMinLines(8);
        box.setGravity(Gravity.TOP | Gravity.START);
        box.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        scroll.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("Export — copy this JSON")
                .setView(scroll)
                .setPositiveButton("Copy", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager)
                            getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("CodeKeys backup", json));
                        Toast.makeText(this, "Backup copied to clipboard.",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showImportDialog() {
        ScrollView scroll = new ScrollView(this);
        final EditText box = new EditText(this);
        box.setHint("Paste a CodeKeys backup JSON here…");
        box.setTextSize(11f);
        box.setTypeface(Typeface.MONOSPACE);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.setMinLines(6);
        box.setGravity(Gravity.TOP | Gravity.START);
        box.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        scroll.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("Import backup")
                .setMessage("This will overwrite your themes, snippets, and settings.")
                .setView(scroll)
                .setPositiveButton("Import", (d, w) -> {
                    String text = box.getText().toString().trim();
                    if (TextUtils.isEmpty(text)) {
                        Toast.makeText(this, "Nothing to import.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (applyBackupJson(text)) {
                        Toast.makeText(this, "Backup imported.",
                                Toast.LENGTH_SHORT).show();
                        recreate();
                    } else {
                        Toast.makeText(this, "Invalid backup JSON.",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Applies a backup JSON document to {@link #prefs} in a single atomic
     * commit. Returns {@code false} if the document doesn't look like a
     * CodeKeys backup so the caller can show an error.
     */
    private boolean applyBackupJson(String text) {
        try {
            JSONObject root = new JSONObject(text);

            SharedPreferences.Editor ed = prefs.edit();
            ed.clear();

            JSONObject p = root.optJSONObject("prefs");
            if (p != null) {
                java.util.Iterator<String> keys = p.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    Object v = p.get(k);
                    if (v == null || v == JSONObject.NULL) continue;
                    if (v instanceof Boolean) {
                        ed.putBoolean(k, (Boolean) v);
                    } else if (v instanceof Integer) {
                        ed.putInt(k, (Integer) v);
                    } else if (v instanceof Long) {
                        ed.putLong(k, (Long) v);
                    } else if (v instanceof Double) {
                        ed.putFloat(k, ((Double) v).floatValue());
                    } else if (v instanceof Float) {
                        ed.putFloat(k, (Float) v);
                    } else {
                        ed.putString(k, v.toString());
                    }
                }
            }

            // Snippets: rebuild custom_snip_<LANG> values from the structured
            // form so old export formats stay valid.
            JSONArray snippets = root.optJSONArray("snippets");
            if (snippets != null) {
                for (int i = 0; i < snippets.length(); i++) {
                    JSONObject langObj = snippets.optJSONObject(i);
                    if (langObj == null) continue;
                    String lang = langObj.optString("lang", "");
                    if (TextUtils.isEmpty(lang)) continue;
                    JSONArray arr = langObj.optJSONArray("entries");
                    if (arr == null) continue;
                    StringBuilder sb = new StringBuilder();
                    for (int j = 0; j < arr.length(); j++) {
                        JSONObject pair = arr.optJSONObject(j);
                        if (pair == null) continue;
                        String t = pair.optString("trigger", "");
                        String ex = pair.optString("expansion", "");
                        if (TextUtils.isEmpty(t) || TextUtils.isEmpty(ex)) continue;
                        if (t.indexOf('\u0001') >= 0 || t.indexOf('\u0002') >= 0
                                || ex.indexOf('\u0001') >= 0
                                || ex.indexOf('\u0002') >= 0) continue;
                        if (sb.length() > 0) sb.append('\u0002');
                        sb.append(t).append('\u0001').append(ex);
                    }
                    ed.putString("custom_snip_" + lang, sb.toString());
                }
            }
            ed.apply();
            return true;
        } catch (JSONException e) {
            return false;
        }
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
            hint.setText("No custom presets yet. Add one above, then tap “Snippets…” to fill it in.");
            hint.setTextSize(11f);
            hint.setTextColor(dim(textCol));
            customLangList.addView(hint);
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
            int snippetCount = loadCustomSnippets(name).size();
            nameView.setText(name + "  ·  " + snippetCount + " snippet" + (snippetCount == 1 ? "" : "s"));
            nameView.setTextSize(13f);
            nameView.setTextColor(textCol);
            nameView.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(nameView);

            Button edit = new Button(this);
            edit.setText("Snippets…");
            edit.setAllCaps(false);
            edit.setTextSize(11f);
            edit.setTextColor(accent);
            edit.setBackgroundColor(blend(bg, accent, 0.15f));
            edit.setPadding(dp(10), 0, dp(10), 0);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            elp.setMarginEnd(dp(6));
            edit.setLayoutParams(elp);
            edit.setOnClickListener(v -> showSnippetEditor(name));
            row.addView(edit);

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

        // Built-in languages also accept user-added snippets that overlay the
        // built-in set. Show a compact list so the user can extend them too.
        TextView header = new TextView(this);
        header.setText("Built-in language snippets");
        header.setTextSize(11f);
        header.setTextColor(dim(textCol));
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(0, dp(14), 0, dp(6));
        customLangList.addView(header);

        for (final String name : Arrays.asList("GENERAL", "C", "JAVA", "PYTHON", "JS")) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundColor(blend(bg, 0xFFFFFFFF, 0.03f));
            row.setPadding(dp(12), dp(8), dp(8), dp(8));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, 0, 0, dp(2));
            row.setLayoutParams(rlp);

            TextView nv = new TextView(this);
            int extra = loadCustomSnippets(name).size();
            nv.setText(name + (extra > 0 ? "  ·  +" + extra + " custom" : ""));
            nv.setTextSize(12f);
            nv.setTextColor(textCol);
            nv.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(nv);

            Button edit = new Button(this);
            edit.setText("Snippets…");
            edit.setAllCaps(false);
            edit.setTextSize(11f);
            edit.setTextColor(accent);
            edit.setBackgroundColor(blend(bg, accent, 0.10f));
            edit.setPadding(dp(10), 0, dp(10), 0);
            edit.setOnClickListener(v -> showSnippetEditor(name));
            row.addView(edit);
            customLangList.addView(row);
        }
    }

    // ─── Snippet editor (per language) ────────────────────────────────────────
    /**
     * Pops up a sheet listing the user's custom snippets for {@code lang} with
     * actions to add, edit, and delete entries. The list is persisted under
     * {@code custom_snip_<LANG>} in the IME's SharedPreferences (see
     * {@link com.codekeys.ime.CodeKeysIME#loadCustomSnippets}).
     */
    private void showSnippetEditor(final String lang) {
        final List<String[]> snippets = new ArrayList<>(loadCustomSnippets(lang));
        final ScrollView scroll = new ScrollView(this);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(12), dp(16), dp(12));
        scroll.addView(list);

        final Runnable refresh = () -> renderSnippetList(list, lang, snippets);
        refresh.run();

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Snippets — " + lang)
                .setView(scroll)
                .setPositiveButton("Add snippet", null)
                .setNegativeButton("Done", null)
                .create();
        dlg.show();

        // Override the positive button so it doesn't auto-dismiss on Add.
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v ->
                showAddOrEditSnippet(lang, null, () -> {
                    snippets.clear();
                    snippets.addAll(loadCustomSnippets(lang));
                    refresh.run();
                    renderCustomLanguages();
                }));
    }

    /**
     * (Re)renders the current snippet list inside the editor sheet. Each row
     * shows {@code trigger → expansion} (truncated for readability) plus
     * Edit / Delete buttons.
     */
    private void renderSnippetList(LinearLayout container, final String lang,
                                   final List<String[]> snippets) {
        container.removeAllViews();
        int textCol = prefs.getInt("text_color", 0xFFE8E8FF);
        int accent  = prefs.getInt("accent_color", 0xFF00E5FF);

        if (snippets.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("No custom snippets yet. Tap “Add snippet”.");
            hint.setTextSize(12f);
            hint.setTextColor(dim(textCol));
            hint.setPadding(0, 0, 0, dp(8));
            container.addView(hint);
            return;
        }
        for (int i = 0; i < snippets.size(); i++) {
            final int idx = i;
            final String[] s = snippets.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            TextView label = new TextView(this);
            String preview = s[1].replace("\n", "↵");
            if (preview.length() > 40) preview = preview.substring(0, 40) + "…";
            label.setText(s[0] + "  →  " + preview);
            label.setTextSize(13f);
            label.setTextColor(textCol);
            label.setTypeface(Typeface.MONOSPACE);
            label.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(label);

            Button edit = new Button(this);
            edit.setText("Edit");
            edit.setAllCaps(false);
            edit.setTextSize(11f);
            edit.setTextColor(accent);
            edit.setBackgroundColor(0x00000000);
            edit.setOnClickListener(v ->
                    showAddOrEditSnippet(lang, s, () -> {
                        snippets.clear();
                        snippets.addAll(loadCustomSnippets(lang));
                        renderSnippetList(container, lang, snippets);
                        renderCustomLanguages();
                    }));
            row.addView(edit);

            Button del = new Button(this);
            del.setText("Delete");
            del.setAllCaps(false);
            del.setTextSize(11f);
            del.setTextColor(0xFFFF6666);
            del.setBackgroundColor(0x00000000);
            del.setOnClickListener(v -> {
                snippets.remove(idx);
                saveCustomSnippets(lang, snippets);
                renderSnippetList(container, lang, snippets);
                renderCustomLanguages();
            });
            row.addView(del);

            container.addView(row);
        }
    }

    /**
     * Shows an Add/Edit dialog for a single snippet. When {@code existing} is
     * non-null the dialog pre-fills its fields and replaces that entry on save;
     * otherwise the new snippet is appended. Calls {@code onSaved} after a
     * successful persist so the parent list can refresh.
     */
    private void showAddOrEditSnippet(final String lang, final String[] existing,
                                      final Runnable onSaved) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(16), dp(8), dp(16), dp(8));

        final EditText trigger = new EditText(this);
        trigger.setHint("Trigger (e.g. fn)");
        trigger.setSingleLine(true);
        trigger.setInputType(InputType.TYPE_CLASS_TEXT);
        if (existing != null) trigger.setText(existing[0]);
        container.addView(trigger);

        final EditText expansion = new EditText(this);
        expansion.setHint("Expansion (multi-line allowed)");
        expansion.setMinLines(3);
        expansion.setGravity(Gravity.TOP | Gravity.START);
        expansion.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        if (existing != null) expansion.setText(existing[1]);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(10);
        expansion.setLayoutParams(elp);
        container.addView(expansion);

        new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Add snippet" : "Edit snippet")
                .setView(container)
                .setPositiveButton("Save", (d, w) -> {
                    String t = trigger.getText().toString().trim();
                    String ex = expansion.getText().toString();
                    if (TextUtils.isEmpty(t) || TextUtils.isEmpty(ex)) {
                        Toast.makeText(this, "Trigger and expansion are required.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // The internal storage uses \u0001 / \u0002 separators; reject
                    // any input that already contains them to keep the format safe.
                    if (t.indexOf('\u0001') >= 0 || t.indexOf('\u0002') >= 0
                            || ex.indexOf('\u0001') >= 0 || ex.indexOf('\u0002') >= 0) {
                        Toast.makeText(this, "Invalid characters in snippet.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    List<String[]> all = new ArrayList<>(loadCustomSnippets(lang));
                    if (existing != null) {
                        for (int i = 0; i < all.size(); i++) {
                            if (all.get(i)[0].equals(existing[0])) {
                                all.set(i, new String[]{t, ex});
                                break;
                            }
                        }
                    } else {
                        // Replace if a snippet with the same trigger already exists,
                        // otherwise append.
                        boolean replaced = false;
                        for (int i = 0; i < all.size(); i++) {
                            if (all.get(i)[0].equals(t)) {
                                all.set(i, new String[]{t, ex});
                                replaced = true;
                                break;
                            }
                        }
                        if (!replaced) all.add(new String[]{t, ex});
                    }
                    saveCustomSnippets(lang, all);
                    if (onSaved != null) onSaved.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Loads the user's custom snippets for {@code lang} (see IME for format). */
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

    private void saveCustomSnippets(String lang, List<String[]> snippets) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < snippets.size(); i++) {
            if (i > 0) sb.append('\u0002');
            String[] s = snippets.get(i);
            sb.append(s[0]).append('\u0001').append(s[1]);
        }
        prefs.edit().putString("custom_snip_" + lang, sb.toString()).apply();
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
