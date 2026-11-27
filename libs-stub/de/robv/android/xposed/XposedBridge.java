package de.robv.android.xposed;

import java.lang.reflect.Member;
import java.util.Set;

/**
 * Xposed API 编译桩：仅用于编译期，运行时由 LSPosed 注入真实实现。
 * 签名（含返回类型）必须与真实 API 一致，否则运行时 NoSuchMethodError。
 */
public final class XposedBridge {
    public static void log(String text) {}
    public static void log(Throwable t) {}
    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> clazz, String methodName, XC_MethodHook hook) {
        return null;
    }
    /** LSPosed 扩展：让被内联的调用方重新走真正的方法调用 */
    public static void deoptimizeMethod(Member method) {}
    public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) { return null; }
    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> clazz, XC_MethodHook hook) {
        return null;
    }
}
