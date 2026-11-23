# libadb / BouncyCastle 使用反射的类保留
-keep class io.github.muntashirakon.adb.** { *; }
-keep class org.conscrypt.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn io.github.muntashirakon.adb.**
-dontwarn com.google.zxing.**
