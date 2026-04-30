package com.codekeys.ime;

import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SuggestionEngine — produces an ordered candidate list for the suggestion
 * strip and learns from the user's typing so frequently-confirmed words rank
 * higher across sessions.
 *
 * <p>Sources, in priority order:
 *   <ol>
 *     <li>Snippet triggers (provided by the IME) for the active language.</li>
 *     <li>Common-typo autocorrect for the highest-confidence single fix.</li>
 *     <li>{@link CommonWords} dictionary, prefix-matched.</li>
 *     <li>The literal current word (so the user can lock it in).</li>
 *   </ol>
 *
 * <p>Within the dictionary slice, candidates are re-ranked by a per-word
 * frequency counter that is incremented every time the user accepts a
 * suggestion or commits a space-terminated word — the same signals Gboard's
 * personal dictionary uses.
 */
final class SuggestionEngine {

    /** Pref key for the persisted frequency map ("word\u0001count"-delimited). */
    private static final String PREFS_KEY = "user_word_freq";
    /** Cap on candidates returned to the strip — readability vs. recall trade-off. */
    private static final int MAX_RESULTS = 8;

    private final SharedPreferences prefs;
    /** In-memory mirror of the persisted frequency map. */
    private final HashMap<String, Integer> freq = new HashMap<>();

    SuggestionEngine(SharedPreferences prefs) {
        this.prefs = prefs;
        load();
    }

    /** Builds the ranked candidate list for the given prefix. */
    List<String> compute(String prefix, List<String[]> snippets) {
        if (TextUtils.isEmpty(prefix)) return Collections.emptyList();
        String lower = prefix.toLowerCase();
        ArrayList<String> out = new ArrayList<>();

        // 1. Snippet triggers.
        if (snippets != null) {
            for (String[] s : snippets) {
                if (s == null || s.length == 0) continue;
                String trigger = s[0];
                if (trigger.startsWith(lower) && !trigger.equals(lower) && !out.contains(trigger)) {
                    out.add(trigger);
                }
            }
        }

        // 2. Single-best typo fix.
        String fix = COMMON_TYPOS.get(lower);
        if (fix != null && !out.contains(fix)) out.add(fix);

        // 3. Dictionary prefix-match, ranked by learned frequency then length.
        ArrayList<String> dictMatches = new ArrayList<>();
        for (String w : CommonWords.all()) {
            if (w.length() <= lower.length()) continue;
            if (w.startsWith(lower)) dictMatches.add(w);
        }
        Collections.sort(dictMatches, (a, b) -> {
            int fa = freq.getOrDefault(a, 0);
            int fb = freq.getOrDefault(b, 0);
            if (fa != fb) return Integer.compare(fb, fa); // higher count first
            return Integer.compare(a.length(), b.length()); // shorter wins on tie
        });
        boolean upper = Character.isUpperCase(prefix.charAt(0));
        for (String w : dictMatches) {
            if (out.size() >= MAX_RESULTS) break;
            String cased = upper ? Character.toUpperCase(w.charAt(0)) + w.substring(1) : w;
            if (!out.contains(cased)) out.add(cased);
        }

        // 4. Locked-in literal as a final option (always there for "I really meant this").
        if (!out.contains(prefix)) out.add(prefix);
        if (out.size() > MAX_RESULTS) out.subList(MAX_RESULTS, out.size()).clear();
        return out;
    }

    /** Records that the user accepted / typed {@code word}. Rank-only signal. */
    void learn(String word) {
        if (TextUtils.isEmpty(word) || word.length() < 2) return;
        String key = word.toLowerCase();
        Integer cur = freq.get(key);
        freq.put(key, (cur == null ? 0 : cur) + 1);
        save();
    }

    private void load() {
        String raw = prefs.getString(PREFS_KEY, "");
        if (TextUtils.isEmpty(raw)) return;
        for (String pair : raw.split("\u0002")) {
            int sep = pair.indexOf('\u0001');
            if (sep <= 0 || sep >= pair.length() - 1) continue;
            try {
                freq.put(pair.substring(0, sep), Integer.parseInt(pair.substring(sep + 1)));
            } catch (NumberFormatException ignored) { /* skip */ }
        }
    }

    private void save() {
        // Only persist top ~256 entries to bound the SharedPreferences blob.
        ArrayList<Map.Entry<String, Integer>> entries = new ArrayList<>(freq.entrySet());
        Collections.sort(entries, (a, b) -> Integer.compare(b.getValue(), a.getValue()));
        if (entries.size() > 256) entries = new ArrayList<>(entries.subList(0, 256));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append('\u0002');
            sb.append(entries.get(i).getKey()).append('\u0001').append(entries.get(i).getValue());
        }
        prefs.edit().putString(PREFS_KEY, sb.toString()).apply();
    }

    /** Tiny, intentionally-small autocorrect map. */
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
}
