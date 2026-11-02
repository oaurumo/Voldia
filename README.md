# Voldia

中文 | [English](README_EN.md)

轻点悬浮球，随时呼出系统音量面板。

Voldia 是一个轻量的 Android 悬浮音量控制工具：在屏幕上常驻一个可拖动的悬浮球，单击呼出系统音量面板，长按展开音量卡片直接拖动调节媒体音量；卡片与主界面还提供基于 Shizuku 的关机 / 重启功能。

![minSdk](https://img.shields.io/badge/minSdk-23-blue)
![targetSdk](https://img.shields.io/badge/targetSdk-37-blue)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9-purple)

## 功能特性

- **悬浮球**
  - 单击：呼出系统音量面板
  - 长按：展开 / 收起音量卡片
  - 拖动：调整位置，松手自动吸附屏幕边缘
- **音量卡片**
  - 拖动滑块直接调节媒体音量，实时显示百分比
  - 一键跳转系统音量面板
  - 可拖动卡片标题栏移动位置，5 秒无操作自动收起
  - 关机 / 重启按钮（需 Shizuku 授权，带防误触确认对话框）
- **外观自定义**：悬浮球尺寸（dp）、透明度（%）、卡片宽度（%），实时生效并持久保存
- **快捷设置磁贴**：在系统通知栏快捷设置中添加「Voldia 音量」（Android 7.0+）
- **前台服务保活**：常驻通知栏，附带「音量卡片」「停止」快捷操作

## 系统要求

- Android 6.0（API 23）～ Android 17（API 37）
- 需要授予「显示在其他应用上层」（悬浮窗）权限
- 关机 / 重启功能需要安装并启动 [Shizuku](https://shizuku.rikka.app/)（ADB 或 Root 方式启动均可），并在 Shizuku 中授权 Voldia

> 说明：Shizuku 库本身声明 minSdk 24，本项目通过 `tools:overrideLibrary` 覆盖以兼容 API 23，但 Shizuku 相关功能在 API 23 设备上不可用，其余功能不受影响。

## 使用方式

1. 打开 App，点击「去授权」授予悬浮窗权限
2. 点击「启动悬浮球」，悬浮球出现在屏幕边缘
3. 单击悬浮球呼出系统音量面板；长按展开音量卡片调节媒体音量
4. （可选）启动 Shizuku 服务后，可在主界面或音量卡片中使用关机 / 重启
5. 在「悬浮球外观」区域调节悬浮球尺寸、透明度与卡片宽度

## 构建

```bash
git clone <repo-url>
cd Voldia
./gradlew assembleDebug
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

## 项目结构

```
app/src/main/java/com/oaurumo/voldia/
├── MainActivity.kt          # 主界面：权限、服务开关、外观设置、关机/重启
├── FloatWindowService.kt    # 前台服务：悬浮球、音量卡片、确认对话框
├── VolumeTileService.kt     # 快捷设置磁贴（Android 7.0+）
├── PowerController.kt       # Shizuku 授权与 shell 命令执行（svc power shutdown/reboot）
└── BallPrefs.kt             # 外观参数持久化（SharedPreferences）
```

## 权限说明

| 权限 | 用途 |
|------|------|
| `SYSTEM_ALERT_WINDOW` | 显示悬浮球与音量卡片 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | 保持悬浮球后台运行 |
| `MODIFY_AUDIO_SETTINGS` | 调节媒体音量 |
| `POST_NOTIFICATIONS` | Android 13+ 前台服务通知 |

## 技术要点

- Kotlin + ViewBinding + Material3
- 悬浮窗基于 `WindowManager`（`TYPE_APPLICATION_OVERLAY`）
- Shizuku API 13.1.5，`Shizuku.newProcess` 为私有方法，通过反射调用执行 shell 命令
- 关机 / 重启经确认对话框后执行 `svc power shutdown` / `svc power reboot`
