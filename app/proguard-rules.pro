# ProGuard rules for Android_MIC

# Keep all project classes, inner classes, and their public constructors/methods
-keep class com.antigravity.androidmic.** { *; }
-keepclassmembers class com.antigravity.androidmic.** { *; }

# Preserve custom Views and constructors for XML layout inflation
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}

# Keep ViewBinding generated classes
-keep class androidx.viewbinding.ViewBinding { *; }
-keep class com.antigravity.androidmic.databinding.** { *; }

# Keep Material Components and Sliders
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**
