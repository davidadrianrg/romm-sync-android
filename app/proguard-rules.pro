# ── Retrofit ────────────────────────────────────────────────────────────
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**

# ── Moshi ───────────────────────────────────────────────────────────────
-keepclassmembers class es.davidrg.rommsync.data.remote.dto.** { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }
-keep class **JsonAdapter { *; }
-keepclassmembers class * {
    @com.squareup.moshi.JsonClass <methods>;
}

# ── Room ────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-dontwarn androidx.room.paging.**

# ── Coroutines ──────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ── Coil ────────────────────────────────────────────────────────────────
-keep class coil.** { *; }

# ── WorkManager ─────────────────────────────────────────────────────────
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }

# ── Kotlin Metadata ─────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }

# ── Compose ─────────────────────────────────────────────────────────────
# Compose ya incluye sus propias reglas en el AAR; no suele necesitar
# reglas adicionales, pero mantenemos esto como safety net.
-dontwarn androidx.compose.**
