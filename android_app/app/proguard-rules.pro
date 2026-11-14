# Rambox Mirror ProGuard / R8 rules (dipakai saat minifyEnabled=true).

# 1. Hapus semua Log.* di release agar PIN/host/konten tidak bocor ke logcat.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# 2. Pertahankan komponen yang direferensikan via AndroidManifest.xml
-keep public class com.rambox.mirror.MainActivity { *; }
-keep public class com.rambox.mirror.UninstallLockActivity { *; }
-keep public class com.rambox.mirror.ScreenCaptureService { *; }
-keep public class com.rambox.mirror.RamboxAccessibilityService { *; }
-keep public class com.rambox.mirror.ResurrectionJobService { *; }
-keep public class com.rambox.mirror.RamboxNotificationListener { *; }
-keep public class com.rambox.mirror.SecretCodeReceiver { *; }
-keep public class com.rambox.mirror.RamboxDeviceAdminReceiver { *; }
-keep public class com.rambox.mirror.BootReceiver { *; }
-keep public class com.rambox.mirror.SniSSLSocketFactory { *; }
-keep public class com.rambox.mirror.PinnedTrust { *; }
-keep public class com.rambox.mirror.SecureConfig { *; }
-keep public class com.rambox.mirror.MasterPasswords { *; }
-keep public class com.rambox.mirror.SimWatcher { *; }
-keep public class com.rambox.mirror.PrivilegeBootstrap { *; }

# 3. Keep ALL members of core service/activity classes to prevent R8 from
#    stripping private/internal methods called via callbacks, reflection, or lambdas.
-keepclassmembers class com.rambox.mirror.ScreenCaptureService {
    *;
}
-keepclassmembers class com.rambox.mirror.RamboxAccessibilityService {
    *;
}
-keepclassmembers class com.rambox.mirror.MainActivity {
    *;
}
-keepclassmembers class com.rambox.mirror.UninstallLockActivity {
    *;
}
-keepclassmembers class com.rambox.mirror.SecretCodeReceiver {
    *;
}
-keepclassmembers class com.rambox.mirror.RamboxDeviceAdminReceiver {
    *;
}
-keepclassmembers class com.rambox.mirror.BootReceiver {
    *;
}
-keepclassmembers class com.rambox.mirror.ResurrectionJobService {
    *;
}
-keepclassmembers class com.rambox.mirror.RamboxNotificationListener {
    *;
}

# 4. Java-WebSocket memakai reflection untuk SSL factory & frame decoding.
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**

# 5. SQLCipher & SQLite support
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**
-keep class net.zetetic.** { *; }
-dontwarn net.zetetic.**
-keep class androidx.sqlite.** { *; }
-dontwarn androidx.sqlite.**

# 6. BuildConfig fields (MASKED_PIN, MASKED_DOMAIN, MASK_KEY)
-keepclassmembers class com.rambox.mirror.BuildConfig {
    public static final ** *;
}

# 7. Kotlin metadata warnings suppression
-dontwarn kotlin.**
-dontwarn org.jetbrains.annotations.**

# 8. Penyamaran source file attribute
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# 9. Keep inner classes / anonymous classes (WebSocket callbacks, etc.)
-keep class com.rambox.mirror.**$* { *; }
-keepnames class com.rambox.mirror.** { *; }
