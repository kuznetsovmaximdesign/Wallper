# kotlinx.serialization: keep generated serializers of our @Serializable models.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class com.wallper.live.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.wallper.live.**$$serializer { *; }
