package com.codekeys.ime;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
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
import android.widget.SeekBar;
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

    /**
     * minSdk-21-safe stand-in for {@code java.util.function.IntConsumer}.
     * Used by the colour picker to funnel palette / hex / slider updates
     * through a single preview renderer without pulling in the API 24+
     * functional types.
     */
    private interface ColorPreviewSink { void accept(int color); }

    /** SharedPreferences keys for the custom background image. The URI key is
     *  shared with the canonical "kb_bg_image_uri" pref read by the IME so the
     *  skeleton-preview dialog and the rest of the app stay in sync. */
    private static final String PREF_BG_IMAGE_URI     = "kb_bg_image_uri";
    private static final String PREF_BG_IMAGE_OPACITY = "custom_bg_image_opacity";

    private SharedPreferences prefs;

    /**
     * Re-rendering hook supplied by {@link #showBackgroundImageDialog} so the
     * skeleton preview refreshes when the user returns from the SAF image
     * picker. Cleared after the dialog is dismissed.
     */
    private Runnable pendingBgPreviewRefresh;

    // Cached views from the inflated XML.
    private ScrollView root;
    private LinearLayout preferencesContainer;
    private LinearLayout themesContainer;
    private LinearLayout customThemeContainer;
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
        // Seed defaults from assets/settings_defaults.json on first launch.
        AssetDefaults.seedDefaults(this, prefs);
        // Pick up any themes shipped in assets/themes.json so the grid below
        // reflects the latest palette without a code change.
        loadAssetThemes();
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

    /** Themes/names/dark flags loaded from assets/themes.json (else hardcoded fallback). */
    private int[][] activeThemes = THEMES;
    private String[] activeThemeNames = THEME_NAMES;
    private boolean[] activeThemeIsDark = THEME_IS_DARK;

    private void loadAssetThemes() {
        java.util.List<AssetDefaults.Theme> themes = AssetDefaults.loadThemes(this);
        if (themes == null || themes.isEmpty()) return;
        int n = themes.size();
        int[][] palette = new int[n][4];
        String[] names = new String[n];
        boolean[] dark = new boolean[n];
        for (int i = 0; i < n; i++) {
            AssetDefaults.Theme t = themes.get(i);
            palette[i] = new int[]{ t.bgColor, t.keyColor, t.textColor, t.accentColor };
            names[i]   = t.name;
            dark[i]    = t.dark;
        }
        activeThemes = palette;
        activeThemeNames = names;
        activeThemeIsDark = dark;
    }

    // ─── View binding ─────────────────────────────────────────────────────────
    private void bindViews() {
        root                 = findViewById(R.id.settings_scroll);
        preferencesContainer = findViewById(R.id.preferences_container);
        themesContainer      = findViewById(R.id.themes_container);
        customThemeContainer = findViewById(R.id.custom_theme_container);
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
        int textCol = prefs.getInt("text_color", 0xFFE8E8FF);
        int bg      = prefs.getInt("bg_color",   0xFF1A1A2E);
        int accent  = prefs.getInt("accent_color", 0xFF00E5FF);

        // Build a themed scrollable body so the About panel matches the
        // user's selected colour palette instead of the platform default.
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(18), dp(20), dp(18));
        box.setBackground(themedRoundedFill(bg, accent, dp(18), dp(1)));
        scroll.addView(box);

        TextView title = new TextView(this);
        title.setText("CodeKeys");
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(accent);
        box.addView(title);

        TextView tagline = new TextView(this);
        tagline.setText("A developer-focused on-screen keyboard.");
        tagline.setTextSize(12f);
        tagline.setTextColor(dim(textCol));
        tagline.setPadding(0, dp(4), 0, dp(14));
        box.addView(tagline);

        TextView features = new TextView(this);
        features.setText(
                "• Suggestions strip — autocomplete with corrections.\n" +
                "• Snippet row — language-aware code templates (fn, for, if…).\n" +
                "• Symbol row — frequently typed symbols, swipeable.\n" +
                "• Emoji panel — search emoji by name.\n" +
                "• Clipboard panel — recent copies with pin & paste.\n" +
                "• PC keys row — Esc, Tab, Ctrl, F-keys, Home/End/PgUp/PgDn.\n" +
                "• Themes — preset palette plus full custom colours.\n" +
                "• Custom snippets — add your own per-language triggers.\n" +
                "• Backup & Restore — export/import as JSON (this screen)."
        );
        features.setTextSize(13f);
        features.setTextColor(textCol);
        features.setLineSpacing(0, 1.15f);
        box.addView(features);

        TextView setupHeader = new TextView(this);
        setupHeader.setText("Setup");
        setupHeader.setTextSize(13f);
        setupHeader.setTypeface(Typeface.DEFAULT_BOLD);
        setupHeader.setTextColor(accent);
        setupHeader.setPadding(0, dp(14), 0, dp(4));
        box.addView(setupHeader);

        TextView setup = new TextView(this);
        setup.setText("① Tap “Enable CodeKeys in System Settings”\n" +
                      "② Tap “Switch to CodeKeys (open IME picker)”\n\n" +
                      "Tip: long-press a snippet to preview the inserted text.");
        setup.setTextSize(12f);
        setup.setTextColor(textCol);
        box.addView(setup);

        // Credits — clearly attributes the creator/developer.
        View div = new View(this);
        div.setBackgroundColor(blend(textCol, 0xFF000000, 0.7f));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divLp.setMargins(0, dp(16), 0, dp(12));
        box.addView(div, divLp);

        TextView creditsHeader = new TextView(this);
        creditsHeader.setText("About the developer");
        creditsHeader.setTextSize(13f);
        creditsHeader.setTypeface(Typeface.DEFAULT_BOLD);
        creditsHeader.setTextColor(accent);
        box.addView(creditsHeader);

        TextView credits = new TextView(this);
        credits.setText(
                "Created & developed by Saqib (saqib-cipher).\n" +
                "Built with care for developers who code on Android.\n" +
                "Icons adapted from Tabler.io. Snippets, themes, and " +
                "symbol presets ship as JSON in /assets so power users " +
                "can extend them without rebuilding the app.\n\n" +
                "Version 1.0.0"
        );
        credits.setTextSize(12f);
        credits.setTextColor(textCol);
        credits.setLineSpacing(0, 1.15f);
        credits.setPadding(0, dp(4), 0, 0);
        box.addView(credits);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setView(scroll)
                .setPositiveButton("Got it", null)
                .create();
        showThemedDialog(dlg);
    }

    /**
     * Wraps an {@link AlertDialog} so its window background and title/button
     * colours pick up the user's selected theme. Keeps the platform default
     * positioning but trades the white system look for the keyboard palette.
     */
    private void showThemedDialog(AlertDialog dlg) {
        int bg     = prefs.getInt("bg_color",     0xFF1A1A2E);
        int accent = prefs.getInt("accent_color", 0xFF00E5FF);
        dlg.setOnShowListener(d -> {
            try {
                if (dlg.getWindow() != null) {
                    dlg.getWindow().setBackgroundDrawable(themedRoundedFill(bg, accent, dp(20), dp(1)));
                }
                Button pos = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
                if (pos != null) pos.setTextColor(accent);
                Button neg = dlg.getButton(AlertDialog.BUTTON_NEGATIVE);
                if (neg != null) neg.setTextColor(accent);
                Button neu = dlg.getButton(AlertDialog.BUTTON_NEUTRAL);
                if (neu != null) neu.setTextColor(accent);
            } catch (Throwable ignored) {}
        });
        dlg.show();
    }

    /**
     * Builds a rounded-rect drawable using the theme's bg colour with a
     * 1dp accent stroke — used as a window background for dialogs and as
     * the background for themed EditText fields.
     */
    private android.graphics.drawable.GradientDrawable themedRoundedFill(
            int fillColor, int strokeColor, int radiusPx, int strokeWidthPx) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(fillColor);
        gd.setCornerRadius(radiusPx);
        gd.setStroke(strokeWidthPx, strokeColor);
        return gd;
    }

    // ─── Backup / restore (JSON) ──────────────────────────────────────────────

    /**
     * Bitmask flags identifying which slices of state the user wants to
     * include in an export, or accept from an import. Granular scopes let
     * the user move a colour palette between devices without overwriting
     * their snippets (and vice-versa).
     */
    private static final int SCOPE_THEME     = 1;
    private static final int SCOPE_SNIPPETS  = 1 << 1;
    private static final int SCOPE_LANGUAGES = 1 << 2;
    private static final int SCOPE_SYMBOLS   = 1 << 3;
    private static final int SCOPE_OTHER     = 1 << 4;
    private static final int SCOPE_ALL =
            SCOPE_THEME | SCOPE_SNIPPETS | SCOPE_LANGUAGES | SCOPE_SYMBOLS | SCOPE_OTHER;

    /**
     * Classifies a pref key into the {@code SCOPE_*} bucket it belongs to.
     * Snippet rows are stored under the key prefix {@code custom_snip_} but
     * also re-emitted in a structured "snippets" array, so the prefs-side
     * classification is what governs whether the backing string is included
     * when {@link #SCOPE_SNIPPETS} is set.
     */
    private int scopeOfPrefKey(String key) {
        if (key == null) return SCOPE_OTHER;
        if (key.startsWith("custom_snip_")) return SCOPE_SNIPPETS;
        if (key.startsWith("custom_sym_"))  return SCOPE_SYMBOLS;
        if (PREF_CUSTOM_LANGS.equals(key))  return SCOPE_LANGUAGES;
        switch (key) {
            case "bg_color":
            case "key_color":
            case "text_color":
            case "accent_color":
            case "theme_kind":
            case "dark":
            case "amoled":
            case "key_radius_dp":
            case "key_text_size_sp":
            case "key_stroke_width_dp":
            case "key_stroke_color":
                return SCOPE_THEME;
        }
        if (key.startsWith("kb_bg_") || key.startsWith("bg_image")) return SCOPE_THEME;
        return SCOPE_OTHER;
    }

    /**
     * Builds a JSON document containing every CodeKeys preference. Preferences
     * the user has never set are stored under their actual current value, so
     * exports are self-contained and re-importing them deterministically
     * restores the keyboard's state.
     */
    private String buildBackupJson() {
        return buildBackupJson(SCOPE_ALL);
    }

    /** Scope-filtered variant of {@link #buildBackupJson()}. */
    private String buildBackupJson(int scopeMask) {
        try {
            JSONObject root = new JSONObject();
            root.put("app", "CodeKeys");
            root.put("version", 1);

            root.put("scopes", scopeMask);

            JSONObject all = new JSONObject();
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                String k = entry.getKey();
                if ((scopeMask & scopeOfPrefKey(k)) == 0) continue;
                Object v = entry.getValue();
                if (v == null) {
                    all.put(k, JSONObject.NULL);
                } else if (v instanceof Boolean) {
                    all.put(k, ((Boolean) v).booleanValue());
                } else if (v instanceof Integer) {
                    all.put(k, ((Integer) v).intValue());
                } else if (v instanceof Long) {
                    all.put(k, ((Long) v).longValue());
                } else if (v instanceof Float) {
                    all.put(k, (double) ((Float) v).floatValue());
                } else if (v instanceof Double) {
                    all.put(k, ((Double) v).doubleValue());
                } else {
                    all.put(k, v.toString());
                }
            }
            root.put("prefs", all);

            // Snippets — also exported in a structured form so a backup can
            // be hand-edited safely (and so older exports without
            // custom_snip_<LANG> prefs can still be restored).
            if ((scopeMask & SCOPE_SNIPPETS) != 0) {
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
            }

            return root.toString(2);
        } catch (JSONException e) {
            return "{\"error\":\"failed to build backup: " + e.getMessage() + "\"}";
        }
    }

    private void exportSettingsJson() {
        showScopePicker(true, scopeMask -> exportSettingsJson(scopeMask));
    }

    private void exportSettingsJson(int scopeMask) {
        final String json = buildBackupJson(scopeMask);

        LinearLayout panel = buildThemedDialogPanel("Export — copy this JSON",
                "Selected sections only. Tap Copy to send the JSON to your clipboard.");
        EditText box = buildThemedMultilineEditText(null, json, 8);
        panel.addView(box);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setView(wrapInScroll(panel))
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
                .create();
        showThemedDialog(dlg);
    }

    private void showImportDialog() {
        showScopePicker(false, scopeMask -> showImportDialog(scopeMask));
    }

    private void showImportDialog(int scopeMask) {
        LinearLayout panel = buildThemedDialogPanel("Import backup",
                "Only the selected sections of the pasted JSON will be applied.");
        final EditText box = buildThemedMultilineEditText(
                "Paste a CodeKeys backup JSON here…", null, 6);
        panel.addView(box);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setView(wrapInScroll(panel))
                .setPositiveButton("Import", (d, w) -> {
                    String text = box.getText().toString().trim();
                    if (TextUtils.isEmpty(text)) {
                        Toast.makeText(this, "Nothing to import.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (applyBackupJson(text, scopeMask)) {
                        Toast.makeText(this, "Backup imported.",
                                Toast.LENGTH_SHORT).show();
                        recreate();
                    } else {
                        Toast.makeText(this, "Invalid backup JSON.",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        showThemedDialog(dlg);
    }

    /**
     * Shows a small dialog with one toggle per backup scope (theme, snippets,
     * languages, symbols, other settings) so the user can opt into exactly
     * the slice they want. {@code onChosen} fires with the bitmask of
     * selected {@code SCOPE_*} flags once the user taps the action button.
     */
    private interface ScopeChosen { void apply(int scopeMask); }
    private void showScopePicker(boolean exporting, final ScopeChosen onChosen) {
        int textCol = prefs.getInt("text_color", 0xFFE8E8FF);

        final boolean[] flags = { true, true, true, true, true };
        final int[] scopeBits = {
                SCOPE_THEME, SCOPE_SNIPPETS, SCOPE_LANGUAGES, SCOPE_SYMBOLS, SCOPE_OTHER
        };
        final String[] labels = {
                "Theme (colours, shape, size)",
                "Snippets",
                "Custom languages",
                "Symbol toolbars",
                "Other settings"
        };

        LinearLayout panel = buildThemedDialogPanel(
                exporting ? "Export — choose sections" : "Import — choose sections",
                exporting
                        ? "Pick which slices of your setup to include in the backup."
                        : "Pick which slices to apply from the pasted backup.");

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            Switch sw = new Switch(this);
            sw.setText(labels[i]);
            sw.setTextColor(textCol);
            sw.setChecked(flags[i]);
            sw.setPadding(dp(4), dp(8), dp(4), dp(8));
            sw.setOnCheckedChangeListener((b, c) -> flags[idx] = c);
            panel.addView(sw);
        }

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setView(wrapInScroll(panel))
                .setPositiveButton(exporting ? "Next" : "Next", (d, w) -> {
                    int mask = 0;
                    for (int i = 0; i < flags.length; i++) {
                        if (flags[i]) mask |= scopeBits[i];
                    }
                    if (mask == 0) {
                        Toast.makeText(this, "Pick at least one section.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    onChosen.apply(mask);
                })
                .setNegativeButton("Cancel", null)
                .create();
        showThemedDialog(dlg);
    }

    /**
     * Builds the standard "themed dialog" container — a vertical {@link LinearLayout}
     * with a header (title + optional subtitle) painted in the user's theme.
     * Callers append their controls and wrap with {@link #wrapInScroll(View)}.
     */
    private LinearLayout buildThemedDialogPanel(String title, String subtitle) {
        int textCol = prefs.getInt("text_color", 0xFFE8E8FF);
        int accent  = prefs.getInt("accent_color", 0xFF00E5FF);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(18), dp(20), dp(18));

        if (!TextUtils.isEmpty(title)) {
            TextView t = new TextView(this);
            t.setText(title);
            t.setTextSize(17f);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(accent);
            panel.addView(t);
        }
        if (!TextUtils.isEmpty(subtitle)) {
            TextView s = new TextView(this);
            s.setText(subtitle);
            s.setTextSize(12f);
            s.setTextColor(dim(textCol));
            s.setPadding(0, dp(4), 0, dp(12));
            panel.addView(s);
        }
        return panel;
    }

    private ScrollView wrapInScroll(View child) {
        ScrollView sv = new ScrollView(this);
        sv.addView(child);
        return sv;
    }

    /**
     * Builds an EditText styled to match the active theme: bg colour from the
     * theme, accent stroke, rounded corners, monospace text. Used by every
     * themed dialog so they all share the same look.
     */
    private EditText buildThemedMultilineEditText(String hint, String initialText, int minLines) {
        int textCol = prefs.getInt("text_color", 0xFFE8E8FF);
        int bg      = prefs.getInt("bg_color",   0xFF1A1A2E);
        int accent  = prefs.getInt("accent_color", 0xFF00E5FF);

        EditText box = new EditText(this);
        if (initialText != null) box.setText(initialText);
        if (hint != null) box.setHint(hint);
        box.setTextSize(11f);
        box.setTypeface(Typeface.MONOSPACE);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setMinLines(minLines);
        box.setGravity(Gravity.TOP | Gravity.START);
        box.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.setBackground(themedRoundedFill(blend(bg, 0xFFFFFFFF, 0.05f),
                accent, dp(12), dp(1)));
        box.setTextColor(textCol);
        box.setHintTextColor(dim(textCol));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        box.setLayoutParams(lp);
        return box;
    }

    /** Single-line variant for triggers / language names / hex inputs. */
    private EditText buildThemedSingleLineEditText(String hint, String initialText) {
        int textCol = prefs.getInt("text_color", 0xFFE8E8FF);
        int bg      = prefs.getInt("bg_color",   0xFF1A1A2E);
        int accent  = prefs.getInt("accent_color", 0xFF00E5FF);

        EditText box = new EditText(this);
        if (initialText != null) box.setText(initialText);
        if (hint != null) box.setHint(hint);
        box.setTextSize(13f);
        box.setSingleLine(true);
        box.setPadding(dp(14), dp(10), dp(14), dp(10));
        box.setBackground(themedRoundedFill(blend(bg, 0xFFFFFFFF, 0.05f),
                accent, dp(12), dp(1)));
        box.setTextColor(textCol);
        box.setHintTextColor(dim(textCol));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        box.setLayoutParams(lp);
        return box;
    }

    /**
     * Applies a backup JSON document to {@link #prefs} in a single atomic
     * commit. Returns {@code false} if the document doesn't look like a
     * CodeKeys backup so the caller can show an error.
     */
    private boolean applyBackupJson(String text) {
        return applyBackupJson(text, SCOPE_ALL);
    }

    /**
     * Scope-filtered variant of {@link #applyBackupJson(String)}: only the
     * sections selected in {@code scopeMask} are applied. Existing prefs in
     * other scopes are preserved (no clear()). Existing prefs *inside* the
     * selected scopes are wiped first so a partial import doesn't leave
     * stale theme/snippet data behind.
     */
    private boolean applyBackupJson(String text, int scopeMask) {
        try {
            JSONObject root = new JSONObject(text);

            SharedPreferences.Editor ed = prefs.edit();

            // Wipe in-scope prefs so partial imports can't merge with stale
            // values (e.g. importing a "theme only" backup leaves snippets
            // alone but replaces every theme key).
            for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
                if ((scopeMask & scopeOfPrefKey(e.getKey())) != 0) {
                    ed.remove(e.getKey());
                }
            }

            JSONObject p = root.optJSONObject("prefs");
            if (p != null) {
                java.util.Iterator<String> keys = p.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    if ((scopeMask & scopeOfPrefKey(k)) == 0) continue;
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
            if ((scopeMask & SCOPE_SNIPPETS) != 0) {
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

        // Appearance (key shape, size, border, colours) lives inside the
        // Custom theme panel — see renderCustomThemePanel — so users have a
        // single place to tune their personal look instead of two.

        // ── Keyboard background ─────────────────────────────────────────
        preferencesContainer.addView(buildSectionHeader("Keyboard Background", textCol));
        preferencesContainer.addView(buildBackgroundModeSelector(textCol, bg, accent));
        String mode = prefs.getString("kb_bg_mode", "solid");
        if ("gradient".equals(mode)) {
            preferencesContainer.addView(buildColorSelector(
                    "Gradient Top",
                    "Top color of the keyboard gradient.",
                    "kb_bg_gradient_start",
                    prefs.getInt("kb_bg_gradient_start", bg),
                    new int[]{0xFF1A1A2E, 0xFF000000, 0xFF1565C0, 0xFF6A1B9A, 0xFF263238, 0xFFE91E63},
                    new String[]{"Navy", "Black", "Blue", "Purple", "Slate", "Pink"},
                    textCol, bg, accent));
            preferencesContainer.addView(buildColorSelector(
                    "Gradient Bottom",
                    "Bottom color of the keyboard gradient.",
                    "kb_bg_gradient_end",
                    prefs.getInt("kb_bg_gradient_end", 0xFF000000),
                    new int[]{0xFF000000, 0xFF1A1A2E, 0xFF263238, 0xFF311B92, 0xFFB71C1C, 0xFF1B5E20},
                    new String[]{"Black", "Navy", "Slate", "Indigo", "Red", "Green"},
                    textCol, bg, accent));
        } else if ("image".equals(mode)) {
            preferencesContainer.addView(buildBackgroundImagePicker(textCol, bg, accent));
        }
    }

    private TextView buildSectionHeader(String label, int textCol) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(11f);
        tv.setTextColor(textCol);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(4), dp(14), dp(4), dp(6));
        return tv;
    }

    /**
     * Generic preset-button selector for an integer preference. Each option
     * shows the int value as its label; whichever is currently active gets
     * the accent highlight.
     */
    private View buildIntStepSelector(String label, String desc, final String key,
                                      final int def, final int[] values,
                                      int textCol, int bg, int accent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(blend(bg, 0xFFFFFFFF, 0.04f));
        card.setPadding(dp(16), dp(12), dp(16), dp(14));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, dp(2), 0, dp(2));
        card.setLayoutParams(clp);

        TextView lv = new TextView(this);
        lv.setText(label);
        lv.setTextSize(15f);
        lv.setTextColor(textCol);
        card.addView(lv);

        TextView dv = new TextView(this);
        dv.setText(desc);
        dv.setTextSize(11f);
        dv.setTextColor(dim(textCol));
        dv.setPadding(0, 0, 0, dp(8));
        card.addView(dv);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        int current = prefs.getInt(key, def);
        for (final int v : values) {
            boolean sel = current == v;
            Button b = new Button(this);
            b.setText(String.valueOf(v));
            b.setAllCaps(false);
            b.setTextSize(12f);
            b.setTextColor(sel ? accent : textCol);
            b.setBackgroundColor(sel ? blend(bg, accent, 0.22f) : blend(bg, 0xFFFFFFFF, 0.05f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            lp.setMargins(2, 0, 2, 0);
            b.setLayoutParams(lp);
            b.setOnClickListener(view -> {
                prefs.edit().putInt(key, v).apply();
                renderPreferences();
            });
            row.addView(b);
        }
        card.addView(row);
        return card;
    }

    /**
     * Preset color picker — each option is a labelled swatch. Stores the
     * chosen ARGB int under {@code key}.
     */
    private View buildColorSelector(String label, String desc, final String key,
                                    int defValue,
                                    final int[] colors, final String[] names,
                                    int textCol, int bg, int accent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(blend(bg, 0xFFFFFFFF, 0.04f));
        card.setPadding(dp(16), dp(12), dp(16), dp(14));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, dp(2), 0, dp(2));
        card.setLayoutParams(clp);

        TextView lv = new TextView(this);
        lv.setText(label);
        lv.setTextSize(15f);
        lv.setTextColor(textCol);
        card.addView(lv);

        TextView dv = new TextView(this);
        dv.setText(desc);
        dv.setTextSize(11f);
        dv.setTextColor(dim(textCol));
        dv.setPadding(0, 0, 0, dp(8));
        card.addView(dv);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        int current = prefs.getInt(key, defValue);
        for (int i = 0; i < colors.length; i++) {
            final int color = colors[i];
            boolean sel = current == color;

            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setBackgroundColor(sel ? blend(bg, accent, 0.22f) : blend(bg, 0xFFFFFFFF, 0.05f));
            cell.setPadding(dp(2), dp(6), dp(2), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
            lp.setMargins(2, 0, 2, 0);
            cell.setLayoutParams(lp);

            View swatch = new View(this);
            // For fully-transparent colors, paint a tiny checkered hint so the
            // user knows this means "no color".
            swatch.setBackgroundColor((color >>> 24) == 0 ? 0x33FFFFFF : color);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(22), dp(14));
            swatch.setLayoutParams(slp);
            cell.addView(swatch);

            TextView name = new TextView(this);
            name.setText(names[i]);
            name.setTextSize(10f);
            name.setTextColor(sel ? accent : textCol);
            name.setGravity(Gravity.CENTER);
            cell.addView(name);

            cell.setOnClickListener(view -> {
                prefs.edit().putInt(key, color).apply();
                renderPreferences();
            });
            row.addView(cell);
        }
        card.addView(row);
        return card;
    }

    /**
     * Three-button selector for the keyboard background mode (solid / gradient
     * / image). Re-renders the section so the mode-specific sub-controls
     * appear / disappear immediately.
     */
    private View buildBackgroundModeSelector(int textCol, int bg, int accent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(blend(bg, 0xFFFFFFFF, 0.04f));
        card.setPadding(dp(16), dp(12), dp(16), dp(14));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, dp(2), 0, dp(2));
        card.setLayoutParams(clp);

        TextView lv = new TextView(this);
        lv.setText("Background Mode");
        lv.setTextSize(15f);
        lv.setTextColor(textCol);
        card.addView(lv);

        TextView dv = new TextView(this);
        dv.setText("Solid uses the theme color. Gradient blends two colors. Image lets you pick a wallpaper.");
        dv.setTextSize(11f);
        dv.setTextColor(dim(textCol));
        dv.setPadding(0, 0, 0, dp(8));
        card.addView(dv);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        final String[] modes = {"solid", "gradient", "image"};
        final String[] names = {"Solid", "Gradient", "Image"};
        String current = prefs.getString("kb_bg_mode", "solid");
        for (int i = 0; i < modes.length; i++) {
            final String mode = modes[i];
            boolean sel = mode.equals(current);
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
                prefs.edit().putString("kb_bg_mode", mode).apply();
                renderPreferences();
            });
            row.addView(b);
        }
        card.addView(row);
        return card;
    }

    /** Pick / clear a custom keyboard background image. */
    private View buildBackgroundImagePicker(int textCol, int bg, int accent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(blend(bg, 0xFFFFFFFF, 0.04f));
        card.setPadding(dp(16), dp(12), dp(16), dp(14));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, dp(2), 0, dp(2));
        card.setLayoutParams(clp);

        TextView lv = new TextView(this);
        lv.setText("Background Image");
        lv.setTextSize(15f);
        lv.setTextColor(textCol);
        card.addView(lv);

        String uri = prefs.getString("kb_bg_image_uri", "");
        TextView dv = new TextView(this);
        dv.setText(TextUtils.isEmpty(uri)
                ? "No image selected. Tap Pick to choose one."
                : "Current: " + uri);
        dv.setTextSize(11f);
        dv.setTextColor(dim(textCol));
        dv.setPadding(0, 0, 0, dp(8));
        card.addView(dv);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button pick = new Button(this);
        pick.setText("Pick image");
        pick.setAllCaps(false);
        pick.setTextSize(12f);
        pick.setTextColor(accent);
        pick.setBackgroundColor(blend(bg, accent, 0.18f));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        plp.setMargins(2, 0, 2, 0);
        pick.setLayoutParams(plp);
        pick.setOnClickListener(v -> launchBackgroundImagePicker());
        row.addView(pick);

        Button clear = new Button(this);
        clear.setText("Clear");
        clear.setAllCaps(false);
        clear.setTextSize(12f);
        clear.setTextColor(0xFFFF6666);
        clear.setBackgroundColor(0x22FF0000);
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(0, dp(40), 1f);
        clp2.setMargins(2, 0, 2, 0);
        clear.setLayoutParams(clp2);
        clear.setOnClickListener(v -> {
            prefs.edit().remove("kb_bg_image_uri").apply();
            renderPreferences();
        });
        row.addView(clear);

        card.addView(row);
        return card;
    }

    /** Request code for the keyboard background image picker. */
    private static final int REQ_PICK_BG_IMAGE = 1042;

    private void launchBackgroundImagePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        // Persistable URI so we can read the file from the IME process later.
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_PICK_BG_IMAGE);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker available on this device.",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_BG_IMAGE
                && resultCode == RESULT_OK && data != null && data.getData() != null) {
            android.net.Uri uri = data.getData();
            // Take a persistable read permission so the IME service can re-open
            // the same URI on later launches.
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
            prefs.edit()
                    .putString(PREF_BG_IMAGE_URI, uri.toString())
                    .putString("kb_bg_mode", "image")
                    .apply();
            // If the skeleton-preview dialog is open, refresh its thumbnail.
            if (pendingBgPreviewRefresh != null) pendingBgPreviewRefresh.run();
            renderPreferences();
        }
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
        boolean customSelected = "custom".equals(prefs.getString("theme_kind", "preset"));

        for (int i = 0; i < activeThemes.length; i++) {
            final int[] theme = activeThemes[i];
            final String name = activeThemeNames[i];
            final boolean isDark = activeThemeIsDark[i];
            boolean active = !customSelected && (currentBg == theme[0]);

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
                // Selecting a preset applies the FULL theme — colours plus a
                // coherent shape/size baseline — so the user gets a consistent
                // look without having to also reset shape sliders by hand.
                // Stroke colour is derived from the preset's accent so the
                // outline reads as part of the same palette when stroke width
                // is bumped up later.
                //
                // Crucially, ALL other surface-related prefs (gradient colours,
                // background image, image opacity, background mode) are wiped
                // here so a preset is exactly what its swatches advertise — no
                // leftover gradient or wallpaper hangs around from the user's
                // previous custom session.
                prefs.edit()
                        .putString("theme_kind",   "preset")
                        .putInt("bg_color",        theme[0])
                        .putInt("key_color",       theme[1])
                        .putInt("text_color",      theme[2])
                        .putInt("accent_color",    theme[3])
                        .putInt("key_radius_dp",   12)
                        .putInt("key_text_size_sp", 14)
                        .putInt("key_stroke_width_dp", 0)
                        .putInt("key_stroke_color", theme[3] & 0x80FFFFFF)
                        .putBoolean("dark",        isDark)
                        .putBoolean("amoled", theme[0] == 0xFF000000)
                        // Reset background to a clean solid surface so the
                        // selected preset is fully applied with no stale state.
                        .putString("kb_bg_mode",   "solid")
                        .remove("kb_bg_gradient_start")
                        .remove("kb_bg_gradient_end")
                        .remove("kb_bg_gradient_dir")
                        .remove("kb_bg_image_uri")
                        .remove("kb_bg_image_fit")
                        .remove(PREF_BG_IMAGE_OPACITY)
                        .apply();
                Toast.makeText(this,
                        name + " applied — restart keyboard to see changes",
                        Toast.LENGTH_SHORT).show();
                recreate();
            });

            themesContainer.addView(row);
        }

        // Custom theme entry (always last). Selecting it shows the colour
        // pickers below; unselecting (by tapping any preset) hides them.
        themesContainer.addView(buildCustomThemeRow(customSelected));
        renderCustomThemePanel(customSelected);
    }

    /**
     * Builds the "Custom" theme row that lives below the preset list. Tapping
     * the row flips {@code theme_kind} to {@code custom} and reveals the
     * colour-picker panel; the preset rows remain available so the user can
     * jump back any time.
     */
    private LinearLayout buildCustomThemeRow(boolean active) {
        int bg = prefs.getInt("bg_color", 0xFF1A1A2E);
        int textCol = prefs.getInt("text_color", 0xFFE8E8FF);
        int accent = prefs.getInt("accent_color", 0xFF00E5FF);
        int keyCol = prefs.getInt("key_color", 0xFF252545);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(bg);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, 0, 0, dp(3));
        row.setLayoutParams(rlp);

        int[] swatches = { bg, keyCol, textCol, accent };
        for (int c : swatches) {
            View s = new View(this);
            s.setBackgroundColor(c);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(20), dp(20));
            slp.setMargins(0, 0, dp(4), 0);
            s.setLayoutParams(slp);
            row.addView(s);
        }

        TextView nameView = new TextView(this);
        nameView.setText("✎ Custom");
        nameView.setTextSize(13f);
        nameView.setTextColor(textCol);
        nameView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        nameView.setPadding(dp(10), 0, 0, 0);
        row.addView(nameView);

        if (active) {
            TextView check = new TextView(this);
            check.setText("✓ active");
            check.setTextSize(11f);
            check.setTextColor(accent);
            row.addView(check);
        }

        row.setOnClickListener(v -> {
            prefs.edit().putString("theme_kind", "custom").apply();
            renderThemes();
        });
        return row;
    }

    /**
     * Renders the four colour-picker rows (background / key / text / accent)
     * inside {@link #customThemeContainer}. Only visible when the user has
     * selected the Custom theme entry — preset selections collapse it.
     */
    private void renderCustomThemePanel(boolean visible) {
        if (customThemeContainer == null) return;
        customThemeContainer.removeAllViews();
        customThemeContainer.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) return;

        int bg = prefs.getInt("bg_color", 0xFF1A1A2E);
        int textCol = prefs.getInt("text_color", 0xFFE8E8FF);
        int accent = prefs.getInt("accent_color", 0xFF00E5FF);
        int keyCol = prefs.getInt("key_color", 0xFF252545);

        // Heading hint
        TextView hint = new TextView(this);
        hint.setText("Tap a colour to edit. Long-press the background for dark/light hint.");
        hint.setTextSize(11f);
        hint.setTextColor(dim(textCol));
        hint.setPadding(dp(14), dp(6), dp(14), dp(8));
        customThemeContainer.addView(hint);

        // ── Colours ──
        customThemeContainer.addView(buildColorRow("Background",   "bg_color",     bg));
        customThemeContainer.addView(buildColorRow("Key surface",  "key_color",    keyCol));
        customThemeContainer.addView(buildColorRow("Text",         "text_color",   textCol));
        customThemeContainer.addView(buildColorRow("Accent",       "accent_color", accent));

        // Background-image row.
        customThemeContainer.addView(buildBackgroundImageRow());

        // Dark/Light toggle row.
        Switch sw = new Switch(this);
        sw.setText("Treat as dark theme");
        sw.setTextColor(textCol);
        sw.setChecked(prefs.getBoolean("dark", true));
        sw.setPadding(dp(14), dp(6), dp(14), dp(6));
        sw.setOnCheckedChangeListener((b, c) -> prefs.edit().putBoolean("dark", c).apply());
        customThemeContainer.addView(sw);

        // ── Shape & size ──
        // Folded into the custom theme so the user only has one place to tune
        // their personal look (was previously a separate "Appearance" section).
        customThemeContainer.addView(buildSectionHeader("Shape & size", textCol));
        customThemeContainer.addView(buildIntStepSelector(
                "Key corner radius",
                "How rounded each key looks. 0 = sharp, 28 = pill.",
                "key_radius_dp", 12,
                new int[]{0, 4, 8, 12, 16, 20, 28},
                textCol, bg, accent));
        customThemeContainer.addView(buildIntStepSelector(
                "Key text size",
                "Label size on letter / symbol keys (sp).",
                "key_text_size_sp", 14,
                new int[]{10, 12, 14, 16, 18, 20, 22},
                textCol, bg, accent));
        customThemeContainer.addView(buildIntStepSelector(
                "Key border width",
                "Stroke around each key. 0 hides the border.",
                "key_stroke_width_dp", 0,
                new int[]{0, 1, 2, 3, 4},
                textCol, bg, accent));
        customThemeContainer.addView(buildColorSelector(
                "Key border color",
                "Color of the stroke when border width > 0.",
                "key_stroke_color", 0x00000000,
                new int[]{0x00000000, 0x66FFFFFF, 0xFF888888, 0xFF000000, accent},
                new String[]{"Off", "Soft", "Gray", "Black", "Accent"},
                textCol, bg, accent));
    }

    /**
     * One row in the custom-theme panel that opens the background-image
     * editor. Mirrors {@link #buildColorRow} visually so it slots in
     * cleanly between the colour rows.
     */
    private LinearLayout buildBackgroundImageRow() {
        int textCol = prefs.getInt("text_color", 0xFFE8E8FF);
        int bg      = prefs.getInt("bg_color",   0xFF1A1A2E);
        int accent  = prefs.getInt("accent_color", 0xFF00E5FF);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(8), dp(14), dp(8));
        row.setBackgroundColor(blend(bg, 0xFFFFFFFF, 0.04f));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, 0, 0, dp(2));
        row.setLayoutParams(rlp);

        // Tiny preview swatch — a thumbnail of the selected image, or a
        // dashed accent border if nothing is set yet.
        View swatch = new View(this);
        String uriStr = prefs.getString(PREF_BG_IMAGE_URI, null);
        Bitmap thumb = TextUtils.isEmpty(uriStr) ? null : decodeUriThumbnail(uriStr, dp(28));
        if (thumb != null) {
            swatch.setBackground(new android.graphics.drawable.BitmapDrawable(getResources(), thumb));
        } else {
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setColor(blend(bg, 0xFF000000, 0.2f));
            gd.setStroke(dp(1), accent);
            gd.setCornerRadius(dp(4));
            swatch.setBackground(gd);
        }
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(28), dp(28));
        slp.setMargins(0, 0, dp(10), 0);
        swatch.setLayoutParams(slp);
        row.addView(swatch);

        TextView name = new TextView(this);
        name.setText("Background image");
        name.setTextSize(13f);
        name.setTextColor(textCol);
        name.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(name);

        TextView state = new TextView(this);
        state.setText(TextUtils.isEmpty(uriStr) ? "None" : "Custom");
        state.setTextSize(11f);
        state.setTextColor(dim(textCol));
        state.setPadding(dp(8), 0, dp(8), 0);
        row.addView(state);

        View.OnClickListener open = v -> showBackgroundImageDialog();
        row.setOnClickListener(open);
        swatch.setOnClickListener(open);
        return row;
    }

    /**
     * Background image editor — shows a skeleton keyboard preview overlaid on
     * the chosen image and lets the user tweak opacity before committing.
     * The skeleton is a stylised mock of {@link CodeKeysIME}'s actual layout
     * (suggestion strip + four key rows + spacebar) so users can judge how
     * legible their picture will be once keys sit on top.
     */
    private void showBackgroundImageDialog() {
        final LinearLayout panel = buildThemedDialogPanel("Background image",
                "Pick a picture and adjust opacity. The skeleton below shows roughly "
                        + "how keys will sit on top of it.");

        // Skeleton preview — sized so a phone keyboard's aspect (~3:1) is roughly
        // preserved. Updated whenever the picker returns or the slider moves.
        final KeyboardSkeletonPreview preview = new KeyboardSkeletonPreview(this);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(160));
        plp.setMargins(0, dp(8), 0, dp(8));
        preview.setLayoutParams(plp);
        panel.addView(preview);

        // Opacity slider (SeekBar 0..100). 0% means image fully hidden behind
        // theme colour; 100% means full image alpha.
        final TextView opacityLabel = new TextView(this);
        opacityLabel.setTextSize(12f);
        opacityLabel.setTextColor(prefs.getInt("text_color", 0xFFE8E8FF));
        panel.addView(opacityLabel);

        final SeekBar opacityBar = new SeekBar(this);
        opacityBar.setMax(100);
        int op = Math.max(0, Math.min(100, prefs.getInt(PREF_BG_IMAGE_OPACITY, 70)));
        opacityBar.setProgress(op);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, dp(2), 0, dp(8));
        opacityBar.setLayoutParams(blp);
        panel.addView(opacityBar);

        // Pick / remove buttons — laid out side by side beneath the slider.
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(4), 0, 0);
        Button pick = new Button(this);
        pick.setText("Pick image…");
        pick.setAllCaps(false);
        Button clear = new Button(this);
        clear.setText("Remove image");
        clear.setAllCaps(false);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        alp.setMargins(0, 0, dp(4), 0);
        pick.setLayoutParams(alp);
        LinearLayout.LayoutParams alp2 = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        alp2.setMargins(dp(4), 0, 0, 0);
        clear.setLayoutParams(alp2);
        int accent = prefs.getInt("accent_color", 0xFF00E5FF);
        int bgCol  = prefs.getInt("bg_color",   0xFF1A1A2E);
        int textCol = prefs.getInt("text_color", 0xFFE8E8FF);
        pick.setBackground(themedRoundedFill(blend(bgCol, accent, 0.18f), accent, dp(10), dp(1)));
        pick.setTextColor(accent);
        clear.setBackground(themedRoundedFill(blend(bgCol, 0xFFFFFFFF, 0.05f), dim(textCol), dp(10), dp(1)));
        clear.setTextColor(textCol);
        actions.addView(pick);
        actions.addView(clear);
        panel.addView(actions);

        // Loader — re-reads the URI from prefs (so the picker callback can
        // simply persist the URI then trigger this) and pushes the bitmap
        // into the preview at the current slider position.
        final Runnable refresh = () -> {
            String uri = prefs.getString(PREF_BG_IMAGE_URI, null);
            Bitmap bmp = TextUtils.isEmpty(uri) ? null : decodeUriThumbnail(uri, dp(480));
            preview.setImage(bmp);
            preview.setImageOpacity(opacityBar.getProgress());
            opacityLabel.setText("Opacity: " + opacityBar.getProgress() + "%");
            clear.setEnabled(!TextUtils.isEmpty(uri));
            clear.setAlpha(TextUtils.isEmpty(uri) ? 0.5f : 1f);
        };
        refresh.run();

        opacityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                preview.setImageOpacity(progress);
                opacityLabel.setText("Opacity: " + progress + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        pick.setOnClickListener(v -> {
            // Stash the refresh callback so onActivityResult can re-render
            // the preview without holding a reference to the dialog.
            pendingBgPreviewRefresh = refresh;
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(intent, REQ_PICK_BG_IMAGE);
            } catch (android.content.ActivityNotFoundException ex) {
                // Fall back to legacy gallery intent on devices without SAF.
                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                fallback.setType("image/*");
                startActivityForResult(fallback, REQ_PICK_BG_IMAGE);
            }
        });

        clear.setOnClickListener(v -> {
            prefs.edit().remove(PREF_BG_IMAGE_URI).apply();
            refresh.run();
        });

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setView(wrapInScroll(panel))
                .setPositiveButton("Apply", (d, w) -> {
                    prefs.edit()
                            .putInt(PREF_BG_IMAGE_OPACITY, opacityBar.getProgress())
                            .apply();
                    // Bumping theme_kind to "custom" so the IME knows to
                    // honour the picture instead of falling back to a preset.
                    prefs.edit().putString("theme_kind", "custom").apply();
                    renderCustomThemePanel(true);
                    Toast.makeText(this, "Background applied.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(d -> pendingBgPreviewRefresh = null)
                .create();
        showThemedDialog(dlg);
    }

    /**
     * Decodes an image URI down to roughly {@code targetPx} on its longest
     * edge. Returns {@code null} if the URI cannot be opened or decoded —
     * callers should treat that as "no image set".
     */
    private Bitmap decodeUriThumbnail(String uriStr, int targetPx) {
        if (TextUtils.isEmpty(uriStr) || targetPx <= 0) return null;
        try {
            Uri uri = Uri.parse(uriStr);
            // First pass — bounds only, to compute a reasonable inSampleSize.
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
            try { in.close(); } catch (java.io.IOException ignored) {}
            int sample = 1;
            int max = Math.max(bounds.outWidth, bounds.outHeight);
            while (max / sample > targetPx * 2) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            java.io.InputStream in2 = getContentResolver().openInputStream(uri);
            if (in2 == null) return null;
            Bitmap bmp = BitmapFactory.decodeStream(in2, null, opts);
            try { in2.close(); } catch (java.io.IOException ignored) {}
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Custom view that draws a chosen image as a wallpaper with a stylised
     * keyboard skeleton (suggestion strip + 4 key rows + bottom row) overlaid
     * so users can preview how their image will look behind real keys.
     */
    private final class KeyboardSkeletonPreview extends View {
        private Bitmap image;
        private int imageOpacity = 70; // 0..100
        private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint bgPaint = new Paint();
        private final Paint keyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        KeyboardSkeletonPreview(Context ctx) {
            super(ctx);
            keyPaint.setStyle(Paint.Style.FILL);
            accentPaint.setStyle(Paint.Style.FILL);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(dp(1));
        }

        void setImage(Bitmap bmp) { this.image = bmp; invalidate(); }
        void setImageOpacity(int op) {
            this.imageOpacity = Math.max(0, Math.min(100, op));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;

            int bg = prefs.getInt("bg_color",   0xFF1A1A2E);
            int keyCol = prefs.getInt("key_color", 0xFF252545);
            int textCol = prefs.getInt("text_color", 0xFFE8E8FF);
            int accent = prefs.getInt("accent_color", 0xFF00E5FF);

            // Container background (always paint the theme colour so the
            // preview tracks the rest of the editor even when no image is
            // chosen).
            bgPaint.setColor(bg);
            c.drawRect(0, 0, w, h, bgPaint);

            if (image != null) {
                imagePaint.setAlpha((int) Math.round(imageOpacity / 100.0 * 255));
                // Center-crop draw: scale the bitmap so it covers the preview,
                // matching how the IME will scale it on the keyboard.
                float scale = Math.max((float) w / image.getWidth(),
                        (float) h / image.getHeight());
                float dw = image.getWidth() * scale;
                float dh = image.getHeight() * scale;
                float dx = (w - dw) / 2f;
                float dy = (h - dh) / 2f;
                RectF dest = new RectF(dx, dy, dx + dw, dy + dh);
                c.drawBitmap(image, null, dest, imagePaint);
            }

            // ── Skeleton overlay ───────────────────────────────────────────
            float pad   = dp(6);
            float gap   = dp(3);
            // 6 horizontal slots: suggestion strip, then 4 key rows, then
            // a bottom action row (space + enter).
            float slotH = (h - pad * 2 - gap * 5) / 6f;
            float radius = dp(4);

            keyPaint.setColor(withAlpha(keyCol, 0xCC));
            borderPaint.setColor(withAlpha(textCol, 0x55));
            accentPaint.setColor(withAlpha(accent, 0xCC));

            float y = pad;

            // Row 0 — suggestion strip (3 chips).
            drawChips(c, pad, y, w - pad, y + slotH, 3, radius, false);
            y += slotH + gap;

            // Rows 1..3 — letter rows. Row counts thin out slightly per row
            // to mimic a real QWERTY layout (10 / 9 / 7).
            int[] rowCounts = {10, 9, 7};
            for (int i = 0; i < rowCounts.length; i++) {
                drawChips(c, pad, y, w - pad, y + slotH, rowCounts[i], radius, false);
                y += slotH + gap;
            }

            // Row 4 — symbols / numbers row (12 narrow keys).
            drawChips(c, pad, y, w - pad, y + slotH, 12, radius, false);
            y += slotH + gap;

            // Row 5 — bottom action row: caps | space (wide) | enter (accent).
            float left = pad;
            float right = w - pad;
            float bottom = y + slotH;
            float capsW = (right - left) * 0.18f;
            float enterW = (right - left) * 0.18f;
            float spaceLeft = left + capsW + gap;
            float spaceRight = right - enterW - gap;
            c.drawRoundRect(new RectF(left, y, left + capsW, bottom), radius, radius, keyPaint);
            c.drawRoundRect(new RectF(left, y, left + capsW, bottom), radius, radius, borderPaint);
            c.drawRoundRect(new RectF(spaceLeft, y, spaceRight, bottom), radius, radius, keyPaint);
            c.drawRoundRect(new RectF(spaceLeft, y, spaceRight, bottom), radius, radius, borderPaint);
            c.drawRoundRect(new RectF(spaceRight + gap, y, right, bottom), radius, radius, accentPaint);
            c.drawRoundRect(new RectF(spaceRight + gap, y, right, bottom), radius, radius, borderPaint);
        }

        /**
         * Lays {@code count} equal-width rounded chips between the given X
         * bounds. Used for both the suggestion strip and individual key rows.
         */
        private void drawChips(Canvas c, float left, float top, float right, float bottom,
                               int count, float radius, boolean accentFill) {
            if (count <= 0) return;
            float gap = dp(2);
            float total = right - left;
            float slotW = (total - gap * (count - 1)) / count;
            for (int i = 0; i < count; i++) {
                float x = left + i * (slotW + gap);
                RectF r = new RectF(x, top, x + slotW, bottom);
                c.drawRoundRect(r, radius, radius, accentFill ? accentPaint : keyPaint);
                c.drawRoundRect(r, radius, radius, borderPaint);
            }
        }

        private int withAlpha(int color, int alpha) {
            return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
        }
    }

    /**
     * One colour row: swatch + label + edit button. Tapping any of those opens
     * a small hex / preset chooser dialog. Persists straight into prefs and
     * re-renders the panel so the swatch updates.
     */
    private LinearLayout buildColorRow(String label, final String prefKey, int currentColor) {
        int textCol = prefs.getInt("text_color", 0xFFE8E8FF);
        int bg      = prefs.getInt("bg_color",   0xFF1A1A2E);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(8), dp(14), dp(8));
        row.setBackgroundColor(blend(bg, 0xFFFFFFFF, 0.04f));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, 0, 0, dp(2));
        row.setLayoutParams(rlp);

        View swatch = new View(this);
        swatch.setBackgroundColor(currentColor);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(28), dp(28));
        slp.setMargins(0, 0, dp(10), 0);
        swatch.setLayoutParams(slp);
        row.addView(swatch);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(13f);
        name.setTextColor(textCol);
        name.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(name);

        TextView hex = new TextView(this);
        // Show the alpha byte too so semi-transparent colours don't look the
        // same as their opaque cousins in the row preview.
        int alpha = (currentColor >>> 24) & 0xFF;
        hex.setText(alpha == 0xFF
                ? "#" + String.format("%06X", 0xFFFFFF & currentColor)
                : "#" + String.format("%08X", currentColor));
        hex.setTextSize(11f);
        hex.setTextColor(dim(textCol));
        hex.setPadding(dp(8), 0, dp(8), 0);
        row.addView(hex);

        View.OnClickListener open = v -> showColorPickerDialog(label, prefKey);
        row.setOnClickListener(open);
        swatch.setOnClickListener(open);
        return row;
    }

    /**
     * Lightweight colour picker — shows a grid of common palette options plus
     * a hex input field. Avoids pulling in a third-party color-picker
     * dependency. Saves to {@code prefKey} and re-renders the theme panel.
     *
     * <p>Supports both opaque {@code #RRGGBB} and alpha-aware {@code #AARRGGBB}
     * hex values, with a transparency slider beneath the input and a live
     * preview swatch that tracks edits in real time so the user can see the
     * exact colour (and alpha) they're about to commit.
     */
    private void showColorPickerDialog(String title, final String prefKey) {
        final int[] palette = {
            0xFF000000, 0xFF1A1A2E, 0xFF252545, 0xFF272822, 0xFF282A36,
            0xFF002B36, 0xFF1E1E1E, 0xFFFFFFFF, 0xFFF5F5F5, 0xFFE8E8FF,
            0xFFD4D4D4, 0xFFCCFFCC, 0xFF222222, 0xFF00E5FF, 0xFF00FF88,
            0xFFE6DB74, 0xFFBD93F9, 0xFF2AA198, 0xFF44FF44, 0xFF569CD6,
            0xFF1565C0, 0xFFFF6666, 0xFFFFC107, 0xFFE91E63, 0xFF9C27B0,
            0xFF673AB7, 0xFF3F51B5, 0xFF03A9F4, 0xFF009688, 0xFF4CAF50,
            // Transparent slot so users can clear semi-overlay colours.
            0x00000000
        };

        int textCol = prefs.getInt("text_color", 0xFFE8E8FF);
        int accent  = prefs.getInt("accent_color", 0xFF00E5FF);

        LinearLayout outer = buildThemedDialogPanel("Choose " + title,
                "Pick a swatch, enter a hex colour, or use the alpha slider. "
                        + "Use #AARRGGBB to type alpha by hand.");

        // ── Live preview row ────────────────────────────────────────────
        // A single rounded swatch that updates on every keystroke / palette
        // tap / alpha change so the user can see the exact colour they will
        // commit before pressing Apply.
        final LinearLayout previewRow = new LinearLayout(this);
        previewRow.setOrientation(LinearLayout.HORIZONTAL);
        previewRow.setGravity(Gravity.CENTER_VERTICAL);
        previewRow.setPadding(0, 0, 0, dp(8));

        final View previewSwatch = new View(this);
        LinearLayout.LayoutParams pswLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        pswLp.setMargins(0, 0, dp(12), 0);
        previewSwatch.setLayoutParams(pswLp);

        final TextView previewLabel = new TextView(this);
        previewLabel.setTextSize(12f);
        previewLabel.setTextColor(dim(textCol));
        previewLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        previewRow.addView(previewSwatch);
        previewRow.addView(previewLabel);
        outer.addView(previewRow);

        // ── Hex input + alpha slider ────────────────────────────────────
        final int initialColor = prefs.getInt(prefKey, 0xFF000000);
        final EditText hexInput = buildThemedSingleLineEditText("#AARRGGBB or #RRGGBB",
                "#" + String.format("%08X", initialColor));
        outer.addView(hexInput);

        // Alpha slider: 0 (fully transparent) → 255 (fully opaque).
        TextView alphaLabel = new TextView(this);
        alphaLabel.setTextSize(11f);
        alphaLabel.setTextColor(dim(textCol));
        alphaLabel.setPadding(0, dp(8), 0, 0);
        outer.addView(alphaLabel);

        final SeekBar alphaBar = new SeekBar(this);
        alphaBar.setMax(255);
        alphaBar.setProgress((initialColor >>> 24) & 0xFF);
        outer.addView(alphaBar);

        // Holder for the most recently parsed colour. Apply uses this so we
        // never apply a partially-typed (and unparseable) hex value.
        final int[] currentColor = { initialColor };

        // Refreshes the preview swatch + alpha label given a parsed colour.
        // Centralised here so palette taps, hex edits, and slider drags all
        // funnel through the same renderer. Uses a small inner interface
        // instead of java.util.function.IntConsumer so we stay compatible
        // with minSdk 21 (the standard Java 8 functional types are API 24+).
        final ColorPreviewSink applyPreview = c -> {
            currentColor[0] = c;
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(c);
            bg.setCornerRadius(dp(10));
            bg.setStroke(dp(1), 0x55FFFFFF);
            previewSwatch.setBackground(bg);
            int a = (c >>> 24) & 0xFF;
            previewLabel.setText("#" + String.format("%08X", c) + "   ·   alpha "
                    + Math.round(a * 100f / 255f) + "%");
            int pct = Math.round(a * 100f / 255f);
            alphaLabel.setText("Alpha: " + pct + "%  (" + a + "/255)");
        };
        applyPreview.accept(initialColor);

        // Hex → preview: re-parse on every keystroke. Invalid input is just
        // ignored (the previous valid colour stays in the preview), so the
        // user can type freely without the slider jumping around.
        hexInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                Integer parsed = parseHexColor(s.toString());
                if (parsed == null) return;
                int c = parsed;
                applyPreview.accept(c);
                int a = (c >>> 24) & 0xFF;
                if (alphaBar.getProgress() != a) {
                    alphaBar.setProgress(a);
                }
            }
        });

        // Slider → preview + hex: keep RGB but swap the alpha byte.
        alphaBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int rgb = currentColor[0] & 0x00FFFFFF;
                int c = (progress << 24) | rgb;
                applyPreview.accept(c);
                String newHex = "#" + String.format("%08X", c);
                if (!newHex.equalsIgnoreCase(hexInput.getText().toString())) {
                    hexInput.setText(newHex);
                    hexInput.setSelection(newHex.length());
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        // Swatch grid (6 columns)
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(0, dp(12), 0, 0);
        LinearLayout currentRow = null;
        for (int i = 0; i < palette.length; i++) {
            if (i % 6 == 0) {
                currentRow = new LinearLayout(this);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(currentRow);
            }
            final int c = palette[i];
            View sw = new View(this);
            // Round the swatches so they line up with the rest of the themed UI.
            android.graphics.drawable.GradientDrawable swBg = new android.graphics.drawable.GradientDrawable();
            // Transparent palette entry: draw a subtle outlined "off" swatch.
            swBg.setColor(((c >>> 24) == 0) ? 0x22FFFFFF : c);
            swBg.setCornerRadius(dp(8));
            swBg.setStroke(dp(1), ((c >>> 24) == 0) ? accent : 0x33FFFFFF);
            sw.setBackground(swBg);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(40), dp(40));
            slp.setMargins(dp(3), dp(3), dp(3), dp(3));
            sw.setLayoutParams(slp);
            sw.setOnClickListener(v -> {
                // Tapping a swatch fills the hex field; the TextWatcher above
                // updates the preview + alpha slider, so this single line
                // keeps every control synchronised.
                hexInput.setText("#" + String.format("%08X", c));
            });
            currentRow.addView(sw);
        }
        outer.addView(grid);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setView(wrapInScroll(outer))
                .setPositiveButton("Apply", (d, w) -> {
                    Integer parsed = parseHexColor(hexInput.getText().toString());
                    if (parsed == null) {
                        Toast.makeText(this, "Invalid hex colour", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    prefs.edit().putInt(prefKey, parsed).apply();
                    renderThemes();
                })
                .setNegativeButton("Cancel", null)
                .create();
        showThemedDialog(dlg);
    }

    /**
     * Parses a 3 / 6 / 8-digit hex colour string (with or without leading
     * {@code #}). Returns {@code null} if the input doesn't represent a valid
     * colour so callers can keep the previous value.
     *
     * <ul>
     *   <li>{@code RGB}      — short form, expanded to {@code RRGGBB}, opaque.</li>
     *   <li>{@code RRGGBB}   — opaque colour ({@code FF} alpha forced).</li>
     *   <li>{@code AARRGGBB} — full alpha-aware ARGB int.</li>
     * </ul>
     */
    private static Integer parseHexColor(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.isEmpty()) return null;
        try {
            if (s.length() == 3) {
                StringBuilder expanded = new StringBuilder("FF");
                for (int i = 0; i < 3; i++) {
                    char ch = s.charAt(i);
                    expanded.append(ch).append(ch);
                }
                return (int) Long.parseLong(expanded.toString(), 16);
            }
            if (s.length() == 6) {
                long v = Long.parseLong(s, 16);
                return (int) (v | 0xFF000000L);
            }
            if (s.length() == 8) {
                return (int) Long.parseLong(s, 16);
            }
            return null;
        } catch (NumberFormatException ex) {
            return null;
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
            // Wrap_content width so each button is sized to its label — long
            // names like "TYPESCRIPT" no longer get truncated and short ones
            // ("C") don't waste space. Horizontal scrolling is provided by the
            // surrounding HorizontalScrollView in settings_activity.xml.
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(44));
            lp.setMargins(dp(2), 0, dp(2), 0);
            btn.setLayoutParams(lp);
            btn.setMinWidth(dp(56));
            btn.setPadding(dp(14), 0, dp(14), 0);
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
        LinearLayout panel = buildThemedDialogPanel("Snippets — " + lang,
                "Tap Add snippet to extend this language; trigger words expand " +
                "into the saved text when typed.");
        // renderSnippetList wipes its container, so use a nested holder so
        // the panel's header/subtitle survive refresh().
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        panel.addView(list);

        final Runnable refresh = () -> renderSnippetList(list, lang, snippets);
        refresh.run();

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setView(wrapInScroll(panel))
                .setPositiveButton("Add snippet", null)
                .setNegativeButton("Done", null)
                .create();
        showThemedDialog(dlg);

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
        LinearLayout container = buildThemedDialogPanel(
                existing == null ? "Add snippet" : "Edit snippet",
                "Trigger words expand into the saved text when typed.");

        final EditText trigger = buildThemedSingleLineEditText("Trigger (e.g. fn)",
                existing != null ? existing[0] : null);
        container.addView(trigger);

        final EditText expansion = buildThemedMultilineEditText(
                "Expansion (multi-line allowed)",
                existing != null ? existing[1] : null, 3);
        container.addView(expansion);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setView(wrapInScroll(container))
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
                .create();
        showThemedDialog(dlg);
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
