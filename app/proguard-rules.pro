# 12 §5 — Room, Hilt, WorkManager and kotlinx-serialization ship consumer rules.
# Keep @Serializable navigation routes and backup DTOs (kotlinx-serialization).
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.nudge.**$$serializer { *; }
-keepclassmembers class app.nudge.** {
    *** Companion;
}
-keepclasseswithmembers class app.nudge.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Hilt workers are instantiated reflectively by WorkManager's factory lookup.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
# Timber is stripped of debug logging in release by not planting a tree.
