# Asphalt SDK consumer ProGuard rules.
# These rules are applied automatically by R8 in any app that depends on this SDK.

# Keep all public SDK API classes so integrating apps can reference them by name.
-keep public class io.asphalt.sdk.Asphalt { public *; }
-keep public class io.asphalt.sdk.AsphaltConfig { *; }
-keep public class io.asphalt.sdk.AsphaltCallback { *; }
-keep public interface io.asphalt.sdk.AsphaltCallback { *; }

# Keep all public model classes — they are serialised to JSON for upload.
-keep class io.asphalt.sdk.model.** { *; }

# Keep Room entity and DAO classes so the generated code survives shrinking.
-keep class io.asphalt.sdk.storage.** { *; }

# WorkManager worker must be kept so the system can re-instantiate it after
# process death. The class name is referenced by name in the WorkManager DB.
-keep class io.asphalt.sdk.upload.UploadWorker { *; }

# Suppress notes about missing classes that are referenced only from runtime
# annotations (e.g. coroutines internals, Room annotations).
-dontnote kotlin.**
-dontnote kotlinx.**
-dontnote androidx.**
