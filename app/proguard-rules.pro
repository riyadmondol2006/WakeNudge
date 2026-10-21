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
