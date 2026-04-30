package com.codekeys.ime;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.List;

/**
 * UIRenderer — keeps the IME class free of view-construction noise.
 *
 * <p>Provides:
 * <ul>
 *   <li>{@code inflate*Panel(parent)} — lazily inflates each panel layout.</li>
 *   <li>{@code makeKey} / {@code makeChip} / {@code roundedFill} — small
 *       Material-3 styled view factories used across panels so every key and
 *       chip in the keyboard shares the same look.</li>
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
     * Builds a {@link MaterialButton}-based key with Material 3 Expressive
     * defaults (rounded corners, ripple, no inset). The runtime palette still
     * wins via {@link #roundedFill(int, int)} on top.
     */
    Button makeKey(String label, int bgColor, int textColor) {
        MaterialButton btn = new MaterialButton(ime, null,
                com.google.android.material.R.attr.materialButtonStyle);
        btn.setText(label);
        btn.setTextSize(14f);
        btn.setTextColor(textColor);
        btn.setAllCaps(false);
        btn.setInsetTop(0);
        btn.setInsetBottom(0);
        btn.setMinHeight(0);
        btn.setMinWidth(0);
        btn.setCornerRadius(ime.dp(12));
        btn.setPadding(ime.dp(2), ime.dp(2), ime.dp(2), ime.dp(2));
        // Runtime theme override (per-user accent) — wins over the M3 default.
        btn.setBackground(roundedFill(bgColor, ime.dp(12)));
        return btn;
    }

    /**
     * Builds a Material 3 Expressive chip for the suggestion strip. The "best"
     * suggestion gets a stronger fill + bold weight so the user can spot the
     * primary candidate at a glance.
     */
    Button makeChip(String label, int bgColor, int textColor, boolean primary) {
        MaterialButton btn = new MaterialButton(ime, null,
                com.google.android.material.R.attr.materialButtonStyle);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextSize(13f);
        btn.setTextColor(textColor);
        btn.setTypeface(primary ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        btn.setInsetTop(0);
        btn.setInsetBottom(0);
        btn.setPadding(ime.dp(12), 0, ime.dp(12), 0);
        btn.setCornerRadius(ime.dp(18));
        btn.setBackground(roundedFill(bgColor, ime.dp(18)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ime.dp(32));
        lp.setMargins(ime.dp(4), ime.dp(4), ime.dp(4), ime.dp(4));
        btn.setLayoutParams(lp);
        btn.setMinHeight(0);
        btn.setMinWidth(0);
        return btn;
    }

    /** Rounded solid fill used everywhere the keyboard wants an M3 corner. */
    GradientDrawable roundedFill(int color, int radiusPx) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(radiusPx);
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
                        CategoryTap onCategory, EmojiTap onEmoji, Runnable onClearSearch) {
        // ── Search bar ──
        TextView searchText = panelView.findViewById(R.id.emoji_search_text);
        Button clearBtn = panelView.findViewById(R.id.emoji_search_clear);
        if (engine.isSearching()) {
            searchText.setText(engine.getSearchQuery());
            searchText.setTextColor(accent);
            clearBtn.setVisibility(View.VISIBLE);
            clearBtn.setOnClickListener(v -> onClearSearch.run());
        } else {
            searchText.setText("Type to search emoji…");
            searchText.setTextColor(dim(textCol));
            clearBtn.setVisibility(View.GONE);
        }

        // ── Category tabs (Material 3 Expressive icon-style buttons) ──
        LinearLayout tabRow = panelView.findViewById(R.id.emoji_category_tabs);
        tabRow.removeAllViews();
        for (int i = 0; i < EmojiData.CATEGORY_KEYS.length; i++) {
            final String key = EmojiData.CATEGORY_KEYS[i];
            String label = EmojiData.CATEGORY_NAMES[i];
            boolean active = key.equals(engine.getCategory()) && !engine.isSearching();
            MaterialButton tab = new MaterialButton(ime, null,
                    com.google.android.material.R.attr.materialButtonStyle);
            tab.setText(label);
            tab.setAllCaps(false);
            tab.setTextSize(label.length() > 2 ? 11f : 18f);
            tab.setTextColor(active ? accent : textCol);
            tab.setInsetTop(0);
            tab.setInsetBottom(0);
            tab.setCornerRadius(ime.dp(18));
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
            MaterialButton btn = new MaterialButton(ime, null,
                    com.google.android.material.R.attr.materialButtonStyle);
            btn.setText(emoji);
            btn.setTextSize(22f);
            btn.setAllCaps(false);
            btn.setInsetTop(0);
            btn.setInsetBottom(0);
            btn.setCornerRadius(ime.dp(12));
            btn.setBackground(roundedFill(keyBg, ime.dp(12)));
            btn.setPadding(0, 0, 0, 0);
            btn.setMinHeight(0);
            btn.setMinWidth(0);
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
     * <p>Cards have rounded corners, a 2-line preview with ellipsis, a pin
     * toggle on the right, and tap → paste / long-press → delete bindings.
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
        // ── MaterialCardView wrapper ──
        MaterialCardView card = new MaterialCardView(ime);
        card.setRadius(ime.dp(16));
        card.setCardElevation(0f);
        card.setStrokeWidth(0);
        card.setUseCompatPadding(false);
        card.setCardBackgroundColor(
                CodeKeysIME.blend(keyBg, accent, entry.pinned ? 0.18f : 0.05f));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(ime.dp(4), ime.dp(4), ime.dp(4), ime.dp(4));
        card.setLayoutParams(cardLp);

        // ── Inner row ──
        LinearLayout row = new LinearLayout(ime);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(ime.dp(12), ime.dp(10), ime.dp(8), ime.dp(10));
        row.setLayoutParams(new MaterialCardView.LayoutParams(
                MaterialCardView.LayoutParams.MATCH_PARENT,
                MaterialCardView.LayoutParams.WRAP_CONTENT));

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
        row.addView(preview);

        // Pin toggle (MaterialButton, icon-style)
        MaterialButton pin = new MaterialButton(ime, null,
                com.google.android.material.R.attr.materialButtonStyle);
        pin.setText(entry.pinned ? "📌" : "📍");
        pin.setAllCaps(false);
        pin.setTextSize(14f);
        pin.setTextColor(textCol);
        pin.setInsetTop(0);
        pin.setInsetBottom(0);
        pin.setCornerRadius(ime.dp(10));
        pin.setBackground(roundedFill(CodeKeysIME.blend(keyBg, accent, 0.10f), ime.dp(10)));
        pin.setPadding(0, 0, 0, 0);
        pin.setMinHeight(0);
        pin.setMinWidth(0);
        LinearLayout.LayoutParams pinLp = new LinearLayout.LayoutParams(ime.dp(40), ime.dp(36));
        pinLp.setMargins(ime.dp(4), 0, ime.dp(2), 0);
        pin.setLayoutParams(pinLp);
        pin.setOnClickListener(v -> onPin.onEntry(entry));
        row.addView(pin);

        // Delete (MaterialButton, error-tinted)
        MaterialButton del = new MaterialButton(ime, null,
                com.google.android.material.R.attr.materialButtonStyle);
        del.setText("✕");
        del.setAllCaps(false);
        del.setTextSize(14f);
        del.setTextColor(0xFFFF6666);
        del.setInsetTop(0);
        del.setInsetBottom(0);
        del.setCornerRadius(ime.dp(10));
        del.setBackground(roundedFill(0x22FF0000, ime.dp(10)));
        del.setPadding(0, 0, 0, 0);
        del.setMinHeight(0);
        del.setMinWidth(0);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(ime.dp(40), ime.dp(36));
        dLp.setMargins(ime.dp(2), 0, 0, 0);
        del.setLayoutParams(dLp);
        del.setOnClickListener(v -> onDelete.onEntry(entry));
        row.addView(del);

        card.addView(row);
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
