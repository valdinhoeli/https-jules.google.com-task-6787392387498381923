# Media3, OkHttp, Coil e Gson já trazem suas próprias regras de consumidor.

# Ofuscação mais forte: nomes de classes/arquivos trocados e tudo num pacote só,
# dificultando engenharia reversa do APK.
-repackageclasses ''
-allowaccessmodification
-renamesourcefileattribute x
-keepattributes LineNumberTable

# remove logs de depuração do código do app
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ponte JavaScript do player de trailer (página do YouTube → app)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
