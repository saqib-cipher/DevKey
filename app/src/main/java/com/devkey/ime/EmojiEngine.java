package com.codekeys.ime;

import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * EmojiEngine — owns the in-keyboard emoji panel state: the active category,
 * the live search query, and the persisted "recently used" list. The actual
 * emoji catalog lives in {@link EmojiData} which we leave untouched.
 */
final class EmojiEngine {

    private static final int MAX_RECENT = 24;
    private static final String PREF_RECENT = "emoji_recents";

    private final SharedPreferences prefs;
    private String currentCategory = "smileys";
    private final StringBuilder searchQuery = new StringBuilder();

    EmojiEngine(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    String getCategory() { return currentCategory; }
    void setCategory(String key) {
        currentCategory = key;
        searchQuery.setLength(0);
    }

    String getSearchQuery() { return searchQuery.toString(); }
    boolean isSearching() { return searchQuery.length() > 0; }
    void appendSearch(char c) { searchQuery.append(c); }
    boolean popSearchChar() {
        if (searchQuery.length() == 0) return false;
        searchQuery.deleteCharAt(searchQuery.length() - 1);
        return true;
    }
    void clearSearch() { searchQuery.setLength(0); }

    /** Resolves the visible emoji list for the current state. */
    List<String> visibleEmojis() {
        if (isSearching()) return EmojiData.search(searchQuery.toString());
        if ("recent".equals(currentCategory)) {
            List<String> r = loadRecent();
            return r.isEmpty() ? EmojiData.emojisFor("smileys") : r;
        }
        return EmojiData.emojisFor(currentCategory);
    }

    /** Records that {@code emoji} was just chosen. MRU semantics, capped. */
    void rememberRecent(String emoji) {
        if (TextUtils.isEmpty(emoji)) return;
        ArrayList<String> existing = new ArrayList<>(loadRecent());
        existing.remove(emoji);
        existing.add(0, emoji);
        while (existing.size() > MAX_RECENT) existing.remove(existing.size() - 1);
        prefs.edit().putString(PREF_RECENT, TextUtils.join("\u0001", existing)).apply();
    }

    List<String> loadRecent() {
        String raw = prefs.getString(PREF_RECENT, "");
        if (TextUtils.isEmpty(raw)) return Collections.emptyList();
        ArrayList<String> out = new ArrayList<>();
        for (String s : raw.split("\u0001")) if (!s.isEmpty()) out.add(s);
        return out;
    }
}
