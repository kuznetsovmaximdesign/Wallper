# kotlinx.serialization: keep generated serializers of our @Serializable models.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class com.wallper.live.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.wallper.live.**$$serializer { *; }

# Room databases (WorkManager's own included) are created by reflection through their
# generated *_Impl no-argument constructor; R8 must keep it or the app dies at launch.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# WorkManager instantiates workers and input mergers by class name.
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.InputMerger { <init>(); }
