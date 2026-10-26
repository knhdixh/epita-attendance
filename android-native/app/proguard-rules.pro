# Keep kotlinx.serialization classes and metadata
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn java.lang.ClassValue

# Keep Retrofit interface methods and types
-keepattributes Exceptions
-keepclassmembers class * {
    @retrofit2.http.* <methods>;
}
