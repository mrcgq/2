# ── gomobile core.aar 保护 ───────────────────────────────────
-keep class go.** { *; }
-keep interface go.** { *; }
-dontwarn go.**

-keep class core.** { *; }
-keep interface core.** { *; }
-dontwarn core.**

# ── 纯 C 核心 hev-socks5-tunnel JNI 保护 ─────────────────────
-keep class com.xlink.android.vpn.TProxyService { *; }
-keepclassmembers class com.xlink.android.vpn.TProxyService {
    native <methods>;
}

# ── VpnService 保护 ──────────────────────────────────────────
-keep class com.xlink.android.vpn.XlinkVpnService { *; }
-keep class com.xlink.android.vpn.XlinkVpnService$* { *; }

# ── 广播接收器保护 ───────────────────────────────────────────
-keep class com.xlink.android.receiver.BootReceiver { *; }
-keep class com.xlink.android.receiver.VpnControlReceiver { *; }

# ── Kotlin 序列化保护 ─────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses,EnclosingMethod

-keep class kotlinx.serialization.** { *; }
-keepclassmembers class ** {
    @kotlinx.serialization.Serializable *;
}

-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }

-keepclassmembers class * extends android.content.BroadcastReceiver {
    public <init>();
}
-keepclassmembers class * extends android.app.Service {
    public <init>();
}

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
