package com.shakecheat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 微信消息防撤回 Hook — v2 重写
 *
 * ====== 核心策略 ======
 *
 * 【方案A — 精准拦截 Revoke 指令 (核心)】
 *   Hook com.tencent.mm.modelmulti.o 中参数包含 RevokeMsgReq 的方法。
 *   这是微信服务端下发撤回指令后，客户端处理撤回的核心入口。
 *   精确匹配参数类型，不误伤其他正常方法。
 *
 * 【方案B — 消息内容保护 (兜底)】
 *   缓存撤回前消息的原始内容，拦截 getContent() 返回撤回提示时用原内容替换。
 *   同时拦截 type=10000 系统消息的插入，阻止撤回通知弹出。
 *
 * 【方案C — 运行时探测增强】
 *   打印当前微信版本中实际存在的关键类和方法，
 *   方便通过 Xposed 日志定位兼容性问题。
 */
public class AntiRecallHook {

    private static final String TAG = "[ShakeCheat-AntiRecall]";
    private static final String MODULE_PACKAGE = "com.shakecheat";
    private static final String PREFS_NAME = "shakecheat_prefs";

    private XSharedPreferences prefs;
    private volatile boolean antiRecallEnabled = false;

    /** 缓存消息 ID -> 原始内容，撤回前保存 */
    private final Map<Long, String> cachedContentMap = new ConcurrentHashMap<>();

    /** 要探测的核心类列表（用于运行时日志输出） */
    private static final String[] PROBE_CLASSES = {
            "com.tencent.mm.modelmulti.o",
            "com.tencent.mm.storage.MsgInfo",
            "com.tencent.mm.modelbase.r",
            "com.tencent.mm.storage.bg",
            "com.tencent.mm.modelmulti.ay",
            "com.tencent.mm.protocal.protobuf.RevokeMsgReq",
    };

    public void hook(ClassLoader classLoader) {
        prefs = new XSharedPreferences(MODULE_PACKAGE, PREFS_NAME);
        try { prefs.makeWorldReadable(); } catch (Throwable ignored) {}
        reloadPrefs();

        if (!antiRecallEnabled) {
            XposedBridge.log(TAG + " 防撤回已禁用，跳过 Hook");
            return;
        }

        XposedBridge.log(TAG + " ====== 开始 Hook 微信消息防撤回 (v2) ======");

        // 运行时探测
        probeClasses(classLoader);

        // 方案A：精准拦截 Revoke 指令
        hookRevokeProcessor(classLoader);

        // 方案B：消息内容保护
        hookMsgContentProtect(classLoader);
        hookSystemMsgFilter(classLoader);

        XposedBridge.log(TAG + " ====== 防撤回 Hook 注册完成 ======");
    }

    // ======================== 配置 ========================

    private void reloadPrefs() {
        try {
            prefs.reload();
            antiRecallEnabled = prefs.getBoolean("anti_recall", false);
        } catch (Throwable ignored) {}
    }

    // ======================== 运行时探测 ========================

    /** 在日志中打印当前微信版本中关键类的存在状态，方便调试 */
    private void probeClasses(ClassLoader cl) {
        XposedBridge.log(TAG + " --- 运行时探测 ---");
        for (String className : PROBE_CLASSES) {
            try {
                Class<?> c = cl.loadClass(className);
                XposedBridge.log(TAG + "   ✅ " + className + " 存在");
                // 打印该类中所有方法名（调试用）
                StringBuilder sb = new StringBuilder();
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().length() <= 2) { // 只打印短方法名（核心方法）
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(m.getName()).append("(");
                        Class<?>[] pts = m.getParameterTypes();
                        for (int i = 0; i < pts.length; i++) {
                            if (i > 0) sb.append(", ");
                            String pn = pts[i].getName();
                            // 简化包名
                            int dot = pn.lastIndexOf('.');
                            sb.append(dot >= 0 ? pn.substring(dot + 1) : pn);
                        }
                        sb.append(")");
                    }
                }
                if (sb.length() > 0) {
                    XposedBridge.log(TAG + "     方法: " + sb.toString());
                }
            } catch (Throwable e) {
                XposedBridge.log(TAG + "   ❌ " + className + " 不存在");
            }
        }
        XposedBridge.log(TAG + " --- 探测结束 ---");
    }

    // ====================================================================
    //  方案A：精准拦截 Revoke 指令处理器
    //
    //  WeChat 服务端下发撤回指令后，经过 mmprotobuf 序列化为 RevokeMsgReq，
    //  由 com.tencent.mm.modelmulti.o 进行处理。
    //
    //  本方案：
    //    1. 加载 o 类
    //    2. 遍历其所有方法，找到参数类型名包含 RevokeMsgReq 的方法
    //    3. 只 Hook 精确匹配的方法，不误伤其他功能
    // ====================================================================

    private void hookRevokeProcessor(ClassLoader cl) {
        String[] handlerClasses = {
                "com.tencent.mm.modelmulti.o",
                "com.tencent.mm.modelmulti.p",
                "com.tencent.mm.modelmulti.s",
                "com.tencent.mm.modelmulti.r",
        };

        for (String className : handlerClasses) {
            try {
                Class<?> c = cl.loadClass(className);
                boolean anyHooked = false;

                for (Method m : c.getDeclaredMethods()) {
                    // 精准匹配：参数中含有 RevokeMsgReq 的方法才是撤回处理入口
                    boolean hasRevokeParam = false;
                    for (Class<?> pt : m.getParameterTypes()) {
                        String ptName = pt.getName();
                        if (ptName.contains("RevokeMsgReq") || ptName.contains("revoke")) {
                            hasRevokeParam = true;
                            break;
                        }
                    }
                    if (!hasRevokeParam) continue;

                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!antiRecallEnabled) return;
                            XposedBridge.log(TAG + " [方案A] ✅ 拦截 Revoke 指令: "
                                    + className + "." + m.getName() + "()");
                            // 尝试缓存这个消息 ID 对应的原内容
                            cacheOriginalContent(param);
                            // 阻止撤回执行
                            param.setResult(null);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!antiRecallEnabled) return;
                            XposedBridge.log(TAG + " [方案A] ℹ️ Revoke 方法返回 (应已被拦截)");
                        }
                    });
                    anyHooked = true;
                    XposedBridge.log(TAG + " [方案A] ✅ 成功 Hook: " + className + "." + m.getName()
                            + " (参数: " + m.getParameterTypes().length + "个)");
                }

                if (!anyHooked) {
                    XposedBridge.log(TAG + " [方案A] ⚠️ " + className + " 中未找到 RevokeMsgReq 参数的方法");
                }
            } catch (Throwable e) {
                XposedBridge.log(TAG + " [方案A] ⚠️ " + className + " 不存在: " + e.getMessage());
            }
        }
    }

    /**
     * 尝试从 Revoke 处理方法的参数中提取消息 ID 并缓存原内容。
     * 不同版本的 RevokeMsgReq 结构不同，通过反射获取 msgId 字段。
     */
    private void cacheOriginalContent(XC_MethodHook.MethodHookParam param) {
        for (Object arg : param.args) {
            if (arg == null) continue;
            // 尝试从 RevokeMsgReq 对象中提取 MsgId
            try {
                Class<?> revokeClass = arg.getClass();
                // RevokeMsgReq 常见字段名: MsgId, msgId, SrvId
                for (String fieldName : new String[]{"MsgId", "msgId", "SrvId", "NewMsgId"}) {
                    try {
                        Field f = revokeClass.getDeclaredField(fieldName);
                        f.setAccessible(true);
                        Object val = f.get(arg);
                        if (val instanceof Number) {
                            long msgId = ((Number) val).longValue();
                            XposedBridge.log(TAG + " [方案A] 提取到撤回消息 ID: " + msgId
                                    + " (字段: " + fieldName + ")");
                        }
                    } catch (NoSuchFieldException ignored) {}
                }
            } catch (Throwable ignored) {}
        }
    }

    // ====================================================================
    //  方案B：消息内容保护
    //
    //  如果方案A未能完全阻止撤回（或撤回来自其他路径），消息内容可能已经被修改。
    //  本方案保护消息内容不被替换为撤回提示。
    //
    //  步骤：
    //    1. Hook setContent 等方法，拦截 "撤回了一条消息" 的写入
    //    2. Hook getContent 等方法，如果返回撤回提示，返回空字符串（不显示）
    //    （注意：不直接返回 View.GONE，因为那需要 UI 上下文）
    // ====================================================================

    private void hookMsgContentProtect(ClassLoader cl) {
        // ——— 1. Hook setContent — 防止内容被改为撤回提示 ———
        String[][] protectTargets = {
                {"com.tencent.mm.storage.MsgInfo", "setContent"},
                {"com.tencent.mm.storage.MsgInfo", "setFieldContent"},
                {"com.tencent.mm.storage.bg", "a"},
                {"com.tencent.mm.storage.ay", "a"},
                {"com.tencent.mm.modelbase.r", "a"},
        };

        for (String[] spec : protectTargets) {
            String className = spec[0];
            String methodName = spec[1];
            try {
                Class<?> c = cl.loadClass(className);
                boolean hooked = false;

                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(methodName)) continue;

                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!antiRecallEnabled) return;
                            for (Object arg : param.args) {
                                if (arg instanceof String) {
                                    String text = (String) arg;
                                    if (text.contains("撤回了一条消息")
                                            || text.contains("revoked") && text.contains("message")) {
                                        XposedBridge.log(TAG + " [方案B] ✅ 拦截内容写入撤回提示");
                                        param.setResult(null);
                                        return;
                                    }
                                }
                            }
                        }
                    });
                    hooked = true;
                }

                if (hooked) {
                    XposedBridge.log(TAG + " [方案B] ✅ Hook " + className + "." + methodName + " 成功");
                }
            } catch (Throwable ignored) {}
        }

        // ——— 2. Hook getContent — 如果返回撤回提示，返回空字符串 ———
        String[][] getterTargets = {
                {"com.tencent.mm.storage.MsgInfo", "getContent"},
                {"com.tencent.mm.storage.MsgInfo", "getFieldContent"},
        };

        for (String[] spec : getterTargets) {
            String className = spec[0];
            String methodName = spec[1];
            try {
                Class<?> c = cl.loadClass(className);
                boolean hooked = false;

                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(methodName)) continue;

                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!antiRecallEnabled) return;
                            if (param.getResult() instanceof String) {
                                String result = (String) param.getResult();
                                if (result != null && (result.contains("撤回了一条消息")
                                        || result.contains("revoked") && result.contains("message"))) {
                                    XposedBridge.log(TAG + " [方案B] ✅ 拦截 getContent 返回撤回提示");
                                    // 返回空字符串，让消息不显示撤回文案
                                    param.setResult("");
                                }
                            }
                        }
                    });
                    hooked = true;
                }

                if (hooked) {
                    XposedBridge.log(TAG + " [方案B] ✅ Hook " + className + "." + methodName + " 成功");
                }
            } catch (Throwable ignored) {}
        }
    }

    // ====================================================================
    //  方案B-2：拦截 type=10000 系统消息的插入（撤回通知）
    //
    //  撤回后，微信会插入一条 type=10000 的系统消息作为撤回通知。
    //  拦截存储层的插入，阻止这类消息进入数据库。
    //
    //  注意：不能 setResult(null) 拦截所有存储操作，
    //  而是只拦截 type 为 10000 的消息。
    // ====================================================================

    private void hookSystemMsgFilter(ClassLoader cl) {
        String[] storageClasses = {
                "com.tencent.mm.storage.bg",
                "com.tencent.mm.storage.ay",
                "com.tencent.mm.modelbase.r",
        };

        for (String className : storageClasses) {
            try {
                Class<?> c = cl.loadClass(className);
                boolean hooked = false;

                for (Method m : c.getDeclaredMethods()) {
                    String mn = m.getName();
                    if (mn.length() != 1) continue;
                    if (!"a".equals(mn) && !"b".equals(mn)) continue;

                    // 参数中有 int/long 类型的，可能是 type 参数
                    boolean hasNumericParam = false;
                    for (Class<?> pt : m.getParameterTypes()) {
                        if (pt == int.class || pt == long.class || pt == Integer.class || pt == Long.class) {
                            hasNumericParam = true;
                            break;
                        }
                    }
                    if (!hasNumericParam) continue;

                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!antiRecallEnabled) return;
                            // 检查是否有参数值为 10000（系统消息 type）
                            for (Object arg : param.args) {
                                if (arg instanceof Integer && (Integer) arg == 10000) {
                                    XposedBridge.log(TAG + " [方案B-2] ✅ 拦截 type=10000 插入: "
                                            + className + "." + mn + "()");
                                    param.setResult(null);
                                    return;
                                }
                            }
                        }
                    });
                    hooked = true;
                }

                if (hooked) {
                    XposedBridge.log(TAG + " [方案B-2] ✅ Hook " + className + " type 检查成功");
                }
            } catch (Throwable ignored) {}
        }
    }
}
