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
    /** Pref key for the persisted bigram map ("prev word\u0001next word\u0001count"). */
    private static final String BIGRAM_KEY = "user_word_bigrams";
    /** Cap on candidates returned to the strip — readability vs. recall trade-off. */
    private static final int MAX_RESULTS = 3;
    /** Minimum length of a word to be persisted to the personal dictionary. */
    private static final int MIN_LEARN_LEN = 2;

    private final SharedPreferences prefs;
    /** In-memory mirror of the persisted frequency map. */
    private final HashMap<String, Integer> freq = new HashMap<>();
    /**
     * In-memory bigram model: prev → (next → count). Built up as the user
     * types so "next word" predictions reflect actual usage rather than just
     * a static curated table. Learned at every word boundary (space / punct
     * / Enter) by {@link #learnBigram(String, String)}.
     */
    private final HashMap<String, HashMap<String, Integer>> bigrams = new HashMap<>();
    /** Last word that was learned — used to chain into the next bigram. */
    private String lastLearnedWord = null;

    SuggestionEngine(SharedPreferences prefs) {
        this.prefs = prefs;
        load();
        loadBigrams();
    }

    private static final Map<String, String[]> PREDICTIONS = new HashMap<>();
    static {
        // Conversational
        PREDICTIONS.put("how", new String[]{"are", "to", "do"});
        PREDICTIONS.put("are", new String[]{"you", "they", "we"});
        PREDICTIONS.put("you", new String[]{"are", "can", "want"});
        PREDICTIONS.put("i",   new String[]{"am", "was", "will"});
        PREDICTIONS.put("it",  new String[]{"is", "was", "has"});
        PREDICTIONS.put("what",new String[]{"is", "are", "do"});
        PREDICTIONS.put("thank",new String[]{"you", "for"});
        PREDICTIONS.put("please",new String[]{"let", "find", "check"});
        PREDICTIONS.put("can", new String[]{"you", "I", "we"});
        PREDICTIONS.put("will",new String[]{"be", "you", "not"});
        PREDICTIONS.put("the", new String[]{"same", "first", "last"});
        PREDICTIONS.put("a",   new String[]{"new", "good", "few"});
        PREDICTIONS.put("of",  new String[]{"the", "course", "this"});
        PREDICTIONS.put("to",  new String[]{"be", "the", "do"});
        PREDICTIONS.put("in",  new String[]{"the", "a", "this"});
        PREDICTIONS.put("on",  new String[]{"the", "my", "this"});
        PREDICTIONS.put("at",  new String[]{"the", "home", "least"});
        PREDICTIONS.put("is",  new String[]{"the", "a", "not"});
        PREDICTIONS.put("was", new String[]{"a", "the", "not"});
        PREDICTIONS.put("we",  new String[]{"are", "have", "can"});
        PREDICTIONS.put("they",new String[]{"are", "have", "will"});
        PREDICTIONS.put("good",new String[]{"morning", "evening", "night"});
        PREDICTIONS.put("let", new String[]{"me", "us", "you"});
        PREDICTIONS.put("see", new String[]{"you", "the", "if"});
        PREDICTIONS.put("got", new String[]{"it", "the", "to"});

        // Coding
        PREDICTIONS.put("if",     new String[]{"(", "true", "condition"});
        PREDICTIONS.put("public", new String[]{"class", "void", "static"});
        PREDICTIONS.put("private",new String[]{"class", "void", "static"});
        PREDICTIONS.put("static", new String[]{"void", "final", "int"});
        PREDICTIONS.put("void",   new String[]{"main", "functionName"});
        PREDICTIONS.put("String", new String[]{"args", "text", "name"});
        PREDICTIONS.put("for",    new String[]{"(int", "item", "i"});
        PREDICTIONS.put("import", new String[]{"java", "android", "static"});
        PREDICTIONS.put("new",    new String[]{"String", "ArrayList", "HashMap"});
        PREDICTIONS.put("return", new String[]{"true", "false", "null"});
        PREDICTIONS.put("else",   new String[]{"if", "{", "return"});
        PREDICTIONS.put("const",  new String[]{"int", "char", "auto"});
        PREDICTIONS.put("let",    new String[]{"x", "y", "value"});
        PREDICTIONS.put("System.out.println", new String[]{"(", "\"\""});
    }

    /**
     * Returns up to {@link #MAX_RESULTS} candidates for the word that should
     * follow {@code prev}. Combines two sources, with learned bigrams
     * winning over the curated table when they disagree:
     *
     * <ol>
     *   <li>Personal bigram model — what the user has actually typed after
     *       {@code prev} before, ranked by frequency. This is what makes
     *       suggestions feel "professional" — they reflect the user's own
     *       phrasing instead of generic boilerplate.</li>
     *   <li>Curated {@link #PREDICTIONS} fallback for cold-start cases
     *       where the user hasn't yet typed enough for the bigram model
     *       to have any signal.</li>
     * </ol>
     */
    List<String> computeNextWord(String prev) {
        if (TextUtils.isEmpty(prev)) return Collections.emptyList();
        String lower = prev.toLowerCase();
        ArrayList<String> out = new ArrayList<>();

        // 1. Personal bigram model — sort by frequency, surface top entries.
        HashMap<String, Integer> followers = bigrams.get(lower);
        if (followers != null && !followers.isEmpty()) {
            ArrayList<Map.Entry<String, Integer>> entries = new ArrayList<>(followers.entrySet());
            Collections.sort(entries, (a, b) -> Integer.compare(b.getValue(), a.getValue()));
            for (Map.Entry<String, Integer> e : entries) {
                if (out.size() >= MAX_RESULTS) break;
                if (!out.contains(e.getKey())) out.add(e.getKey());
            }
        }

        // 2. Curated table — fill remaining slots with the static suggestions
        //    so cold-start typing still produces meaningful predictions.
        String[] preds = PREDICTIONS.get(lower);
        if (preds != null) {
            for (String p : preds) {
                if (out.size() >= MAX_RESULTS) break;
                if (!out.contains(p)) out.add(p);
            }
        }
        return out;
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

        // 2. Single-best typo fix — first the curated map, then a fuzzy
        //    fallback against the built-in dictionary for short prefixes
        //    (edit-distance 1) so common typos like "thnk" → "think" or
        //    "recieve" → "receive" still surface even if they aren't in
        //    the curated table.
        String fix = COMMON_TYPOS.get(lower);
        if (fix == null) fix = fuzzyTypoFix(lower);
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
            if (w.toLowerCase().startsWith(lower)) dictMatches.add(w);
        }
        Collections.sort(dictMatches, (a, b) -> {
            int fa = freq.getOrDefault(a.toLowerCase(), 0);
            int fb = freq.getOrDefault(b.toLowerCase(), 0);
            if (fa != fb) return Integer.compare(fb, fa);
            return Integer.compare(a.length(), b.length());
        });
        for (String w : dictMatches) {
            if (out.size() >= MAX_RESULTS) break;
            // If the word in the dictionary is already cased (like "Android Studio"),
            // use it as-is. Otherwise, apply typing-context casing.
            boolean isPreCased = false;
            for (int i = 0; i < w.length(); i++) {
                if (Character.isUpperCase(w.charAt(i)) || w.charAt(i) == ' ') {
                    isPreCased = true; break;
                }
            }
            String cased = isPreCased ? w : applyCasing(w, allUpper, firstUpper);
            if (!out.contains(cased)) out.add(cased);
        }

        // 5. Locked-in literal as a final option (always there for "I really meant this").
        if (!out.contains(prefix)) out.add(prefix);
        if (out.size() > MAX_RESULTS) out.subList(MAX_RESULTS, out.size()).clear();
        return out;
    }

    /** Records that the user accepted / typed {@code word}. Rank-only signal.
     *  Also chains into the bigram model so {@code lastLearnedWord → word}
     *  becomes a future next-word prediction. */
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

        // Chain bigram: previous word → this word. Keeps the model tight to
        // the actual phrase the user is typing right now.
        if (lastLearnedWord != null) {
            learnBigram(lastLearnedWord, key);
        }
        lastLearnedWord = key;
    }

    /** Resets the bigram context so the next {@link #learn(String)} call
     *  starts a fresh chain. Call after sentence-ending punctuation
     *  (period, question, exclamation) or paragraph breaks so bigrams
     *  don't span unrelated sentences. */
    void resetBigramContext() {
        lastLearnedWord = null;
    }

    /** Increments the count for {@code prev → next} in the bigram map and
     *  persists it. Both arguments are expected to already be lower-cased
     *  alphabetic word keys (mirrors {@link #freq}). */
    private void learnBigram(String prev, String next) {
        if (TextUtils.isEmpty(prev) || TextUtils.isEmpty(next)) return;
        HashMap<String, Integer> followers = bigrams.get(prev);
        if (followers == null) {
            followers = new HashMap<>();
            bigrams.put(prev, followers);
        }
        Integer cur = followers.get(next);
        followers.put(next, (cur == null ? 0 : cur) + 1);
        saveBigrams();
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

    /**
     * Bigram persistence format:
     *   prev\u0001next\u0001count\u0002prev\u0001next\u0001count...
     * Mirrors the {@link #save()} layout but with three fields per record.
     */
    private void loadBigrams() {
        String raw = prefs.getString(BIGRAM_KEY, "");
        if (TextUtils.isEmpty(raw)) return;
        for (String triple : raw.split("\u0002")) {
            String[] parts = triple.split("\u0001");
            if (parts.length != 3) continue;
            try {
                int count = Integer.parseInt(parts[2]);
                HashMap<String, Integer> followers = bigrams.get(parts[0]);
                if (followers == null) {
                    followers = new HashMap<>();
                    bigrams.put(parts[0], followers);
                }
                followers.put(parts[1], count);
            } catch (NumberFormatException ignored) { /* skip */ }
        }
    }

    private void saveBigrams() {
        // Cap to top 512 (prev,next) pairs by frequency so the blob stays small.
        ArrayList<String[]> flat = new ArrayList<>();
        for (Map.Entry<String, HashMap<String, Integer>> outer : bigrams.entrySet()) {
            for (Map.Entry<String, Integer> inner : outer.getValue().entrySet()) {
                flat.add(new String[]{outer.getKey(), inner.getKey(), Integer.toString(inner.getValue())});
            }
        }
        Collections.sort(flat, (a, b) -> Integer.compare(Integer.parseInt(b[2]), Integer.parseInt(a[2])));
        if (flat.size() > 512) flat = new ArrayList<>(flat.subList(0, 512));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < flat.size(); i++) {
            if (i > 0) sb.append('\u0002');
            sb.append(flat.get(i)[0]).append('\u0001').append(flat.get(i)[1]).append('\u0001').append(flat.get(i)[2]);
        }
        prefs.edit().putString(BIGRAM_KEY, sb.toString()).apply();
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
        COMMON_TYPOS.put("thier", "their");
        COMMON_TYPOS.put("wich", "which");
        COMMON_TYPOS.put("wierd", "weird");
        COMMON_TYPOS.put("alot", "a lot");
        COMMON_TYPOS.put("becuase", "because");
        COMMON_TYPOS.put("becouse", "because");
        COMMON_TYPOS.put("acheive", "achieve");
        COMMON_TYPOS.put("beleive", "believe");
        COMMON_TYPOS.put("freind", "friend");
        COMMON_TYPOS.put("tommorow", "tomorrow");
        COMMON_TYPOS.put("tomarrow", "tomorrow");
        COMMON_TYPOS.put("alright", "alright");
        COMMON_TYPOS.put("alright", "alright");
        COMMON_TYPOS.put("occured", "occurred");
        COMMON_TYPOS.put("succesful", "successful");
        COMMON_TYPOS.put("succesfull", "successful");
        COMMON_TYPOS.put("neccessary", "necessary");
        COMMON_TYPOS.put("neccesary", "necessary");
        COMMON_TYPOS.put("calender", "calendar");
        COMMON_TYPOS.put("goverment", "government");
        COMMON_TYPOS.put("enviroment", "environment");
        COMMON_TYPOS.put("publically", "publicly");
        COMMON_TYPOS.put("yhe", "the");
        COMMON_TYPOS.put("hte", "the");
        COMMON_TYPOS.put("waht", "what");
        COMMON_TYPOS.put("taht", "that");
        COMMON_TYPOS.put("youre", "you're");
        COMMON_TYPOS.put("dont", "don't");
        COMMON_TYPOS.put("cant", "can't");
        COMMON_TYPOS.put("wont", "won't");
        COMMON_TYPOS.put("isnt", "isn't");
        COMMON_TYPOS.put("didnt", "didn't");
        COMMON_TYPOS.put("doesnt", "doesn't");
        COMMON_TYPOS.put("wasnt", "wasn't");
        COMMON_TYPOS.put("werent", "weren't");
        COMMON_TYPOS.put("hasnt", "hasn't");
        COMMON_TYPOS.put("havent", "haven't");
        COMMON_TYPOS.put("im", "I'm");
        COMMON_TYPOS.put("ive", "I've");
        COMMON_TYPOS.put("ill", "I'll");
        COMMON_TYPOS.put("thnak", "thank");
        COMMON_TYPOS.put("thnk", "think");
        COMMON_TYPOS.put("knwo", "know");
        COMMON_TYPOS.put("liek", "like");
        COMMON_TYPOS.put("becuse", "because");
        COMMON_TYPOS.put("strign", "string");
        COMMON_TYPOS.put("lenght", "length");
        COMMON_TYPOS.put("widht", "width");
        COMMON_TYPOS.put("hieght", "height");
        COMMON_TYPOS.put("priavte", "private");
        COMMON_TYPOS.put("pulbic", "public");
        COMMON_TYPOS.put("statci", "static");
        COMMON_TYPOS.put("voide", "void");
        COMMON_TYPOS.put("contect", "context");
        COMMON_TYPOS.put("activty", "activity");
        COMMON_TYPOS.put("layoyt", "layout");
        COMMON_TYPOS.put("buton", "button");
        COMMON_TYPOS.put("ediittext", "edittext");
        COMMON_TYPOS.put("textveiw", "textview");
    }

    /**
     * Tries to pick a single best dictionary word that is at edit distance
     * 1 from {@code lower}. Returns {@code null} if no clear single fix
     * exists (we want exactly-one candidate so the suggestion strip stays
     * trustworthy — multiple equal-distance matches would be guesses).
     *
     * Bounded to short words because Levenshtein over the whole dictionary
     * is O(N · L²) and we only run on every keystroke.
     */
    private static String fuzzyTypoFix(String lower) {
        if (lower == null) return null;
        int len = lower.length();
        // Only correct meaningful, short, all-letter tokens. 3 chars is the
        // floor where a 1-edit fix is more likely than a legitimate prefix.
        if (len < 4 || len > 10) return null;
        for (int i = 0; i < len; i++) {
            if (!Character.isLetter(lower.charAt(i))) return null;
        }
        // If the literal word is already in the dictionary, it's not a typo.
        for (String w : CommonWords.all()) {
            if (w.equals(lower)) return null;
        }
        String best = null;
        int bestCount = 0;
        for (String w : CommonWords.all()) {
            int wl = w.length();
            if (Math.abs(wl - len) > 1) continue;
            if (editDistanceWithin1(lower, w)) {
                if (best == null) {
                    best = w;
                    bestCount = 1;
                } else {
                    bestCount++;
                    if (bestCount > 3) return null; // ambiguous
                }
            }
        }
        return best;
    }

    /** True when {@code a} and {@code b} differ by at most one insertion,
     *  deletion, or substitution. Tight, allocation-free check. */
    private static boolean editDistanceWithin1(String a, String b) {
        int la = a.length(), lb = b.length();
        if (Math.abs(la - lb) > 1) return false;
        if (la > lb) { String t = a; a = b; b = t; int ti = la; la = lb; lb = ti; }
        // Now la <= lb, diff <= 1.
        int i = 0, j = 0, edits = 0;
        while (i < la && j < lb) {
            if (a.charAt(i) == b.charAt(j)) {
                i++;
                j++;
            } else {
                if (++edits > 1) return false;
                if (la == lb) {
                    i++; j++; // substitution
                } else {
                    j++; // insertion in b
                }
            }
        }
        if (j < lb) edits += (lb - j);
        return edits <= 1 && !(la == lb && edits == 0);
    }

    /** Lazily-built set view of {@link CommonWords#all()} for O(1) lookups
     *  by the compound-split helper. */
    private static final java.util.HashSet<String> DICT_SET = new java.util.HashSet<>(CommonWords.all());
}
