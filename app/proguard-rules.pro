# Project-specific ProGuard rules.
# IME entry points are referenced by the system via reflection, so keep them.
-keep class com.codekeys.ime.CodeKeysIME { *; }
-keep class com.codekeys.ime.SettingsActivity { *; }
