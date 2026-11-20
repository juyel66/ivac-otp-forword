# Proguard rules for IVAC Assistant Mobile
-keepattributes Signature
-keepattributes *Annotation*

# Retrofit & OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }

# Gson data models
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.ivac.assistant.core.data.model.** { *; }

# WorkManager
-keep class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Android Components & Receivers
-keep class com.ivac.assistant.core.receiver.** { *; }
-keep class com.ivac.assistant.core.service.** { *; }
-keep class com.ivac.assistant.core.data.security.** { *; }
-keep class com.ivac.assistant.core.data.repository.** { *; }
-keep class com.ivac.assistant.core.util.** { *; }
-keep class com.ivac.assistant.IvacApplication { *; }
-keep class com.ivac.assistant.MainActivity { *; }


