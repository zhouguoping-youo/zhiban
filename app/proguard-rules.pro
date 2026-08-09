# Tink references Error Prone annotations that are compile-time metadata only.
# They are not required by the application at runtime.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
-keep,includedescriptorclasses class net.zetetic.database.sqlcipher.** { *; }
-keep,includedescriptorclasses interface net.zetetic.database.sqlcipher.** { *; }
