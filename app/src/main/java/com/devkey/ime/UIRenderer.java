package com.codekeys.ime;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * UIRenderer — keeps the IME class free of view-construction noise.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@code inflate*Panel(parent)} — lazily inflates each panel layout.</li>
 *   <li>{@code makeKey} / {@code makeChip} / {@code roundedFill} — small
 *       view factories used across panels so every key and chip in the
 *       keyboard shares the same look. All visuals are produced
 *       programmatically (no Material theme attrs) so the IME inflates
 *       safely under {@code Theme.DeviceDefault.InputMethod}.</li>
 *   <li>{@code fillEmojiPanel} / {@code fillClipboardPanel} — fills the
 *       inflated panel views with their actual content based on the data
 *       sources passed in. The callbacks let the IME stay in charge of the
 *       actual insert / pin / delete behaviour.</li>
 * </ul>
 */
final class UIRenderer {

    private final CodeKeysIME ime;
    private final LayoutInflater inflater;

    /** ── Callback interfaces. ── */
    interface CategoryTap { void onCategory(String key); }
    interface EmojiTap { void onEmoji(String emoji); }
    interface ClipAction { void onEntry(ClipboardStore.Entry entry); }

    UIRenderer(CodeKeysIME ime) {
        this.ime = ime;
        this.inflater = LayoutInflater.from(ime);
    }

    // ─── Inflate ──────────────────────────────────────────────────────────────
    View inflateKeyboardPanel(ViewGroup parent) {
        return inflater.inflate(R.layout.panel_keyboard, parent, false);
    }
    View inflateEmojiPanel(ViewGroup parent) {
        return inflater.inflate(R.layout.panel_emoji, parent, false);
    }
    View inflateClipboardPanel(ViewGroup parent) {
        return inflater.inflate(R.layout.panel_clipboard, parent, false);
    }

    // ─── Key / chip factory ───────────────────────────────────────────────────
    /**
     * Builds a plain {@link Button} key with a programmatic rounded background
     * so it stays independent of the platform / Material theme. Radius, text
     * size and stroke are pulled from the IME's user-configurable prefs so
     * every key in the layout shares one visual language.
     */
    Button makeKey(String label, int bgColor, int textColor) {
        Button btn = new Button(ime);
        btn.setText(label);
        btn.setTextSize(ime.getKeyTextSizeSp());
        btn.setTextColor(textColor);
        btn.setAllCaps(false);
        btn.setMinHeight(0);
        btn.setMinWidth(0);
        btn.setBackground(roundedFill(bgColor,
                ime.dp(ime.getKeyRadiusDp()),
                ime.dp(ime.getKeyStrokeWidthDp()),
                ime.getKeyStrokeColor()));
        btn.setPadding(ime.dp(2), ime.dp(2), ime.dp(2), ime.dp(2));
        return btn;
    }

    /**
     * Builds a chip-style suggestion button. The "best" candidate gets a
     * stronger fill + bold weight so the user can spot the primary
     * suggestion at a glance. Chips share the user's key radius (capped a
     * little lower so they remain visually pill-y).
     */
    Button makeChip(String label, int bgColor, int textColor, boolean primary) {
        Button btn = new Button(ime);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextSize(13f);
        btn.setTextColor(textColor);
        btn.setTypeface(primary ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        btn.setPadding(ime.dp(12), 0, ime.dp(12), 0);
        // Chips use a slightly larger radius than keys (or fall back to the
        // user setting if it's already above the chip baseline).
        int chipRadius = Math.max(ime.dp(ime.getKeyRadiusDp()), ime.dp(14));
        btn.setBackground(roundedFill(bgColor, chipRadius,
                ime.dp(ime.getKeyStrokeWidthDp()),
                ime.getKeyStrokeColor()));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ime.dp(32));
        lp.setMargins(ime.dp(4), ime.dp(4), ime.dp(4), ime.dp(4));
        btn.setLayoutParams(lp);
        btn.setMinHeight(0);
        btn.setMinWidth(0);
        return btn;
    }

    /** Rounded solid fill used everywhere the keyboard wants a soft corner. */
    GradientDrawable roundedFill(int color, int radiusPx) {
        return roundedFill(color, radiusPx, 0, 0);
    }

    /**
     * Rounded fill with an optional stroke. Stroke is fully transparent by
     * default so existing call sites keep their look; the IME passes user
     * settings through this overload to draw a customizable border.
     */
    GradientDrawable roundedFill(int color, int radiusPx, int strokePx, int strokeColor) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(radiusPx);
        if (strokePx > 0) bg.setStroke(strokePx, strokeColor);
        return bg;
    }

    // ─── Emoji panel ─────────────────────────────────────────────────────────
    /**
     * Fills the inflated emoji panel view with category tabs and the 8-column
     * grid for the active category / search query. The callbacks tell the IME
     * what to do when the user taps a category, an emoji, or the clear button.
     */
    void fillEmojiPanel(View panelView, EmojiEngine engine,
                        int keyBg, int textCol, int accent,
                        CategoryTap onCategory, EmojiTap onEmoji,
                        Runnable onBackspaceSearch, Runnable onClearSearch) {
        // ── Bottom search bar (pill + key-styled clear) ──
        TextView searchText = panelView.findViewById(R.id.emoji_search_text);
        ImageButton clearBtn = panelView.findViewById(R.id.emoji_search_clear);
        LinearLayout pill = panelView.findViewById(R.id.emoji_search_pill);

        // Pill: rounded surface a touch lighter than the panel background so
        // it reads as elevated. Becomes accent-tinted while a query is active.
        if (pill != null) {
            int pillFill = engine.isSearching()
                    ? CodeKeysIME.blend(keyBg, accent, 0.18f)
                    : CodeKeysIME.blend(keyBg, 0xFFFFFFFF, 0.06f);
            pill.setBackground(roundedFill(pillFill, ime.dp(22)));
        }

        if (searchText != null) {
            if (engine.isSearching()) {
                searchText.setText(engine.getSearchQuery());
                searchText.setTextColor(accent);
            } else {
                searchText.setText("Search");
                searchText.setTextColor(dim(textCol));
            }
        }

        if (clearBtn != null) {
            // Always-visible backspace-style key. Tap deletes the last search
            // character (so it actually behaves like a backspace); long-press
            // wipes the whole query. Icon is tinted to match the active theme.
            clearBtn.setImageResource(R.drawable.backspace);
            clearBtn.setColorFilter(engine.isSearching() ? accent : textCol);
            clearBtn.setBackground(roundedFill(keyBg, ime.dp(12)));
            clearBtn.setOnClickListener(v -> onBackspaceSearch.run());
            clearBtn.setOnLongClickListener(v -> { onClearSearch.run(); return true; });
        }

        // ── Category tabs (plain Button + rounded background) ──
        LinearLayout tabRow = panelView.findViewById(R.id.emoji_category_tabs);
        tabRow.removeAllViews();
        for (int i = 0; i < EmojiData.CATEGORY_KEYS.length; i++) {
            final String key = EmojiData.CATEGORY_KEYS[i];
            String label = EmojiData.CATEGORY_NAMES[i];
            boolean active = key.equals(engine.getCategory()) && !engine.isSearching();
            Button tab = new Button(ime);
            tab.setText(label);
            tab.setAllCaps(false);
            tab.setTextSize(label.length() > 2 ? 11f : 18f);
            tab.setTextColor(active ? accent : textCol);
            tab.setBackground(roundedFill(active ? CodeKeysIME.blend(keyBg, accent, 0.30f) : keyBg, ime.dp(18)));
            tab.setOnClickListener(v -> onCategory.onCategory(key));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ime.dp(54), ime.dp(34));
            lp.setMargins(ime.dp(3), ime.dp(4), ime.dp(3), ime.dp(4));
            tab.setLayoutParams(lp);
            tab.setMinHeight(0);
            tab.setMinWidth(0);
            tab.setPadding(0, 0, 0, 0);
            tabRow.addView(tab);
        }

        // ── 8-column grid ──
        LinearLayout grid = panelView.findViewById(R.id.emoji_grid);
        grid.removeAllViews();

        // Swipe horizontally on the grid to jump to the previous / next
        // category (Gboard parity). Vertical scrolling stays delegated to
        // the host ScrollView since we only consume horizontal flings.
        ScrollView gridScroll = panelView.findViewById(R.id.emoji_grid_scroll);
        if (gridScroll != null && !engine.isSearching()) {
            final GestureDetector swipe = new GestureDetector(ime,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onFling(MotionEvent e1, MotionEvent e2,
                                               float vx, float vy) {
                            if (e1 == null || e2 == null) return false;
                            float dx = e2.getX() - e1.getX();
                            float dy = e2.getY() - e1.getY();
                            if (Math.abs(dx) < ime.dp(48)) return false;
                            if (Math.abs(dx) < Math.abs(dy) * 1.5f) return false;
                            int idx = -1;
                            for (int i = 0; i < EmojiData.CATEGORY_KEYS.length; i++) {
                                if (EmojiData.CATEGORY_KEYS[i].equals(engine.getCategory())) {
                                    idx = i; break;
                                }
                            }
                            if (idx < 0) return false;
                            int next = dx < 0 ? idx + 1 : idx - 1;
                            if (next < 0 || next >= EmojiData.CATEGORY_KEYS.length) return false;
                            onCategory.onCategory(EmojiData.CATEGORY_KEYS[next]);
                            return true;
                        }
                    });
            gridScroll.setOnTouchListener((v, ev) -> swipe.onTouchEvent(ev));
        } else if (gridScroll != null) {
            // Searching: clear any prior listener so vertical scroll still works.
            gridScroll.setOnTouchListener(null);
        }

        List<String> emojis = engine.visibleEmojis();
        if (emojis.isEmpty()) {
            TextView hint = new TextView(ime);
            hint.setText(engine.isSearching()
                    ? ("No emoji match \"" + engine.getSearchQuery() + "\"")
                    : "No emojis in this category yet.");
            hint.setTextSize(12f);
            hint.setTextColor(dim(textCol));
            hint.setPadding(ime.dp(12), ime.dp(20), ime.dp(12), ime.dp(20));
            grid.addView(hint);
            return;
        }
        final int columns = 8;
        LinearLayout currentRow = null;
        for (int i = 0; i < emojis.size(); i++) {
            if (i % columns == 0) {
                currentRow = new LinearLayout(ime);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, ime.dp(44));
                currentRow.setLayoutParams(rowLp);
                grid.addView(currentRow);
            }
            final String emoji = emojis.get(i);
            Button btn = new Button(ime);
            btn.setText(emoji);
            btn.setTextSize(22f);
            btn.setAllCaps(false);
            btn.setBackground(roundedFill(keyBg, ime.dp(12)));
            btn.setPadding(0, 0, 0, 0);
            btn.setMinHeight(0);
            btn.setMinWidth(0);
            // Same hover/preview popup the letter keys use, so the user can
            // see which emoji they're about to commit.
            btn.setOnTouchListener(ime.keyPreviewToucher(emoji));
            btn.setOnClickListener(v -> onEmoji.onEmoji(emoji));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(ime.dp(2), ime.dp(2), ime.dp(2), ime.dp(2));
            btn.setLayoutParams(lp);
            currentRow.addView(btn);
        }
        // Pad the last row with empty spacers so the grid stays aligned.
        if (currentRow != null) {
            int filled = emojis.size() % columns;
            if (filled != 0) {
                for (int i = filled; i < columns; i++) {
                    View spacer = new View(ime);
                    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                    sp.setMargins(ime.dp(2), ime.dp(2), ime.dp(2), ime.dp(2));
                    spacer.setLayoutParams(sp);
                    currentRow.addView(spacer);
                }
            }
        }
    }

    // ─── Clipboard panel ─────────────────────────────────────────────────────
    /**
     * Fills the inflated clipboard panel view with cards for each entry.
     *
     * <p>Cards have rounded corners (drawn via {@link GradientDrawable}, no
     * Material widgets), a 2-line preview with ellipsis, a pin toggle on the
     * right, and tap → paste / long-press → delete bindings.
     */
    void fillClipboardPanel(View panelView, List<ClipboardStore.Entry> entries,
                            int keyBg, int textCol, int accent,
                            ClipAction onPaste, ClipAction onPin, ClipAction onDelete,
                            Runnable onCopySelection, Runnable onClearAll) {
        Button copyBtn  = panelView.findViewById(R.id.clip_copy_selection);
        Button clearBtn = panelView.findViewById(R.id.clip_clear_all);
        if (copyBtn != null) copyBtn.setOnClickListener(v -> onCopySelection.run());
        if (clearBtn != null) clearBtn.setOnClickListener(v -> onClearAll.run());

        LinearLayout list = panelView.findViewById(R.id.clip_list);
        list.removeAllViews();

        if (entries.isEmpty()) {
            TextView empty = new TextView(ime);
            empty.setText("No copied items yet.\nLong-press a symbol or use ⧉ Copy on a selection.");
            empty.setTextSize(12f);
            empty.setTextColor(dim(textCol));
            empty.setPadding(ime.dp(14), ime.dp(20), ime.dp(14), ime.dp(20));
            list.addView(empty);
            return;
        }

        for (final ClipboardStore.Entry entry : entries) {
            list.addView(buildClipCard(entry, keyBg, textCol, accent, onPaste, onPin, onDelete));
        }
    }

    private View buildClipCard(final ClipboardStore.Entry entry,
                               int keyBg, int textCol, int accent,
                               ClipAction onPaste, ClipAction onPin, ClipAction onDelete) {
        // ── Card wrapper (plain LinearLayout with rounded background) ──
        LinearLayout card = new LinearLayout(ime);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(ime.dp(12), ime.dp(10), ime.dp(8), ime.dp(10));
        card.setBackground(roundedFill(
                CodeKeysIME.blend(keyBg, accent, entry.pinned ? 0.18f : 0.05f),
                ime.dp(16)));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(ime.dp(4), ime.dp(4), ime.dp(4), ime.dp(4));
        card.setLayoutParams(cardLp);

        // Text preview (max 2 lines, ellipsis)
        TextView preview = new TextView(ime);
        preview.setText((entry.pinned ? "📌  " : "") + entry.text);
        preview.setTextSize(13f);
        preview.setTextColor(textCol);
        preview.setMaxLines(2);
        preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        preview.setLayoutParams(pLp);
        preview.setOnClickListener(v -> onPaste.onEntry(entry));
        preview.setOnLongClickListener(v -> { onDelete.onEntry(entry); return true; });
        card.addView(preview);

        // Pin toggle (plain Button, rounded background)
        Button pin = new Button(ime);
        pin.setText(entry.pinned ? "📌" : "📍");
        pin.setAllCaps(false);
        pin.setTextSize(14f);
        pin.setTextColor(textCol);
        pin.setBackground(roundedFill(CodeKeysIME.blend(keyBg, accent, 0.10f), ime.dp(10)));
        pin.setPadding(0, 0, 0, 0);
        pin.setMinHeight(0);
        pin.setMinWidth(0);
        LinearLayout.LayoutParams pinLp = new LinearLayout.LayoutParams(ime.dp(40), ime.dp(36));
        pinLp.setMargins(ime.dp(4), 0, ime.dp(2), 0);
        pin.setLayoutParams(pinLp);
        pin.setOnClickListener(v -> onPin.onEntry(entry));
        card.addView(pin);

        // Delete (plain Button, error-tinted)
        Button del = new Button(ime);
        del.setText("✕");
        del.setAllCaps(false);
        del.setTextSize(14f);
        del.setTextColor(0xFFFF6666);
        del.setBackground(roundedFill(0x22FF0000, ime.dp(10)));
        del.setPadding(0, 0, 0, 0);
        del.setMinHeight(0);
        del.setMinWidth(0);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(ime.dp(40), ime.dp(36));
        dLp.setMargins(ime.dp(2), 0, 0, 0);
        del.setLayoutParams(dLp);
        del.setOnClickListener(v -> onDelete.onEntry(entry));
        card.addView(del);

        // Make the whole card tappable too — easier touch target.
        card.setOnClickListener(v -> onPaste.onEntry(entry));
        return card;
    }

    private static int dim(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >>  8) & 0xFF;
        int b =  color        & 0xFF;
        return 0xFF000000 | (((r + 100) / 2) << 16) | (((g + 100) / 2) << 8) | ((b + 100) / 2);
    }
}
