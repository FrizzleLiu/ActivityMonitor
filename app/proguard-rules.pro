# Add project specific ProGuard rules here.
# The embedded helper is launched by name via `app_process`, so R8 must keep its entry point.
-keep class com.june.activitymonitor.privileged.HelperServer {
    public static void main(java.lang.String[]);
}
-keep class com.june.activitymonitor.privileged.HelperClient { *; }
