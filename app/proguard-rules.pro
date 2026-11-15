# WakeNudge R8 rules. Most libraries ship consumer rules; keep this file minimal.

# kotlinx.serialization: keep generated serializers for backup/restore + mission params.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class com.riyadm.wakenudge.** {
    *** Companion;
}
-keepclasseswithmembers class com.riyadm.wakenudge.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.riyadm.wakenudge.**$$serializer { *; }

# Stack traces in diagnostics exports stay readable.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# WorkManager 2.7.x (pulled in by Glance, which runs every widget session as a worker) creates its InputMerger by
# reflection, and its own rule (-keep class * extends androidx.work.InputMerger) names no members. R8 no longer keeps a
# no-argument constructor for such a rule, so without this every widget stayed on its loading spinner in release builds.
-keep class * extends androidx.work.InputMerger { void <init>(); }
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
