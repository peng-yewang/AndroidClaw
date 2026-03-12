# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Keep accessibility service
-keep class com.androidclaw.app.service.** { *; }

# Keep task scripts
-keep class com.androidclaw.app.task.** { *; }
