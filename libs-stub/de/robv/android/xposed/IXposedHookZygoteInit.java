package de.robv.android.xposed;

/**
 * Xposed Zygote 初始化回调（编译桩）。
 * LSPosed 在 zygote 启动时就会调用这个入口，比 handleLoadPackage 更早。
 */
public interface IXposedHookZygoteInit {
    void initZygote(StartupParam startupParam) throws Throwable;

    class StartupParam {
        public String modulePath;
        public boolean startsSystemServer;
    }
}
