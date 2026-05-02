package com.codekeys.ime;

import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
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
 *     <li>Personal dictionary — every word the user has ever typed, ranked by
 *         their own usage frequency. Surfaces favourites that aren't in the
 *         built-in dictionary.</li>
 *     <li>{@link CommonWords} dictionary, prefix-matched and re-ranked by the
 *         same per-word frequency counter.</li>
 *     <li>The literal current word (so the user can lock it in).</li>
 *   </ol>
 *
 * <p>Casing follows what the user is typing:
 *   <ul>
 *     <li>All-caps prefix (≥2 letters, every char upper) → suggestions are
 *         emitted upper-case so {@code AB} suggests {@code ABOUT}, not
 *         {@code About}. Mirrors how desktop IMEs auto-shout.</li>
 *     <li>First-letter-upper prefix → first letter capitalised
 *         ({@code Ab} → {@code About}).</li>
 *     <li>Otherwise → lower-case as the dictionary stores it.</li>
 *   </ul>
 */
final class SuggestionEngine {

    /** Pref key for the persisted frequency map ("word\u0001count"-delimited). */
    private static final String PREFS_KEY = "user_word_freq";
    /** Cap on candidates returned to the strip — readability vs. recall trade-off. */
    private static final int MAX_RESULTS = 8;
    /** Minimum length of a word to be persisted to the personal dictionary. */
    private static final int MIN_LEARN_LEN = 2;

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

        // Casing modes — follow what the user is typing so SHOUTED prefixes
        // get SHOUTED completions. We need at least 2 upper-case letters to
        // confidently say the user is typing all-caps (a single capital is
        // just sentence-case).
        final boolean allUpper = isAllUpper(prefix);
        final boolean firstUpper = !allUpper && Character.isUpperCase(prefix.charAt(0));

        // 1. Snippet triggers (always lowercase — those are user-defined IDs
        //    so we don't second-guess their casing).
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
        if (fix != null) {
            String fixCased = applyCasing(fix, allUpper, firstUpper);
            if (!out.contains(fixCased)) out.add(fixCased);
        }

        // 3. Personal-dictionary matches first — words the user actually uses
        //    rank above the generic dictionary so {@code "amzn"} keeps winning
        //    over alphabetical neighbours after the user has typed it a few
        //    times.
        ArrayList<String> userMatches = new ArrayList<>();
        for (Map.Entry<String, Integer> e : freq.entrySet()) {
            String w = e.getKey();
            if (w.length() <= lower.length()) continue;
            if (w.startsWith(lower)) userMatches.add(w);
        }
        Collections.sort(userMatches, (a, b) -> {
            int fa = freq.getOrDefault(a, 0);
            int fb = freq.getOrDefault(b, 0);
            if (fa != fb) return Integer.compare(fb, fa);
            return Integer.compare(a.length(), b.length());
        });
        for (String w : userMatches) {
            if (out.size() >= MAX_RESULTS) break;
            String cased = applyCasing(w, allUpper, firstUpper);
            if (!out.contains(cased)) out.add(cased);
        }

        // 4. Built-in dictionary, ranked by learned frequency then length.
        ArrayList<String> dictMatches = new ArrayList<>();
        for (String w : CommonWords.all()) {
            if (w.length() <= lower.length()) continue;
            if (w.startsWith(lower)) dictMatches.add(w);
        }
        Collections.sort(dictMatches, (a, b) -> {
            int fa = freq.getOrDefault(a, 0);
            int fb = freq.getOrDefault(b, 0);
            if (fa != fb) return Integer.compare(fb, fa);
            return Integer.compare(a.length(), b.length());
        });
        for (String w : dictMatches) {
            if (out.size() >= MAX_RESULTS) break;
            String cased = applyCasing(w, allUpper, firstUpper);
            if (!out.contains(cased)) out.add(cased);
        }

        // 5. Locked-in literal as a final option (always there for "I really meant this").
        if (!out.contains(prefix)) out.add(prefix);
        if (out.size() > MAX_RESULTS) out.subList(MAX_RESULTS, out.size()).clear();
        return out;
    }

    /** Records that the user accepted / typed {@code word}. Rank-only signal. */
    void learn(String word) {
        if (TextUtils.isEmpty(word) || word.length() < MIN_LEARN_LEN) return;
        // Only learn alphabetic words — punctuation, numbers, and mixed-symbol
        // tokens shouldn't pollute the personal dictionary.
        for (int i = 0; i < word.length(); i++) {
            if (!Character.isLetter(word.charAt(i))) return;
        }
        String key = word.toLowerCase();
        Integer cur = freq.get(key);
        freq.put(key, (cur == null ? 0 : cur) + 1);
        save();
    }

    private static boolean isAllUpper(String s) {
        if (s == null || s.length() < 2) return false;
        boolean anyLetter = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                anyLetter = true;
                if (!Character.isUpperCase(c)) return false;
            }
        }
        return anyLetter;
    }

    /** Applies the prefix's casing flavour to a lower-case dictionary word. */
    private static String applyCasing(String lowerWord, boolean allUpper, boolean firstUpper) {
        if (TextUtils.isEmpty(lowerWord)) return lowerWord;
        if (allUpper) return lowerWord.toUpperCase();
        if (firstUpper) {
            return Character.toUpperCase(lowerWord.charAt(0)) + lowerWord.substring(1);
        }
        return lowerWord;
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
