# 摇一摇作弊 Xposed 模块（系统层传感器版）

基于 LSPosed API 的 Xposed 模块，通过 Hook Android 系统层加速度传感器服务，
让微信内所有获取加速度数据的地方（包括任意 WebView 网页、原生组件）都收到伪造的摇一摇数据。

## 功能

- **系统层传感器修改**：Hook `SensorManager.registerListener`，收集所有加速度传感器监听器
- **全局生效**：不针对特定网页，微信内所有摇一摇功能都会被影响
- **频率可调**：通过设置界面调整每秒注入次数（1~50次/秒）
- **启用/禁用开关**：不需要时关闭模块，不影响正常使用

## 工作原理

1. 微信进程加载后，模块 Hook `android.hardware.SensorManager.registerListener` 的各种重载
2. 收集所有注册了 `TYPE_ACCELEROMETER` 的 `SensorEventListener`
3. 按设定频率定时构造伪造的 `SensorEvent` 并调用这些监听器
4. 伪造数据在相邻两次注入间大幅交替变化（+30~50 与 -15~25），远超任何摇一摇阈值
5. 微信内任意网页或组件接收到的加速度数据都被污染，从而实现自动计数

## 项目结构

```
LSPosed/
├── settings.gradle              # Gradle 配置
├── build.gradle                 # 项目级构建
├── gradle.properties
├── gradle/wrapper/
│   └── gradle-wrapper.properties
├── app/
│   ├── build.gradle             # 模块构建配置
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml  # 清单文件（含 Xposed 声明）
│       ├── assets/
│       │   └── xposed_init      # Xposed 入口类声明
│       ├── java/com/shakecheat/
│       │   ├── MainHook.java    # Xposed Hook 核心逻辑（Hook SensorManager）
│       │   └── SettingsActivity.java  # 设置界面
│       └── res/
│           ├── layout/activity_settings.xml
│           ├── drawable/        # 图标和按钮样式
│           ├── values/          # 字符串、颜色、主题
│           └── xml/xposed_scope.xml  # LSPosed 作用域（微信）
```

## 构建方法

### 环境要求
- Android Studio (Hedgehog 2023.1 或更高)
- JDK 17
- Android SDK 34

### 步骤
1. 用 Android Studio 打开 `LSPosed` 目录
2. 等待 Gradle 同步完成（首次可能自动下载 Gradle wrapper）
3. Build → Build APK
4. 生成的 APK 在 `app/build/outputs/apk/debug/`

## 安装使用

1. 安装 APK 到手机
2. 打开模块 App，设置每秒次数，保存
3. 打开 LSPosed Manager
4. 在模块列表中启用「摇一摇作弊」
5. 在作用域中勾选微信 (com.tencent.mm)
6. 强制停止微信后重新打开
7. 微信扫码打开任意摇一摇网页
8. 点击「开始挑战」，自动开始计数

## 调试

可以通过 `adb logcat -s "[ShakeCheat]"` 查看模块日志，检查：
- 模块是否成功加载到微信进程
- 是否成功 Hook SensorManager
- 是否发现加速度监听器
- 是否成功注入伪造数据

## 技术参数

| 参数 | 值 |
|------|-----|
| minSdk | 24 (Android 7.0) |
| targetSdk | 34 (Android 14) |
| Xposed API | 82 |
| 目标应用 | com.tencent.mm (微信) |
| Hook 目标 | SensorManager.registerListener / unregisterListener |
| 传感器类型 | TYPE_ACCELEROMETER (加速度传感器) |
| 频率范围 | 1 ~ 50 次/秒 |
