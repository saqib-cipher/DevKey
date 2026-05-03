package com.codekeys.ime;

import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ClipboardStore — persists the in-keyboard clipboard history (pinned + recent
 * entries) to SharedPreferences. The IME no longer touches the storage format
 * directly; everything goes through this class.
 */
final class ClipboardStore {

    /** Maximum number of unpinned (recent) entries kept in history. */
    private static final int MAX_UNPINNED = 20;
    private static final String PREF_KEY = "clipboard_history";

    /** Single in-keyboard clipboard entry. */
    static final class Entry {
        final String text;
        final boolean pinned;
        Entry(String text, boolean pinned) { this.text = text; this.pinned = pinned; }
    }

    private final SharedPreferences prefs;

    ClipboardStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /** Reads the current list (pinned first, then recents in MRU order). */
    List<Entry> all() {
        String raw = prefs.getString(PREF_KEY, "");
        if (TextUtils.isEmpty(raw)) return Collections.emptyList();
        ArrayList<Entry> out = new ArrayList<>();
        for (String rec : raw.split("\u0002")) {
            if (rec.isEmpty()) continue;
            int sep = rec.indexOf('\u0001');
            if (sep <= 0) continue;
            String tag = rec.substring(0, sep);
            String text = rec.substring(sep + 1);
            out.add(new Entry(text, "P".equals(tag)));
        }
        return out;
    }

    /** Adds/promotes {@code text}. Existing pinned status is preserved. */
    void add(String text) {
        if (TextUtils.isEmpty(text)) return;
        ArrayList<Entry> entries = new ArrayList<>(all());
        boolean wasPinned = false;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).text.equals(text)) {
                wasPinned = entries.get(i).pinned;
                entries.remove(i);
                break;
            }
        }
        // Insert at the top of the unpinned region (after the last pinned entry).
        int insertAt = 0;
        for (Entry e : entries) { if (e.pinned) insertAt++; else break; }
        entries.add(insertAt, new Entry(text, wasPinned));
        // Trim unpinned tail.
        int unpinned = 0;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (!entries.get(i).pinned) {
                unpinned++;
                if (unpinned > MAX_UNPINNED) entries.remove(i);
            }
        }
        save(entries);
    }

    /** Toggles the pinned flag for the entry whose text equals {@code text}. */
    void togglePin(String text) {
        ArrayList<Entry> entries = new ArrayList<>(all());
        Entry target = null;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).text.equals(text)) { target = entries.remove(i); break; }
        }
        if (target == null) return;
        Entry updated = new Entry(target.text, !target.pinned);
        // Reinsert: pinned items stay first, recents after.
        int insertAt = updated.pinned ? 0 : countPinned(entries);
        entries.add(insertAt, updated);
        save(entries);
    }

    void remove(String text) {
        ArrayList<Entry> entries = new ArrayList<>(all());
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).text.equals(text)) { entries.remove(i); break; }
        }
        save(entries);
    }

    /** Removes every unpinned entry; pinned entries survive. */
    void clearUnpinned() {
        ArrayList<Entry> entries = new ArrayList<>(all());
        ArrayList<Entry> kept = new ArrayList<>();
        for (Entry e : entries) if (e.pinned) kept.add(e);
        save(kept);
    }

    private static int countPinned(List<Entry> entries) {
        int n = 0;
        for (Entry e : entries) if (e.pinned) n++;
        return n;
    }

    private void save(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append('\u0002');
            Entry e = entries.get(i);
            sb.append(e.pinned ? 'P' : 'U').append('\u0001').append(e.text);
        }
        prefs.edit().putString(PREF_KEY, sb.toString()).apply();
    }
}
