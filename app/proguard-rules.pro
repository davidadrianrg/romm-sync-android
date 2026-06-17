# Moshi
-keep class es.davidrg.rommsync.data.remote.dto.** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.JsonClass <methods>;
}
-keep @com.squareup.moshi.JsonClass class * { *; }

# Retrofit
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
