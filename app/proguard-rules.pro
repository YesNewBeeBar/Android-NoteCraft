# 自用项目暂不开启混淆；若日后开启，下面几条是 Room 与 kotlinx.serialization 必需的
-keep class androidx.room.** { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
