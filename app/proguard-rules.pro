-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }

# --- release (R8) ---
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, RuntimeVisibleAnnotations, AnnotationDefault
# TensorFlow Lite (JNI)
-keep class org.tensorflow.** { *; }
-dontwarn org.tensorflow.**
# Ktor server (service loaders, reflection on engine/plugins)
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn io.netty.**
-dontwarn java.lang.management.**
-dontwarn javax.**
-dontwarn org.fusesource.**
-dontwarn reactor.blockhound.**
# kotlinx.serialization: keep generated serializers of our models
-keepclassmembers class io.github.assistent.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.assistent.**$$serializer { *; }
# Room entities/DAOs are generated; keep our model classes intact for JSON/Room
-keep class io.github.assistent.data.** { *; }
-keep class io.github.assistent.config.** { *; }
# ZXing / OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
