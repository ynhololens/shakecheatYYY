package com.shakecheat;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import com.shakecheat.AntiRecallHook;

/**
 * 摇一摇作弊 Xposed 模块 — 系统层传感器数据注入版
 *
 * 核心原理：
 * 1. Hook SensorManager.registerListener 所有重载，收集加速度监听器
 * 2. 使用 Unsafe.allocateInstance 创建伪造的 SensorEvent（兼容所有 Android 版本）
 * 3. 定时向收集到的监听器回调伪造的加速度数据
 * 4. 同时 Hook SystemSensorManager 内部实现层，捕获非公开 API 注册的监听器
 * 5. 额外 Hook Chromium WebView 的 PlatformSensor，
 *    确保 WebView 中 devicemotion 事件也能收到伪造数据
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "[ShakeCheat]";
    private static final String MODULE_PACKAGE = "com.shakecheat";
    private static final String PREFS_NAME = "shakecheat_prefs";
    private static final int RELOAD_INTERVAL = 10;

    private XSharedPreferences prefs;
    private Handler handler;

    // 已注册的加速度传感器监听器
    private final CopyOnWriteArrayList<ListenerInfo> listeners = new CopyOnWriteArrayList<>();
    // 使用 Set 跟踪已经 hook 过 onSensorChanged 的监听器 class
    private final Set<Class<?>> hookedListenerClasses = new HashSet<>();

    private volatile boolean isInjecting = false;
    private boolean toggle = false;
    private final AtomicInteger injectCounter = new AtomicInteger(0);

    private boolean enabled = true;
    private int shakesPerSecond = 30;

    private volatile boolean javaHomeSet = false;

    private static class ListenerInfo {
        final SensorEventListener listener;
        final Sensor sensor;
        final Handler callbackHandler;

        ListenerInfo(SensorEventListener listener, Sensor sensor, Handler handler) {
            this.listener = listener;
            this.sensor = sensor;
            this.callbackHandler = handler;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ListenerInfo)) return false;
            return listener == ((ListenerInfo) o).listener;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(listener);
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        String pkg = lpparam.packageName;

        // 处理微信进程
        if ("com.tencent.mm".equals(pkg)) {
            XposedBridge.log(TAG + " 微信进程: " + lpparam.processName);
            initAndHook(lpparam);
            return;
        }

        // 处理模块自身进程（兼容 XSharedPreferences 访问）
        if (MODULE_PACKAGE.equals(pkg)) {
            XposedBridge.log(TAG + " 模块自身进程加载");
            return;
        }
    }

    private void initAndHook(XC_LoadPackage.LoadPackageParam lpparam) {
        // 读取配置
        prefs = new XSharedPreferences(MODULE_PACKAGE, PREFS_NAME);
        try {
            prefs.makeWorldReadable();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " makeWorldReadable 失败: " + t.getMessage());
        }
        reloadPrefs();

        if (!enabled) {
            XposedBridge.log(TAG + " 模块已禁用，跳过 Hook");
        }

        XposedBridge.log(TAG + " 开始 Hook 传感器系统，每秒注入: " + shakesPerSecond + " 次");

        handler = new Handler(Looper.getMainLooper());

        // ========== 方案一：Hook 公开 API ==========
        hookSensorManagerPublic(lpparam.classLoader);

        // ========== 方案二：Hook 内部实现层 ==========
        hookSystemSensorManager();

        // ========== 方案三：Hook Chromium WebView Sensor 路径 ==========
        hookChromiumSensor();

        // ========== 方案四：微信消息防撤回 ==========
        try {
            new AntiRecallHook().hook(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 防撤回 Hook 异常: " + t.getMessage());
        }

        XposedBridge.log(TAG + " 所有 Hook 注册完成");
    }

    // ======================== 配置 ========================

    private static final String TMP_CFG = "/data/local/tmp/shakecheat_config.txt";

    /** 每次注入都读取配置 */
    private void reloadPrefs() {
        boolean oldEnabled = enabled;
        int oldRate = shakesPerSecond;

        // 方式 A：读 /data/local/tmp/ 文件（由 SettingsActivity 通过 su 写入，全局可读）
        File f = new File(TMP_CFG);
        if (f.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("enabled="))
                        enabled = "true".equals(line.substring(8).trim());
                    else if (line.startsWith("speed=")) {
                        try { shakesPerSecond = Integer.parseInt(line.substring(6).trim()); }
                        catch (NumberFormatException ignored) {}
                    }
                }
                clampAndLog(oldEnabled, oldRate);
                return;
            } catch (Throwable t) {
            }
        }

        // 方式 B：XSharedPreferences 兜底
        try {
            prefs.reload();
            enabled = prefs.getBoolean("enabled", true);
            shakesPerSecond = prefs.getInt("shakes_per_second", 30);
        } catch (Throwable ignored) {}

        clampAndLog(oldEnabled, oldRate);
    }

    private void clampAndLog(boolean oldEnabled, int oldRate) {
        if (shakesPerSecond < 1) shakesPerSecond = 1;
        if (shakesPerSecond > 50) shakesPerSecond = 50;
    }

    // ======================== 方案一：公开 API Hook ========================

    private void hookSensorManagerPublic(ClassLoader classLoader) {
        String smClass = "android.hardware.SensorManager";

        // 逐一 Hook 每个重载，不通过数组传参（避免 LSPosed API 101 varargs 解析问题）
        hookRegisterListenerDirect(smClass, classLoader, 3, -1);
        hookRegisterListenerDirect(smClass, classLoader, 4, 3);
        hookRegisterListenerDirect(smClass, classLoader, 4, -1);
        hookRegisterListenerDirect(smClass, classLoader, 5, 4);

        // Hook unregisterListener
        try {
            XposedHelpers.findAndHookMethod(smClass, classLoader,
                    "unregisterListener", SensorEventListener.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            removeListener((SensorEventListener) param.args[0]);
                        }
                    });
            XposedBridge.log(TAG + " Hook SensorManager.unregisterListener(SensorEventListener) 成功");
        } catch (Throwable ignored) {}

        try {
            XposedHelpers.findAndHookMethod(smClass, classLoader,
                    "unregisterListener", SensorEventListener.class, Sensor.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            removeListener((SensorEventListener) param.args[0]);
                        }
                    });
        } catch (Throwable ignored) {}
    }

    /**
     * 直接调用 findAndHookMethod，每个 Class 参数独立传给 varargs（不经过数组中转）
     */
    private void hookRegisterListenerDirect(String className, ClassLoader classLoader,
                                             int paramCount, int handlerArgIndex) {
        String methodName = "registerListener";
        String errorInfo = "(" + paramCount + "参数)";
        try {
            XC_MethodHook hook = createRegisterHook(handlerArgIndex);
            Class<?>[] types = getRegisterParamTypes(paramCount);
            if (types == null) return;

            // 用反射调用 findAndHookMethod，避免 varargs 数组问题
            // 方式：直接用 XposedHelpers.findAndHookMethod 传参
            switch (paramCount) {
                case 3:
                    XposedHelpers.findAndHookMethod(className, classLoader,
                            methodName,
                            types[0], types[1], types[2],
                            hook);
                    break;
                case 4:
                    XposedHelpers.findAndHookMethod(className, classLoader,
                            methodName,
                            types[0], types[1], types[2], types[3],
                            hook);
                    break;
                case 5:
                    XposedHelpers.findAndHookMethod(className, classLoader,
                            methodName,
                            types[0], types[1], types[2], types[3], types[4],
                            hook);
                    break;
            }
            XposedBridge.log(TAG + " Hook SensorManager.registerListener" + errorInfo + " 成功");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Hook registerListener" + errorInfo + " 失败: " + t.getMessage());
        }
    }

    private Class<?>[] getRegisterParamTypes(int paramCount) {
        switch (paramCount) {
            case 3: return new Class<?>[]{SensorEventListener.class, Sensor.class, int.class};
            case 4: return new Class<?>[]{SensorEventListener.class, Sensor.class, int.class, Handler.class};
            case 5: return new Class<?>[]{SensorEventListener.class, Sensor.class, int.class, int.class, Handler.class};
        }
        return null;
    }

    private XC_MethodHook createRegisterHook(int handlerArgIndex) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.getResult() != null && !(Boolean) param.getResult()) return;

                SensorEventListener listener = null;
                Sensor sensor = null;
                Handler handlerParam = null;

                for (int i = 0; i < param.args.length; i++) {
                    Object arg = param.args[i];
                    if (arg instanceof SensorEventListener && listener == null) {
                        listener = (SensorEventListener) arg;
                    } else if (arg instanceof Sensor && sensor == null) {
                        sensor = (Sensor) arg;
                    } else if (handlerArgIndex == i && arg instanceof Handler) {
                        handlerParam = (Handler) arg;
                    }
                }

                recordListener(listener, sensor, handlerParam);
            }
        };
    }

    // ======================== 方案二：内部实现层 Hook ========================

    /**
     * 尝试 Hook SystemSensorManager 内部 registerListenerImpl。
     * 该方法签名在不同 Android 版本上不一致，这里尝试多种变体。
     */
    private void hookSystemSensorManager() {
        String ssmClass = "android.hardware.SystemSensorManager";

        // 尝试 6 参数签名：listener, sensor, rateUs, maxLatency, handler, mode
        try {
            XposedHelpers.findAndHookMethod(ssmClass, null,
                    "registerListenerImpl",
                    SensorEventListener.class, Sensor.class, int.class, int.class,
                    Handler.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            recordListener(
                                    (SensorEventListener) param.args[0],
                                    (Sensor) param.args[1],
                                    (Handler) param.args[4]);
                        }
                    });
            XposedBridge.log(TAG + " Hook SystemSensorManager.registerListenerImpl(6参) 成功");
        } catch (Throwable ignored) {}

        // 尝试 7 参数签名：listener, sensor, rateUs, maxLatency, handler, mode, requestType
        try {
            XposedHelpers.findAndHookMethod(ssmClass, null,
                    "registerListenerImpl",
                    SensorEventListener.class, Sensor.class, int.class, int.class,
                    Handler.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            recordListener(
                                    (SensorEventListener) param.args[0],
                                    (Sensor) param.args[1],
                                    (Handler) param.args[4]);
                        }
                    });
            XposedBridge.log(TAG + " Hook SystemSensorManager.registerListenerImpl(7参) 成功");
        } catch (Throwable ignored) {}

        // 尝试 5 参数签名（较早版本）：listener, sensor, rateUs, maxLatency, handler
        try {
            XposedHelpers.findAndHookMethod(ssmClass, null,
                    "registerListenerImpl",
                    SensorEventListener.class, Sensor.class, int.class, int.class,
                    Handler.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            recordListener(
                                    (SensorEventListener) param.args[0],
                                    (Sensor) param.args[1],
                                    (Handler) param.args[4]);
                        }
                    });
            XposedBridge.log(TAG + " Hook SystemSensorManager.registerListenerImpl(5参) 成功");
        } catch (Throwable ignored) {}

        // Hook SensorManager.registerListenerImpl 同样方式
        String smClass = "android.hardware.SensorManager";
        try {
            XposedHelpers.findAndHookMethod(smClass, null,
                    "registerListenerImpl",
                    SensorEventListener.class, Sensor.class, int.class, int.class,
                    Handler.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            recordListener(
                                    (SensorEventListener) param.args[0],
                                    (Sensor) param.args[1],
                                    (Handler) param.args[4]);
                        }
                    });
            XposedBridge.log(TAG + " Hook SensorManager.registerListenerImpl(6参) 成功");
        } catch (Throwable ignored) {}
    }

    // ======================== 方案三：Chromium WebView 传感器路径 Hook ========================

    /**
     * Chromium/WebView 在 Android 上使用 org.chromium.device.sensors 包下的类。
     * Hook PlatformSensor 的 onSensorChanged 方法可以拦截 WebView 中的 devicemotion 数据。
     */
    private void hookChromiumSensor() {
        // 尝试多个已知的 Chromium 传感器类名
        String[] chromiumClasses = {
                "org.chromium.device.sensors.PlatformSensor",
                "org.chromium.device.sensors.PlatformSensor$PlatformSensorAndroid",
        };

        for (String className : chromiumClasses) {
            try {
                XposedHelpers.findAndHookMethod(className, null,
                        "onSensorChanged", SensorEvent.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (!enabled) return;
                                if (toggle) {
                                    SensorEvent event = (SensorEvent) param.args[0];
                                    event.values[0] = 40f + (float) (Math.random() * 20f);
                                    event.values[1] = 30f + (float) (Math.random() * 20f);
                                    event.values[2] = -15f - (float) (Math.random() * 10f);
                                }
                                // 注：这里不切换 toggle，由注入循环统一管理
                            }
                        });
                XposedBridge.log(TAG + " Hook " + className + ".onSensorChanged 成功");
            } catch (Throwable ignored) {}
        }

        // 尝试 Hook Chromium 的 SensorManagerProxy
        String[] proxyClasses = {
                "org.chromium.device.sensors.SensorManagerProxyImpl",
        };
        for (String className : proxyClasses) {
            try {
                XposedHelpers.findAndHookMethod(className, null,
                        "createSensorEventListener", Sensor.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Object result = param.getResult();
                                if (result instanceof SensorEventListener) {
                                    SensorEventListener listener = (SensorEventListener) result;
                                    Sensor sensor = (Sensor) param.args[0];
                                    recordListener(listener, sensor, null);
                                }
                            }
                        });
                XposedBridge.log(TAG + " Hook " + className + ".createSensorEventListener 成功");
            } catch (Throwable ignored) {}
        }
    }

    // ======================== 监听器管理 ========================

    private void recordListener(SensorEventListener listener, Sensor sensor, Handler callbackHandler) {
        if (listener == null || sensor == null) return;
        if (sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;

        ListenerInfo info = new ListenerInfo(listener, sensor, callbackHandler);
        boolean isNew = !listeners.contains(info);
        if (isNew) {
            listeners.add(info);
            XposedBridge.log(TAG + " [发现] 加速度监听器: " + listener.getClass().getName()
                    + ", 总数=" + listeners.size());
        }

        // 尝试 Hook 该监听器的 onSensorChanged 方法（双保险）
        hookListenerOnSensorChanged(listener);

        // 确保注入循环已启动
        startInjecting();
    }

    private void removeListener(SensorEventListener listener) {
        if (listener == null) return;
        listeners.removeIf(info -> info.listener == listener);
        XposedBridge.log(TAG + " [移除] 监听器: " + listener.getClass().getName()
                + ", 剩余=" + listeners.size());
    }

    /**
     * 直接 Hook 监听器实例的 onSensorChanged 方法。
     * 当真实传感器数据到达时，拦截并修改数值为大幅值，触发摇一摇。
     */
    private void hookListenerOnSensorChanged(SensorEventListener listener) {
        Class<?> listenerClass = listener.getClass();
        if (hookedListenerClasses.contains(listenerClass)) return;

        try {
            final Method onSensorChanged = listenerClass.getDeclaredMethod(
                    "onSensorChanged", SensorEvent.class);
            onSensorChanged.setAccessible(true);

            // 使用 XposedBridge 的实例级 hook（只 hook 这个实例）
            // 实际上 XposedBridge.hookMethod 是类级 hook，所以我们检查 hookList
            // 用监听器类做去重
            if (!hookedListenerClasses.add(listenerClass)) return;

            XposedBridge.hookMethod(onSensorChanged, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!enabled || !isInjecting) return;
                    SensorEvent event = (SensorEvent) param.args[0];
                    if (event == null) return;

                    // 大幅修改加速度值，确保超过摇一摇检测阈值
                    if (toggle) {
                        event.values[0] = 40f + (float) (Math.random() * 15f);
                        event.values[1] = 35f + (float) (Math.random() * 15f);
                        event.values[2] = 25f + (float) (Math.random() * 15f);
                    } else {
                        event.values[0] = -20f - (float) (Math.random() * 10f);
                        event.values[1] = -15f - (float) (Math.random() * 10f);
                        event.values[2] = -10f - (float) (Math.random() * 10f);
                    }
                }
            });
            XposedBridge.log(TAG + " [Hook] onSensorChanged: " + listenerClass.getName());
        } catch (NoSuchMethodException e) {
            // 监听器可能是动态代理，没有显式的 onSensorChanged 方法
            XposedBridge.log(TAG + " [信息] " + listenerClass.getName()
                    + " 无显式 onSensorChanged (可能是代理): " + e.getMessage());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " [Hook onSensorChanged] 失败: " + t.getMessage());
        }
    }

    // ======================== 注入循环 ========================

    private synchronized void startInjecting() {
        if (isInjecting) return;
        isInjecting = true;
        XposedBridge.log(TAG + " [注入] 启动注入循环");
        injectLoop();
    }

    private void stopInjecting() {
        isInjecting = false;
    }

    private void injectLoop() {
        reloadPrefs();

        if (!enabled) {
            if (isInjecting) {
                isInjecting = false;
                XposedBridge.log(TAG + " [注入] 已暂停");
            }
            // 暂停状态：仍然循环检查，等 enabled 变 true 后恢复
            handler.postDelayed(this::injectLoop, 500);
            return;
        }

        // enabled=true 但注入已停止 → 恢复注入
        if (!isInjecting) {
            isInjecting = true;
            XposedBridge.log(TAG + " [注入] 已恢复");
        }

        if (!listeners.isEmpty()) {
            injectFakeSensorData();
            toggle = !toggle;
        }

        long intervalMs = Math.max(20, 1000L / Math.max(1, shakesPerSecond));
        handler.postDelayed(this::injectLoop, intervalMs);
    }

    /**
     * 构造并注入伪造的加速度数据
     */
    private void injectFakeSensorData() {
        if (listeners.isEmpty()) return;

        float x, y, z;
        if (toggle) {
            x = 40f + (float) (Math.random() * 20f);
            y = 30f + (float) (Math.random() * 20f);
            z = 25f + (float) (Math.random() * 20f);
        } else {
            x = -20f - (float) (Math.random() * 10f);
            y = -15f - (float) (Math.random() * 10f);
            z = -10f - (float) (Math.random() * 10f);
        }

        int successCount = 0;
        int failCount = 0;
        for (ListenerInfo info : listeners) {
            try {
                SensorEvent event = createFakeSensorEventSafe(info.sensor, x, y, z);
                if (event == null) {
                    failCount++;
                    continue;
                }

                Runnable callback = new Runnable() {
                    @Override
                    public void run() {
                        try {
                            info.listener.onSensorChanged(event);
                        } catch (Throwable ignored) {}
                    }
                };

                if (info.callbackHandler != null) {
                    info.callbackHandler.post(callback);
                } else {
                    handler.post(callback);
                }
                successCount++;
            } catch (Throwable t) {
                failCount++;
                XposedBridge.log(TAG + " [注入] 向 " + info.listener.getClass().getSimpleName()
                        + " 注入失败: " + t.getMessage());
            }
        }
    }

    // ======================== SensorEvent 创建 (Unsafe) ========================

    /**
     * 使用 Unsafe.allocateInstance 创建 SensorEvent。
     * 这是最兼容的方式 —— 不调用任何构造函数，直接分配内存。
     */
    private volatile Object unsafeInstance;
    private volatile Class<?> unsafeClass;

    private Object getUnsafe() {
        if (unsafeInstance != null) return unsafeInstance;
        try {
            Class<?> clazz = Class.forName("sun.misc.Unsafe");
            Field field = clazz.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            unsafeInstance = field.get(null);
            unsafeClass = clazz;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " [错误] 获取 Unsafe 失败: " + t.getMessage());
        }
        return unsafeInstance;
    }

    private SensorEvent createFakeSensorEventSafe(Sensor sensor, float x, float y, float z) {
        // 方式 A: Unsafe.allocateInstance (最可靠，Android 7+ 都支持)
        try {
            Object unsafe = getUnsafe();
            if (unsafe != null) {
                Method allocateMethod = unsafeClass.getMethod("allocateInstance", Class.class);
                SensorEvent event = (SensorEvent) allocateMethod.invoke(unsafe, SensorEvent.class);
                // values 是 final 字段，需要用反射设置
                setField(event, "values", new float[]{x, y, z});
                setSensorEventFields(event, sensor);
                return event;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " [创建] Unsafe.allocateInstance 失败: " + t.getMessage());
        }

        // 方式 B: 反射 4 参构造函数
        try {
            java.lang.reflect.Constructor<SensorEvent> c = SensorEvent.class.getDeclaredConstructor(
                    int.class, Sensor.class, int.class, long.class);
            c.setAccessible(true);
            SensorEvent event = c.newInstance(3, sensor,
                    SensorManager.SENSOR_STATUS_ACCURACY_HIGH, SystemClock.elapsedRealtimeNanos());
            event.values[0] = x;
            event.values[1] = y;
            event.values[2] = z;
            return event;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " [创建] 反射4参构造失败: " + t.getMessage());
        }

        // 方式 C: 反射单参构造函数 + 反射设置字段
        try {
            java.lang.reflect.Constructor<SensorEvent> c = SensorEvent.class.getDeclaredConstructor(int.class);
            c.setAccessible(true);
            SensorEvent event = c.newInstance(3);
            event.values[0] = x;
            event.values[1] = y;
            event.values[2] = z;
            setSensorEventFields(event, sensor);
            return event;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " [创建] 反射单参构造失败: " + t.getMessage());
        }

        XposedBridge.log(TAG + " [创建] 所有 SensorEvent 创建方式均失败！传感器注入将无效");
        return null;
    }

    private void setSensorEventFields(SensorEvent event, Sensor sensor) throws Exception {
        setField(event, "sensor", sensor);
        setField(event, "timestamp", SystemClock.elapsedRealtimeNanos());
        setField(event, "accuracy", SensorManager.SENSOR_STATUS_ACCURACY_HIGH);
    }

    private void setField(Object obj, String name, Object value) throws Exception {
        Field field = obj.getClass().getDeclaredField(name);
        field.setAccessible(true);
        // 如果字段是 final，尝试移除 final 修饰符
        try {
            Field modifiers = Field.class.getDeclaredField("modifiers");
            modifiers.setAccessible(true);
            modifiers.setInt(field, field.getModifiers() & ~Modifier.FINAL);
        } catch (Throwable ignored) {}
        field.set(obj, value);
    }

    private void writeConfigViaSu(boolean enable) {
        try {
            // 读取当前 anti_recall 配置，防止覆盖丢失
            boolean antiRecall = false;
            File f = new File(TMP_CFG);
            if (f.exists()) {
                try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith("anti_recall="))
                            antiRecall = "true".equals(line.substring(12).trim());
                    }
                } catch (Throwable ignored) {}
            }
            String content = "enabled=" + enable + "\nspeed=" + shakesPerSecond
                    + "\nanti_recall=" + antiRecall + "\n";
            String escaped = content.replace("'", "'\\''");
            Runtime.getRuntime().exec(new String[]{
                "su", "-c",
                "echo '" + escaped + "' > /data/local/tmp/shakecheat_config.txt && chmod 644 /data/local/tmp/shakecheat_config.txt"
            });
        } catch (Throwable ignored) {}
    }
}
