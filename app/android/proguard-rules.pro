# Fuse release optimisation (on by default; -Pfuse.r8=false builds without it).
# Names stay readable, so a crash report on the device still says where it happened.
-dontobfuscate

# Fuse's own code: serializers, enums read from stored settings, and Compose screens are all reached
# in ways R8 can't fully follow, so none of it is removed.
-keep class io.github.matiyaaa.fuse.** { *; }

# Ktor finds its client and server engines with ServiceLoader, and Phone Link's server (CIO) the same.
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { volatile <fields>; }

# kotlinx.serialization: generated serializers are found through companion objects.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, EnclosingMethod
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** Companion;
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Optional parts of libraries that aren't on Android.
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn java.lang.management.**
-dontwarn javax.naming.**
-dontwarn reactor.blockhound.**
-dontwarn io.netty.**

# Libraries that find their own parts by name (image fetchers and decoders, player renderers and
# extensions): kept whole, so nothing they look up at run time can be missing.
-keep class coil3.** { *; }
-keep class androidx.media3.** { *; }
-dontwarn coil3.**
-dontwarn androidx.media3.**

# Enums are read back from stored settings by name.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
