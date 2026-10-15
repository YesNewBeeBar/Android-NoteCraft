# 笔记整理器（NoteCraft）

录音 → 转写 → AI 整理 → 导出 Markdown 的安卓笔记 App。
Kotlin + Jetpack Compose + 安卓原生 API，纯原生实现，不依赖任何第三方 UI 框架。

---

## 一、怎么编译

### 前提

- Android Studio **Ladybug (2024.2)** 或更新版本
- JDK 17（Android Studio 自带的 JBR 就够）
- Android SDK：**API 35**（编译用）+ API 26 以上设备

### 步骤

1. Android Studio → **File → Open**，选中本目录（含 `settings.gradle.kts` 的那一层），
   **不要**打开 `app/` 子目录。
2. 第一次打开时 Studio 会提示同步 Gradle。**同意下载 Gradle 8.9 与依赖**（需要联网，约几百 MB）。
3. 等 `Gradle sync` 变成绿色。
4. 连上手机（开发者选项 + USB 调试），点 ▶️ Run。
5. 或者只出安装包：**Build → Build Bundle(s)/APK(s) → Build APK(s)**，
   产物在 `app/build/outputs/apk/debug/app-debug.apk`。

### 关于 gradle-wrapper.jar

本工程**没有**打包 `gradle/wrapper/gradle-wrapper.jar`（二进制文件，不便随源码传输）。
两种解决办法，任选其一：

- **推荐**：直接用 Android Studio 打开，它检测到缺 wrapper 时会自动补齐并下载 Gradle。
- 或者命令行执行一次：`gradle wrapper --gradle-version 8.9`（需要本机已装 Gradle）。
- 或者从别的工程拷一个 `gradle-wrapper.jar` 到 `gradle/wrapper/` 目录。

`gradle-wrapper.properties` 已经写好，指向 Gradle 8.9。

### 依赖版本（都在 `gradle/libs.versions.toml` 里，想升级改一个文件即可）

| 组件 | 版本 |
|---|---|
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
| Compose BOM | 2024.10.01 |
| Room | 2.6.1 |
| OkHttp | 4.12.0 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |

---

## 二、先配置 API，不然录了也白录

打开 App → **设置**，填两处：

### 1. 笔记整理（DeepSeek）

| 字段 | 填什么 |
|---|---|
| API Key | 在 platform.deepseek.com 申请的 `sk-…` |
| BaseURL | `https://api.deepseek.com`（默认） |
| 模型 | `deepseek-chat`，想要更强推理可用 `deepseek-reasoner` |

### 2. 音频转写（**必须是另一家**）

> ⚠️ **DeepSeek 的 API 只能处理文本，没有音频转写接口。**
> 所以转写必须配置另一家，本项目对接的是事实标准 **OpenAI `/v1/audio/transcriptions`** 协议。

默认已经填好**硅基流动**（国内可直连、便宜、中文识别准）：

| 字段 | 默认值 |
|---|---|
| BaseURL | `https://api.siliconflow.cn/v1` |
| 模型 | `FunAudioLLM/SenseVoiceSmall` |

换其它供应商只要改这两项：

| 供应商 | BaseURL | 模型 |
|---|---|---|
| OpenAI 官方 | `https://api.openai.com/v1` | `whisper-1` |
| Groq | `https://api.groq.com/openai/v1` | `whisper-large-v3` |
| 本地 vLLM / faster-whisper | 你自己的地址 | 你部署的模型名 |

API Key 用 **Android Keystore 加密**后存在本机，且**不参与云备份**。
设置页有「运行密钥存储自检」可以验证加解密链路。

---

## 三、功能与用法

### 录音

- 大圆按钮开始／结束，**红点=正在录**。
- 前台服务 + 麦克风类型，**熄屏、切后台、锁屏都继续录**，通知栏有暂停／结束控制。
- 暂停、静音、放弃（删掉这条）都在下方次要按钮里，离主按钮远一点防误触。
- 录音格式**两种，设置里选**（见下节「录音格式怎么选」）。

### 录音格式怎么选

| | m4a（默认） | WAV |
|---|---|---|
| 编码 | AAC-LC 32kbps 单声道 | PCM 16kHz 单声道 16bit |
| 每分钟 | ~0.24 MB | ~1.92 MB |
| 1 小时 | **~14 MB** | ~115 MB |
| 各家转写接口 | 都认 | 都认 |
| 实时上屏 | ❌ 拿不到 PCM 流 | ✅ |
| 录音中途静音 | ❌ | ✅ |
| 详情页波形图 | 不显示（非 PCM） | 显示 |

**实时上屏开着时会强制用 WAV**，因为切片需要原始 PCM 流。界面会用一个徽标
（`m4a · 省空间` / `WAV · 高兼容`）如实告诉你这次录的是哪种，不让你猜。

### 为什么没做成 webm

考虑过，但安卓上有三个坑，收益又不明显：

1. **`MediaMuxer` 不支持 WebM 输出**（只有 MP4/3GP/HEIF/OGG），要自己手写 Matroska/EBML 封装；
2. WebM 里装的是 **Opus**，得走 `MediaCodec` 的软件编码器，**不是每台设备都有**；
3. 部分国内转写服务商只收 mp3/wav/aac，**不收 webm**。

压缩收益上：m4a 32kbps 是 0.24MB/分钟，webm+Opus 24kbps 约 0.18MB/分钟 —— 只差 25%，
却要付出「自己写容器 + 赌设备编码器 + 赌服务商支持」的代价。所以选 m4a。

### 实时上屏（可开关）

设置 → 音频转写 → **录音时实时上屏**。

- 原理：把录音流按固定时长（默认 8 秒，可调 4～20 秒）切片，逐段上传云转写，按顺序拼接显示。
- **只有云转写能做实时上屏**：系统离线识别必须独占麦克风，无法与录音并行。
- 切片里纯静音段会被自动跳过，省流量也省额度。
- 关掉它之后录音完全离线落盘，转写在结束后才开始。

### 提示词（两套，设置页右上角进入）

这里有个容易混淆的点：**「转写提示词」和「整理提示词」是两回事**。

| | 转写提示词 | 整理提示词 |
|---|---|---|
| 给谁 | 转写模型（Whisper 一类） | DeepSeek 等文本模型 |
| 本质 | **术语线索**，不是命令 | **系统指令**，真正的提示词 |
| 写什么 | 这段录音里可能出现的专业词、人名、缩写 | 整理成什么样：分几段、突出什么、什么风格 |
| 长度 | 建议 50 字以内（有 token 上限，太长会反效果） | 几百字都行 |
| 作用 | 减少专有名词听错 | 决定笔记的结构与质量 |
| 写「请认真转写」有用吗 | **没用**，模型只当上下文参考 | 有用，但别写空话 |

整理提示词的优先级：**你手写的内容 > 选中的模板 > 内置默认**。
不论写什么，App 都会自动附加 JSON 字段约定（标题/摘要/要点/待办/标签/正文），
所以你不必自己操心「模型能不能被程序解析」，写自然语言要求就行。

内置**模板库**（一键切换，不用每次重写）：

| 模板 | 适用场景 | 特点 |
|---|---|---|
| 会议纪要 | 会议、讨论 | 按议题分段，区分「已决定 / 待讨论 / 下一步」 |
| 课堂笔记 | 上课、讲座 | 按知识点组织，定义与公式单独标出 |
| 灵感速记 | 随手想法、头脑风暴 | 保留跳跃思路，不强行归并 |
| 待办提取 | 只想抽任务 | 重点抽 action_items，其余压缩成一句 |

### 转写与整理

录完自动执行（可在设置里关掉）：

```
录音.wav ──► [转写] ──► 原始文字 ──► [DeepSeek 整理] ──► 结构化 Markdown ──► [可选] 写入 .md
                                                              │
                                                    标题/摘要/要点/待办/标签
```

- 云转写失败时，若开着「自动回落」，会改用系统离线识别兜底
  （代价：离线识别要你对着手机再讲一遍，因为它需要实时麦克风）。
- DeepSeek 未配置时**不算失败**：转写结果会保留，状态标成「待处理」。
- 详情页右上角菜单可以单独重跑：重新转写 / 重新整理 / 全流程 / 重拟标题。

### 转写引擎二选一

| | 云端 API（默认） | 系统离线识别 |
|---|---|---|
| 音频是否出手机 | 会发送给服务商 | 不出手机 |
| 准确率 | 高 | 一般，长句/方言差 |
| 费用 | 按量计费 | 免费 |
| 能录下音频文件 | 能 | **不能**（要识别就得独占麦克风） |
| 能实时上屏 | 能 | 不能 |
| 需要联网 | 是 | 否（设备端离线包则完全离线） |

### 导入已有音频

- 笔记页右下角「导入音频」，或从**别的 App 分享音频到本 App**（已注册 `ACTION_SEND`）。
- 支持 wav / mp3 / m4a / aac / ogg / opus / flac / 3gp / amr / webm。
- 外部 wav 头部若是坏的（进程被杀留下的残头），会自动修复长度字段，否则服务端会解出 0 秒。

### 导出 Markdown（选择存放位置）

这条需求**没有**用 `MANAGE_EXTERNAL_STORAGE`（「所有文件访问」权限），而是用系统的
**目录授权（SAF）**：

- 设置 → Markdown 存放位置 → **选择文件夹**，授权一次之后一直有效。
- 也可以在详情页菜单里「另存到其他位置…」，选完会问你要不要**设为默认**。
- 会在你选的目录下再建一层子目录（默认 `NoteCraft`），避免把目录塞满散文件。
- 文件名模板默认 `{date}-{time}-{title}`，可用占位符：
  `{date}` `{time}` `{datetime}` `{title}` `{id}`。

导出的 `.md` 长这样：

```markdown
---
title: "周会纪要"
created: 2026-04-05 14:30:12
duration: 12:35
asr: 云端 · FunAudioLLM/SenseVoiceSmall
model: deepseek-chat
tags: ["项目", "排期"]
---

# 周会纪要

> 一句话摘要……

## 要点
- ……

## 待办
- [ ] ……

## 正文
（整理后的结构化内容）

---

## 原始转写
> 未经 AI 整理的原文，保留下来便于核对。
```

顶部是 YAML front matter，所以这份文件丢进 Obsidian / Logseq 也能被识别出属性。

---

## 四、工程结构

```
app/src/main/java/com/dsh/notes/
├── NoteCraftApp.kt            手工依赖容器（刻意不用 Hilt，少一类版本坑）
├── MainActivity.kt            单 Activity，处理权限与「分享音频」入口
├── ai/                        AI 对接
│   ├── OpenAiCompatibleAsr.kt   云转写（OpenAI 兼容 multipart 协议）
│   ├── DeepSeekClient.kt       chat/completions + JSON 输出模式
│   ├── PromptBuilder.kt        两套提示词 + 模板库 + 优先级解析
│   ├── AiTextCodec.kt          宽容的 JSON 树解析
│   └── AiClients.kt            两个 OkHttp 客户端（超时策略不同）
├── audio/
│   ├── AudioSink.kt            录音引擎接口（两个实现同构）
│   ├── WavAudioRecorder.kt     PCM 引擎：AudioRecord + WavWriter
│   ├── M4aAudioRecorder.kt     压缩引擎：MediaRecorder → m4a/AAC
│   ├── WavWriter.kt            WAV 头部与落盘
│   ├── LiveCaptioner.kt        实时上屏切片 + 串行队列
│   ├── LocalTranscriber.kt     系统离线识别（设备端优先）
│   ├── RecordingService.kt     前台服务：录音 + 结束后继续跑处理
│   ├── Waveform.kt             波形抽取（详情页声波图）
│   └── BootReceiver.kt         重启后清理「僵尸录音状态」
├── data/
│   ├── Note.kt                 Room 实体（一条笔记的完整生命周期）
│   ├── NoteDao.kt / NotesDatabase.kt / NoteRepository.kt
│   ├── SettingsStore.kt        DataStore 设置项
│   └── CryptoUtils.kt          Keystore AES/GCM 加密 API Key
├── pipeline/NotePipeline.kt    转写 → 整理 → 导出，逐阶段写库
├── export/                     Markdown 生成、文件名模板、SAF 写入
├── ui/                         Compose 界面（录音/列表/详情/设置）
└── util/                       时间格式化、权限判定、剪贴板
```

### 几个刻意的设计选择

- **不用 Hilt / Dagger**：手工依赖容器就够，少一个注解处理器就少一类
  「KSP 版本和 Kotlin 版本对不上」的编译错误。
- **两个录音引擎，按需选**：要实时上屏就必须拿 PCM（AudioRecord+WavWriter），
  不要上屏就用 MediaRecorder 录 m4a（小 8 倍）。两者对上层同构，界面只说结果。
- **不用 webm**：MediaMuxer 不支持 WebM 输出，Opus 编码器不是每台机器都有，
  还有服务商不收；收益只有 25%，不值这个风险。
- **录音结束后服务不立刻退出**：继续挂在前台把「转写 + 整理」跑完。
  否则用户一锁屏，系统就可能回收进程，转写跑到一半就没了。
- **每阶段都写库**：界面刷新、进程被杀、切页都不会丢进度。
- **自己写 Markdown 渲染器**（约 200 行）：需求只要「笔记读得舒服」，
  引 Markwon/compose-markdown 会带来 AndroidView 互操作和依赖链风险。
- **存储走 SAF**：`MANAGE_EXTERNAL_STORAGE` 会被 Google Play 直接拒审，
  而且对自用也没必要；SAF 在 Android 8～15 行为一致，不用写两套分支。

---

## 五、已知限制（都说清楚，别踩坑）

1. **DeepSeek 没有音频接口**，转写必须另配一家（见第二节）。
2. **离线识别模式录不到音频文件**：SpeechRecognizer 要独占麦克风，
   所以选它时录完只有文字。想要音频就用云端转写。
3. **实时上屏只有云转写能做**，本地识别无法与录音并行。
4. **录音文件存在 App 私有目录**，卸载 App 会一起删掉。
   重要录音请先在详情页导出（导出的 .md 会附上音频路径备注）。
5. **长录音的体积与流量**：1 小时 m4a 约 14MB、WAV 约 115MB。
   接口超时设为 300 秒，够传完这两种格式的一小时音频。
6. **通知权限被拒**：Android 13+ 若拒绝通知权限，前台服务仍在跑，
   但通知栏看不到控制按钮，录音页之外的暂停/结束会不方便。
7. 首次用「系统离线识别」前，建议在手机系统设置里下载中文离线识别包
   （部分机型默认没有，此时会退化成在线识别）。
8. **m4a 格式下不能录音中途静音**：MediaRecorder 在采样层拿不到数据，
   界面会把静音按钮置灰并提示。想要静音就选 WAV 格式。
9. **m4a 不支持实时上屏**：切片需要原始 PCM。开着实时上屏时会被强制成 WAV，
   界面用徽标明确标出当前格式。
10. 详情页的波形图只对 WAV 显示；m4a 是压缩流，画不了 PCM 波形（播放功能正常）。
11. 工程未做 R8 混淆（自用项目，避免 Room / 序列化出玄学问题）；
   真要上架再开 `isMinifyEnabled` 并补 proguard 规则。

---

## 六、自检脚本

`tools/check.js` 是一个不依赖 JDK 的静态自检脚本（Node 运行），检查：
括号平衡、未闭合字符串、代码里的中文标点、import 是否存在、
是否漏 import、完全限定名、资源引用、manifest 与代码是否对得上。

```bash
node tools/check.js
```

在本工程上当前输出为 **0 错误 0 警告**。它不是编译器，
但能在没有 Android SDK 的环境里先挡住大部分低级错误。
