package com.codekeys.ime;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads keyboard data that used to be hardcoded in {@code CodeKeysIME} and
 * {@code SettingsActivity} from JSON files inside {@code app/main/assets}:
 *
 * <ul>
 *   <li>{@code themes.json} — theme palette presets shown in Settings.</li>
 *   <li>{@code snippets.json} — per-language snippet shortcuts.</li>
 *   <li>{@code lang_symbols.json} — symbol toolbar entries per language.</li>
 *   <li>{@code settings_defaults.json} — default {@link SharedPreferences}
 *       values seeded on first launch.</li>
 * </ul>
 *
 * <p>These files exist so users can ship new themes / snippets / symbol sets
 * without code changes — the JSON shape is documented inline in each file.
 * Every loader degrades silently to an empty result if the asset is missing
 * or malformed; callers should provide their own hard-coded fallbacks.
 */
final class AssetDefaults {

    private AssetDefaults() {}

    /** A single theme as defined by {@code themes.json}. */
    static final class Theme {
        final String name;
        final boolean dark;
        final int bgColor, keyColor, textColor, accentColor;

        Theme(String name, boolean dark, int bg, int key, int text, int accent) {
            this.name = name;
            this.dark = dark;
            this.bgColor = bg;
            this.keyColor = key;
            this.textColor = text;
            this.accentColor = accent;
        }
    }

    // ─── Loaders ──────────────────────────────────────────────────────────────

    /**
     * Reads {@code assets/themes.json} into a list of themes. Returns an empty
     * list (never null) on any failure so callers can fall back to a hardcoded
     * default without needing to handle exceptions.
     */
    static List<Theme> loadThemes(Context ctx) {
        List<Theme> out = new ArrayList<>();
        String raw = readAsset(ctx, "themes.json");
        if (raw == null) return out;
        try {
            JSONArray arr = new JSONObject(raw).optJSONArray("themes");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("name", "Theme " + (i + 1));
                boolean dark = o.optBoolean("dark", true);
                int bg     = parseColor(o.optString("bg_color", "#1A1A2E"));
                int key    = parseColor(o.optString("key_color", "#252545"));
                int text   = parseColor(o.optString("text_color", "#E8E8FF"));
                int accent = parseColor(o.optString("accent_color", "#00E5FF"));
                out.add(new Theme(name, dark, bg, key, text, accent));
            }
        } catch (JSONException ignored) { /* empty list signals failure */ }
        return out;
    }

    /**
     * Reads {@code snippets.json} into a {@code lang -> [{trigger, expansion}]}
     * map. The order of triggers is preserved (LinkedHashMap) so toolbar
     * layouts remain deterministic.
     */
    static Map<String, List<String[]>> loadSnippets(Context ctx) {
        Map<String, List<String[]>> out = new LinkedHashMap<>();
        String raw = readAsset(ctx, "snippets.json");
        if (raw == null) return out;
        try {
            JSONObject langs = new JSONObject(raw).optJSONObject("languages");
            if (langs == null) return out;
            for (java.util.Iterator<String> it = langs.keys(); it.hasNext(); ) {
                String lang = it.next();
                JSONArray arr = langs.optJSONArray(lang);
                if (arr == null) continue;
                List<String[]> rows = new ArrayList<>(arr.length());
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject row = arr.optJSONObject(i);
                    if (row == null) continue;
                    String t = row.optString("trigger", "");
                    String e = row.optString("expansion", "");
                    if (!TextUtils.isEmpty(t) && !TextUtils.isEmpty(e)) {
                        rows.add(new String[]{ t, e });
                    }
                }
                if (!rows.isEmpty()) out.put(lang, rows);
            }
        } catch (JSONException ignored) {}
        return out;
    }

    /** Reads {@code lang_symbols.json} into a {@code lang -> symbols} map. */
    static Map<String, String[]> loadLangSymbols(Context ctx) {
        Map<String, String[]> out = new HashMap<>();
        String raw = readAsset(ctx, "lang_symbols.json");
        if (raw == null) return out;
        try {
            JSONObject langs = new JSONObject(raw).optJSONObject("languages");
            if (langs == null) return out;
            for (java.util.Iterator<String> it = langs.keys(); it.hasNext(); ) {
                String lang = it.next();
                JSONArray arr = langs.optJSONArray(lang);
                if (arr == null) continue;
                String[] syms = new String[arr.length()];
                for (int i = 0; i < arr.length(); i++) syms[i] = arr.optString(i, "");
                out.put(lang, syms);
            }
        } catch (JSONException ignored) {}
        return out;
    }

    /**
     * Seeds {@link SharedPreferences} from {@code settings_defaults.json} —
     * but only for keys the user has never set. Idempotent and safe to call
     * on every launch; existing user customisations are preserved.
     */
    static void seedDefaults(Context ctx, SharedPreferences prefs) {
        String raw = readAsset(ctx, "settings_defaults.json");
        if (raw == null) return;
        try {
            JSONObject p = new JSONObject(raw).optJSONObject("prefs");
            if (p == null) return;
            SharedPreferences.Editor ed = prefs.edit();
            for (java.util.Iterator<String> it = p.keys(); it.hasNext(); ) {
                String k = it.next();
                if (prefs.contains(k)) continue;
                Object v = p.get(k);
                if (v instanceof Boolean) {
                    ed.putBoolean(k, (Boolean) v);
                } else if (v instanceof Integer) {
                    ed.putInt(k, (Integer) v);
                } else if (v instanceof Long) {
                    ed.putLong(k, (Long) v);
                } else if (v instanceof Double || v instanceof Float) {
                    ed.putFloat(k, ((Number) v).floatValue());
                } else if (v instanceof String) {
                    String s = (String) v;
                    // Hex colour strings (#RRGGBB / #AARRGGBB) are stored as ints
                    // so the IME's prefs.getInt() path keeps working.
                    if (s.startsWith("#")) {
                        int parsed = parseColor(s);
                        ed.putInt(k, parsed);
                    } else {
                        ed.putString(k, s);
                    }
                }
            }
            ed.apply();
        } catch (JSONException ignored) {}
    }

    // ─── Internals ────────────────────────────────────────────────────────────

    private static String readAsset(Context ctx, String name) {
        try (InputStream in = ctx.getAssets().open(name);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }

    private static int parseColor(String s) {
        if (TextUtils.isEmpty(s)) return 0xFF000000;
        try {
            int c = Color.parseColor(s);
            // Force opaque alpha when caller used #RRGGBB.
            if (s.length() == 7) c |= 0xFF000000;
            return c;
        } catch (IllegalArgumentException ex) {
            return 0xFF000000;
        }
    }
}
