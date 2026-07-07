# keep core protocol types
-keep class io.sleepwalker.core.protocol.** { *; }

# Keep keymap raw resource IDs for reflection
-keepclassmembers class io.sleepwalker.core.R$raw {
    public static int keymap_*;
}