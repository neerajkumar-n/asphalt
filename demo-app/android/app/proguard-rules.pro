# Asphalt demo app ProGuard rules.
# The SDK has its own consumer-rules.pro that R8 applies automatically;
# the rules below cover only demo-app-specific classes.

# Keep the Application subclass so the manifest reference is never renamed.
-keep class io.asphalt.demo.AsphaltDemoApplication { *; }

# Keep activity names referenced in AndroidManifest.xml.
-keep class io.asphalt.demo.MainActivity { *; }

# Keep ViewModel classes so the ViewModelProvider reflection works correctly.
-keep class io.asphalt.demo.DemoViewModel { *; }

# Silence notes about reflection used by AndroidX internals.
-dontnote androidx.**
-dontnote android.**
