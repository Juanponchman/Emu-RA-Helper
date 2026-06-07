-keepattributes *Annotation*
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class io.github.mayusi.emuhelper.data.model.** { *; }
-keepclassmembers class io.github.mayusi.emuhelper.data.source.** { *; }

# kotlinx.serialization — keep generated serializers (release/R8)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** serializer(...);
}
-keepclasseswithmembers class ** {
    @kotlinx.serialization.Serializable <methods>;
}
-keep,includedescriptorclasses class io.github.mayusi.emuhelper.**$$serializer { *; }
-keepclassmembers class io.github.mayusi.emuhelper.** {
    *** Companion;
}
