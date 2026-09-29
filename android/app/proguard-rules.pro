# kotlinx.serialization keeps its generated serializers via its own rules;
# keep our @Serializable model classes' companions for reflection-free lookup.
-keepclassmembers class com.example.pinch.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.pinch.** {
    kotlinx.serialization.KSerializer serializer(...);
}
