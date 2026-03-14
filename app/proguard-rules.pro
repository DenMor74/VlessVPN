# Сохраняем libv2ray классы
-keep class libv2ray.** { *; }
-dontwarn libv2ray.**

# Сохраняем Room entity классы
-keep class com.vlessvpn.app.model.** { *; }

# Сохраняем Gson модели
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
