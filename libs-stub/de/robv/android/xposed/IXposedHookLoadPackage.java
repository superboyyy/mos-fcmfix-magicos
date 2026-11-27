package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed 模块入口接口（编译桩）。
 * 运行时由 LSPosed 提供同名同签名接口的真实实现。
 */
public interface IXposedHookLoadPackage {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
