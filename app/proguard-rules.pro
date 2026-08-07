# Add project specific ProGuard rules here.
# For NetShield DNS AdBlocker

# Preserve line numbers and source file attributes for crash reporting
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Room Database rules
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Moshi rules
-keep class com.squareup.moshi.** { *; }
-keepclasseswithmembers class * {
    @com.squareup.moshi.Json *;
}
-keepclasseswithmembers class * {
    @com.squareup.moshi.JsonClass *;
}

# OkHttp & Retrofit rules
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepclassmembers class * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# Kotlin Coroutines
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# NetShield Entities & Data Models
-keep class com.example.data.local.** { *; }
-keep class com.example.model.** { *; }

