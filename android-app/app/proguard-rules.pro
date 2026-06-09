# kotlinx.serialization keeps generated serializers via @Serializable; keep them.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.conduit.android.connection.** {
    *** Companion;
}
-keepclasseswithmembers class com.conduit.android.connection.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Java-WebSocket
-keep class org.java_websocket.** { *; }
