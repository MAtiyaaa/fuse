# Fuse release shrinking (opt in with -Pfuse.r8=true until verified on devices).
# Shrink only: names stay readable, so a crash report on the device still says where it happened.
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
