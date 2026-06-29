# 微信自主摇一摇 (ShakeCheat)

> 微信内加速度传感器 Hook Xposed 模块 + 摇一摇计数网页  
> 让摇一摇挑战自动化，无需真正摇晃手机

[![LSPosed](https://img.shields.io/badge/LSPosed-Xposed-blueviolet)](https://github.com/LSPosed/LSPosed)
[![Android](https://img.shields.io/badge/Android-7.0%2B-brightgreen)](https://developer.android.com)

---

## 项目概览

一个完整的摇一摇自动化工具链，包含两大组件：

| 组件 | 说明 |
|---|---|
| **Xposed 模块** (LSPosed/) | 基于 LSPosed 框架，Hook 系统层加速度传感器，向微信进程注入伪造的摇一摇数据 |
| **前端网页** (index.html) | 手机端摇一摇计数游戏，通过 DeviceMotionEvent API 检测加速度变化 |

## 核心原理

- **方案一**：Hook SensorManager.registerListener 4 个公开重载
- **方案二**：Hook SystemSensorManager.registerListenerImpl（5/6/7 参数变体）
- **方案三**：Hook Chromium WebView 传感器路径（PlatformSensor.onSensorChanged）
- **双保险机制**：注入循环 + 真实数据拦截

## 快速开始

### 前置条件
- Android 手机已 Root
- 已安装 LSPosed
- 微信 (com.tencent.mm)

### 安装步骤
1. 下载最新 APK → [Releases](https://github.com/ynhololens/shakecheatYYY/releases)
2. 安装 APK 到手机
3. 打开 xab 微信自主摇一摇 xbb App，设置速度，保存
4. LSPosed Manager → 启用模块 → 勾选微信
5. 强制停止微信 → 重新打开
6. 微信内打开摇一摇网页 → 开始挑战

## 构建

```bash
cd LSPosed
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 调试日志

```bash
adb logcat -s "[ShakeCheat]"
```

## 版本历史

| 版本 | 说明 |
|---|---|
| v3.0 | 更名为「微信自主摇一摇」，清理日志 |
| v2.0 | 全面重写，三层 Hook 策略 + 悬浮窗控制 |
| v1.x | 初始版本 |

## 技术参数

| 参数 | 值 |
|---|---|
| minSdk | 24 (Android 7.0) |
| targetSdk | 33 (Android 13) |
| Xposed API | 82 |
| 目标应用 | com.tencent.mm (微信) |
| 传感器类型 | TYPE_ACCELEROMETER |
| 注入频率 | 1 ~ 50 次/秒 |

## 声明

本项目仅供学习研究使用，请勿用于违规用途。
