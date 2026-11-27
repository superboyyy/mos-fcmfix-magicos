package de.robv.android.xposed.callbacks;

/**
 * Xposed 回调参数桩（编译期使用）。
 */
public class XC_LoadPackage {

    public static class LoadPackageParam {
        public String packageName;
        public ClassLoader classLoader;
        public String processName;
        public boolean isFirstApplication;
    }
}
