-keep class com.ping.verbead.** { *; }

# Keep all native methods across the app
-keepclasseswithmembernames class * {
    native <methods>;
}

# ONNX Runtime Android
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Sherpa-onnx JNI
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# ZXingCpp Barcode reader
-keep class com.zxingcpp.** { *; }
-dontwarn com.zxingcpp.**

# OpenCC4J
-keep class com.github.houbb.opencc4j.** { *; }
-dontwarn com.github.houbb.opencc4j.**

# Apache Commons Compress
-dontwarn org.apache.commons.compress.**
