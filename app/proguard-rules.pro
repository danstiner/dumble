# App-specific R8 keep rules. Compose, Hilt, and AndroidX ship their own
# consumer rules, so this stays minimal until reflection/JNI/serialization
# surfaces are added in later PRs.

# protobuf-javalite ships as a plain .jar with no consumer rules. GeneratedMessageLite
# reaches fields reflectively; R8 renaming them fails at runtime, not build time.
-assumevalues class com.google.protobuf.Android { static boolean ASSUME_ANDROID return true; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
