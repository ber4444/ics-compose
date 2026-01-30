# Add project specific ProGuard rules here.

# Ktor
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.atomicfu.**
-dontwarn io.netty.**
-dontwarn com.typesafe.**
-dontwarn org.slf4j.**

# Keep all classes in our package
-keep class com.livingpresence.inner.circle.squared.** { *; }
