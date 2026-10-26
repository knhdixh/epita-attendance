# Bridge.java finds the Custom Tabs session by reflection (by field type), so keep these fields.
-keepclassmembers class com.google.androidbrowserhelper.trusted.LauncherActivity { com.google.androidbrowserhelper.trusted.TwaLauncher *; }
-keepclassmembers class com.google.androidbrowserhelper.trusted.TwaLauncher { androidx.browser.customtabs.CustomTabsSession *; }
