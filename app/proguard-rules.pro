# Keep line numbers so release stack traces stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Optional annotation and security-provider classes that libraries reference but the app does not ship.
-dontwarn org.jspecify.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.re2j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
