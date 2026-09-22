# Keep Room entities & kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-keep,includedescriptorclasses class com.tbmedtrack.app.**$$serializer { *; }
-keepclassmembers class com.tbmedtrack.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.tbmedtrack.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
