# hyperos-callrec-overlay

HyperOS / MIUI 上「通话接通 → 自动浮出录音机小窗 → 自动展开实时转文字」的 LSPosed 模块。

电话、以及**微信 / QQ / 企业微信 / 钉钉 / 飞书 / 腾讯会议**等三方语音通话都支持：
通话真正开始录音后约 0.8–1.2 秒，屏幕上浮出一个约 583×1010 的小窗，显示
`录音中 00:xx / 简体中文 / 说话人 1 + 实时字幕`，位置在通话页上半屏，不挡拨号键盘，无需任何手动操作。

An LSPosed module that pops up MIUI Sound Recorder's live-transcription mini window
automatically when a phone call **or a third-party VoIP call (WeChat, QQ, WeCom, DingTalk,
Feishu, Tencent Meeting…)** starts recording.

## 实测环境

| 项 | 值 |
|---|---|
| 机型 | REDMI K90 Ultra（warsaw / M332BF，SM8750，1280×2772） |
| 系统 | HyperOS 4 `OS4.0.6.0.XHPCNXM`，Android 17（SDK 37） |
| Root / 框架 | KernelSU + Zygisk + LSPosed 2.2.1 |
| 录音机 | `com.android.soundrecorder` 8.2.0 |

只在上面这一台机器上验证过。MIUI 的内部类名/方法名是**版本相关**的，OTA 之后可能需要重新对位（见下文「适配新版本」）。

## 原理（两条触发链）

| 场景 | 触发点 | 拉起窗口时的 `extra_from_package_name` | 录音落盘 |
|---|---|---|---|
| 电话（来电 / 去电） | `com.android.incallui` → `RecorderNotificationMgr#setRecordIconVisibility(true)` | `com.android.incallui` | `MIUI/sound_recorder/call_rec/*.mp3` |
| 微信等三方通话 | `com.miui.audiomonitor` → `MiuiVoipRecorderService#createUIManger(int, String pkg)` | `com.miui.audiomonitor` | `MIUI/sound_recorder/app_rec/*.aac` |

三方通话的录音并不是 InCallUI 做的，而是 MIUI 的「应用通话录音」服务 `com.miui.audiomonitor`
（system uid）通过 VoIP 上下行采集写入的。所以模块作用域必须包含它。

`createUIManger` 是私有方法，签名 `(ILjava/lang/String;)V`，**只在系统已经判定「这通通话可以录音」之后**
才被调用（响铃阶段不会），因此比 `onStartCommand` 更可靠。命中后延迟 800 ms 再拉起窗口，
否则录音尚未真正开始，录音机会走 `recording is already finished, skip` 分支把自己关掉。

录音机侧的证据：`com.android.soundrecorder.RecorderActivityProxy#onCreate` 里有
`fromPackageName.equals("com.miui.audiomonitor") → AppTask.setExcludeFromRecents(true)` 的特判，
说明这个入口本来就是给应用通话录音准备的，同一套 Intent 结构可以直接复用。

### 窗口为什么用「画布 + freeformScale」

MIUI 的小窗是「按整屏密度渲染 → 整体按比例缩小」。给一个小的 `bounds` 会让 app 按小 dp 重排
（字大、行少、面板挤爆，甚至 `DequeueBuffer time out` 白屏）。正确做法是给接近全屏的画布，
再用反射出来的 `getActivityOptionsInjector().setFreeformScale(0.47f)` + `setNormalFreeForm(false)` 缩放：

```
显示左上角 = (L, T)          ← 位置不参与缩放
显示宽     = (R - L) × scale
显示高     = (B - T) × scale
```

默认 `(20,110,1240,2259)` × `0.47` ≈ `583×1010`。要更矮就减 `B`，别继续抬 `T`（会被状态栏压住）。

### 转写面板

录音页默认只有计时器；左下角 `contentDescription == "显示文本"`（真实 id `btn_ai_mode_toggle`）
既进 AI 模式又开始识别，`performClick()` 即可。**时机**很关键：点太早（<600 ms）ASR 音频管线还没就绪，
`onRecognizeStart` 之后永远没有结果。默认延迟 1000 ms（电话）/ 1800 ms（三方通话），可运行时调。

## 前置：这些 MIUI 开关必须开

```bash
# 电话自动录音
adb shell settings get system button_auto_record_call          # 期望 1

# 三方通话录音 + AI 纪要（在 录音机 → 应用通话录音 页面里）
adb shell am start -n com.miui.audiomonitor/.voiprecord.view.VoipRecordSettingsActivity2
#   页面里三个开关都要开着：AI录音纪要 / 自动开启"AI录音纪要" / 微信

# 实时转写开关（录音机 → 设置 → AI 转写）
#   /data/data/com.android.soundrecorder/shared_prefs/com.android.soundrecorder_preferences.xml
#   pref_key_ai_recognize_enable / pref_key_ai_recognize_call = true
```

## 构建

不需要 Gradle，只需要 JDK 8+、Android SDK（build-tools + `platforms/android-35`）：

```bash
./build.sh          # → build/CallRecAuto.apk
ANDROID_SDK=/opt/android-sdk BUILD_TOOLS=/opt/android-sdk/build-tools/35.0.0 ./build.sh
```

链路：`javac`（自写 `de.robv.*` 桩类，运行时由 LSPosed 提供）→ `d8` → `aapt2 link` →
塞 `classes.dex` + `assets/xposed_init` → `zipalign` → `apksigner`（`debug.keystore`，口令 `android`，
**仅供自用/测试，别拿它上架**）。

## 部署

LSPosed 没有 CLI 开关，启动脚本只能改数据库。`deploy.sh` 把「装机 → 改 `modules_config.db` → 软重启 zygote」串起来了：

```bash
adb connect <手机IP>:<无线调试端口>
./deploy.sh <手机IP>:<端口>
```

它会写入三张表：`modules.apk_path`（**每次 `install -r` 都会变，忘了同步模块会静默不 hook**）、
`modules_state.enabled=1`、`scope`（`com.android.incallui` / `com.android.soundrecorder` / `com.miui.audiomonitor`）。

软重启会断 ADB 约 60–90 秒。起来后自检：

```bash
su -c 'logcat -d | grep CallRecAuto'
# 期望（各进程加载时各一条）：
#   CallRecAuto: InCallUI hook installed
#   CallRecAuto: SoundRecorder hook installed
#   CallRecAuto: audiomonitor hook installed (createUIManger)
# 通话中：
#   CallRecAuto: recorder window launched from=com.miui.audiomonitor
#   CallRecAuto: clicked 显示文本 id=btn_ai_mode_toggle (try 3, +1633ms)
```

手动装 APK 时也可以用 LSPosed 管理器开启，作用域三个包都要勾上。

## 可调参数（不用重打包）

```bash
adb shell su -c 'settings put global callrec_click_delay_ms 1200'        # 电话：转写按钮延迟
adb shell su -c 'settings put global callrec_voip_click_delay_ms 1800'   # 三方通话
```

判据：`onRecognizeStart` 之后长时间没有 `onRecognizeResultUpdate` ⇒ 点太早，往上加 200–300 ms；
总丢第一句同样往上加。

想改窗口尺寸/位置，改 `src/com/nil/callrec/Hook.java` 顶部的 `L, T, R, B, FREEFORM_SCALE` 后重新 `./deploy.sh`。

## 适配新版本

OTA 后如果模块静默失效（LSPosed 里 enabled 但没有 hook 日志），按顺序核对：

1. `RecorderNotificationMgr#setRecordIconVisibility(boolean)` 是否还在
2. `MiuiVoipRecorderService#createUIManger(int, String)` 是否还在（名字/签名可能变）
3. 三方 App 包名白名单 `VOIP_APPS`
4. 录音机：`SoundRecorder#onCreate/onNewIntent`、`显示文本`/`隐藏文本框` 的 contentDescription、
   `com.xiaomi.xms.ai.recorder.AIRecorderManager#isRecording()`

## 另一台机型实测（Redmi Note 12 Turbo / marble）

- 机型 `23049RAD8C`，HyperOS 4 `OS4.0.5.0.XPCCNXM`，Android 17，1080×2400，录音机 8.1.6
- 模块加载正常：作用域三个包都能 hook（`audiomonitor hook installed (createUIManger)` / `SoundRecorder hook installed`）
- 窗口几何是**按屏幕比例算**的，同一份 APK 不用改数值：
  `canvas = (0.0156W, 0.0397H) → (W-0.0312W, 0.0397H + 0.775H)`，`scale=0.47`
  → K90（1280×2772）得到 `(20,110,1240,2259)` 显示约 583×1010；
  N12T（1080×2400）得到 `(17,95,1046,1955)` 显示约 484×874，占屏比例一致
- 想整体放大/缩小：`settings put global callrec_freeform_scale 0.52`（0.05–1，默认 0.47）
- 坑：这台 ROM 的 LSPosed **不往 logcat 写日志**，hook 是否加载要看
  `su -c 'cat /data/adb/lspd/log/modules_*.log'`

## 已知限制

- 与 MIUI 内部实现强耦合，**系统升级后可能失效**，需要重新对位
- 实时字幕依赖联网 ASR，网络差时表现为「不出字」而不是报错
- 通话结束后小窗会停在「录音已完成」，需手动关闭（与原生行为一致）
- 只在 HyperOS 4 / 上面那台机器上验证；其他 MIUI / HyperOS 版本未测
- 需要 root（KernelSU/Magisk）+ LSPosed；无 root 请走无障碍方案，本项目不涉及

## License

MIT
