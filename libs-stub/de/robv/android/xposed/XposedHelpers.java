package de.robv.android.xposed;

public final class XposedHelpers {

    public static XC_MethodHook.Unhook findAndHookMethod(
            Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            String className, ClassLoader cl, String methodName, Object... parameterTypesAndCallback) {
        return null;
    }

    public static Class<?> findClass(String className, ClassLoader cl) {
        return null;
    }

    public static boolean classExists(String className, ClassLoader cl) { return false; }

    public static Object getObjectField(Object obj, String fieldName) { return null; }
    public static int getIntField(Object obj, String fieldName) { return 0; }
    public static long getLongField(Object obj, String fieldName) { return 0L; }
    public static boolean getBooleanField(Object obj, String fieldName) { return false; }
    public static void setObjectField(Object obj, String fieldName, Object value) {}
    public static void setIntField(Object obj, String fieldName, int value) {}
    public static void setBooleanField(Object obj, String fieldName, boolean value) {}
    public static Object getStaticObjectField(Class<?> clazz, String fieldName) { return null; }
    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {}
    public static Object callMethod(Object obj, String methodName, Object... args) { return null; }
    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) { return null; }
}
