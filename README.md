# CodeKeys — Coding IME for Sketchware Pro
## Complete Integration & Setup Guide

---

## 📁 Project Structure

```
CodeKeys/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/codekeys/ime/
│       │   ├── CodeKeysIME.java        ← Core IME Service (key preview, shift logic,
│       │   │                              suggestions, IME actions, language popup)
│       │   └── SettingsActivity.java   ← Settings UI logic + custom language presets
│       └── res/
│           ├── layout/
│           │   ├── keyboard_main.xml   ← Keyboard view
│           │   └── settings_activity.xml ← Settings screen layout (separate from Java)
│           ├── values/
│           │   └── styles.xml          ← AppTheme + SettingsHeader style
│           └── xml/
│               └── method.xml          ← IME Metadata
```

## ✨ What's new in this build

* **M3 key-preview popup** — every text/symbol key tap shows a Gboard-style
  popup above the key with the inserted character.
* **Smart Shift / Caps Lock** — single tap = uppercase next letter, then auto-revert;
  double tap = caps-lock until tapped again. ⇧ icon = single, ⇪ icon = locked.
* **Selection-safe** — Shift/Caps, suggestion taps, and snippet inserts no longer
  collapse the user's selection. Backspace deletes the selection (if any) instead
  of one character to its left.
* **Suggestion strip** — top row offers autocomplete (snippet triggers) and basic
  typo correction.
* **IME action support** — Enter button shows Go / Next / Done / 🔍 Search / Send
  based on the active EditText's `imeOptions`.
* **No more Tab key** — replaced by dedicated **?123** symbol panel and **☺** emoji
  panel toggles.
* **Settings + language popup** — the ⚙ button shows a bottom-left popup listing
  every available language plus a "Settings…" entry that launches the settings app.
* **Custom language presets** — Settings → "Custom Language Presets" lets the user
  add/remove preset names; they appear in the keyboard's language popup.

---

## 🔧 Sketchware Pro Setup (Step-by-Step)

### Step 1 — Create a New Project

1. Open Sketchware Pro → tap **+** to create new project
2. Package name: `com.codekeys.ime`
3. Min SDK: **21** (Android 5.0)
4. Activity name: `SettingsActivity`

---

### Step 2 — Add the IME Service via "Add Source Directly"

Sketchware Pro allows injecting raw Java/XML. Use these paths:

#### 2a. Add IME Service Class
- Go to **My Files** (or project source folder)
- Create: `CodeKeysIME.java` in the same package as `SettingsActivity`
- Paste the entire content of `CodeKeysIME.java`

#### 2b. Add Settings Activity
- Replace or extend the auto-generated `MainActivity.java` with `SettingsActivity.java`
- OR rename your main activity to `SettingsActivity`

---

### Step 3 — Create Layouts

#### keyboard_main.xml
In Sketchware Pro → **View** tab → create a new layout file called `keyboard_main`:

You can either:
- **Option A**: Use Sketchware's drag-and-drop to create LinearLayouts matching the XML structure
- **Option B**: In the file manager, navigate to `res/layout/` and paste `keyboard_main.xml` directly

**Key view IDs needed** (create these in Sketchware's view editor):
```
LinearLayout: keyboard_root       (orientation=vertical, bg=#1A1A2E)
├── LinearLayout: row_numbers
├── LinearLayout: row_letters1
├── LinearLayout: row_letters2
├── LinearLayout: row_letters3    (nested inside a row with caps/backspace)
├── Button: btn_caps
├── Button: btn_backspace
├── HorizontalScrollView → LinearLayout: row_symbols
├── HorizontalScrollView → LinearLayout: row_snippets
└── LinearLayout: row_nav
    ├── TextView: lang_label
    ├── Button: btn_tab
    ├── Button: btn_undo
    ├── Button: btn_redo
    ├── Button: btn_space
    ├── ImageButton: btn_arrow_up
    ├── ImageButton: btn_arrow_down
    ├── ImageButton: btn_arrow_left
    ├── ImageButton: btn_arrow_right
    └── Button: btn_enter
```

---

### Step 4 — Configure the Manifest

In Sketchware Pro → **Library / Config** → **AndroidManifest**:

Add inside `<application>` tag:

```xml
<service
    android:name=".CodeKeysIME"
    android:label="CodeKeys"
    android:permission="android.permission.BIND_INPUT_METHOD"
    android:exported="true">
    <intent-filter>
        <action android:name="android.view.InputMethod" />
    </intent-filter>
    <meta-data
        android:name="android.view.im"
        android:resource="@xml/method" />
</service>
```

Add permission before `<application>`:
```xml
<uses-permission android:name="android.permission.VIBRATE" />
```

---

### Step 5 — Create res/xml/method.xml

1. In Sketchware's file manager, create folder `res/xml/`
2. Create file `method.xml` and paste the content from `method.xml`

This file **must exist** or the IME service will crash on binding.

---

### Step 6 — Dependencies

In Sketchware Pro → **Library** tab, enable:
- `AppCompat` library

No other dependencies are needed.

---

## 🚀 How to Test

1. **Build & Install** the APK on your Android device
2. Go to **Settings → General Management → Keyboard → On-screen keyboard**
3. Enable **CodeKeys**
4. Go back to any text field
5. Long-press the keyboard icon in the navigation bar → select **CodeKeys**

---

## 🎨 Feature Reference

### Language Modes
Tap the **GEN/C/JAVA/PYTHON/JS** label (bottom-left) to cycle through modes.
Each mode changes:
- The **symbol row** (top priority symbols for that language)
- The **snippet row** (quick-insert code templates)

### Symbol Row
- **Tap**: Insert symbol (auto-closes `( { [ " ' \``)
- **Long-press**: Copies symbol to internal clipboard

### Snippet Row
- **Tap**: Inserts full code template at cursor
- Templates use real indentation (tab characters)

### Arrow Keys
- The 4-directional arrow cluster moves the cursor precisely
- Works in all apps including code editors

### Undo / Redo
- ↩ = Ctrl+Z (undo)
- ↪ = Ctrl+Y (redo)
- Works in apps that support standard undo (most code editors)

### Auto-close Brackets
```
Type (  →  ()  with cursor inside
Type {  →  {}  with cursor inside
Type [  →  []  with cursor inside
Type "  →  ""  with cursor inside
Type '  →  ''  with cursor inside
Type `  →  ``  with cursor inside
```

### Theme Settings
Open the **CodeKeys** app icon from your launcher to access settings:
- **7 built-in themes**: Dark Blue, AMOLED, Deep Green, Monokai, Dracula, Solarized Dark, Light
- **Haptic feedback toggle**
- **AMOLED pure-black mode**
- **Reset to defaults**

---

## 📝 Snippet Templates Reference

### C
| Trigger | Expands To |
|---------|-----------|
| `if`   | `if () { }` |
| `for`  | `for (int i = 0; i < n; i++) { }` |
| `fn`   | `void functionName() { }` |
| `main` | `int main() { return 0; }` |
| `inc`  | `#include <stdio.h>` |
| `pf`   | `printf("");` |

### Java
| Trigger | Expands To |
|---------|-----------|
| `class` | `public class ClassName { }` |
| `fn`    | `public void methodName() { }` |
| `sys`   | `System.out.println("");` |
| `try`   | `try { } catch (Exception e) { }` |
| `forea` | `for (Object item : collection) { }` |

### Python
| Trigger | Expands To |
|---------|-----------|
| `def`   | `def function_name():` |
| `class` | `class ClassName:` |
| `print` | `print("")` |
| `for`   | `for i in range():` |
| `imp`   | `import ` |

### JavaScript
| Trigger | Expands To |
|---------|-----------|
| `fn`    | `function name() { }` |
| `arrow` | `const fn = () => { }` |
| `const` | `const  = ` |
| `log`   | `console.log("");` |
| `prom`  | `new Promise((resolve, reject) => { })` |

---

## 🛠 Troubleshooting

| Problem | Solution |
|---------|----------|
| Keyboard doesn't appear in IME list | Check `method.xml` exists in `res/xml/` and manifest has `<meta-data>` |
| App crashes on open | Ensure `keyboard_main.xml` has all required view IDs |
| Symbols row missing | Check `row_symbols` LinearLayout ID is correct |
| No haptic feedback | Enable VIBRATE permission in manifest, toggle haptic in settings |
| Arrows don't work | Some apps block `sendKeyEvent` — normal behavior |

---

## 🔮 Future Enhancements

- **Gesture swipe typing** — requires custom `View` with `onTouchEvent`
- **Floating keyboard** — use `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`
- **Code completion** — maintain a word-frequency trie, update on `onUpdateSelection`
- **Macro recording** — record key sequences, replay with one tap
- **Theme export/import** — serialize SharedPreferences to JSON, share via Intent

---

*CodeKeys v1.0 — Built for developers, by developers.*
