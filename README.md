<div align="center">

# 笔记整理器 · NoteCraft

**按下录音键，剩下的交给它。**

说话 → 文字 → 结构化笔记 → Markdown 文件。开会、上课、走路时的灵光一闪，录完就不用再管了。

纯原生安卓实现：Kotlin + Jetpack Compose，不依赖任何第三方 UI 框架。

[![Android](https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white)](https://www.android.com/)
[![Downloads](https://img.shields.io/github/downloads/YesNewBeeBar/Android-NoteCraft/total?style=flat-square&color=blue)](https://github.com/YesNewBeeBar/Android-NoteCraft/releases)

</div>

---

## 这是什么

一个把「说话」变成「笔记」的安卓 App。

按下录音，它会替你走完这一条链：

```
录音 ──► 转写成文字 ──► AI 整理成结构化笔记 ──► 导出 Markdown
                            标题 / 摘要 / 要点 / 待办 / 标签
```

产物是一份带 YAML front matter 的 `.md`，丢进 Obsidian、Logseq 能被认出属性，而不是一坨死文本。

**它能做什么**

- **熄屏也能录** —— 前台服务，切后台、锁屏、关屏幕都继续录，通知栏有暂停 / 结束按钮
- **实时上屏** —— 说话的时候字就在屏幕上滚（走云端转写，可开关）
- **AI 整理** —— 自动分出标题、摘要、要点、待办、标签和正文
- **提示词模板** —— 会议纪要 / 课堂笔记 / 灵感速记 / 待办提取，一键切换
- **两种录音格式** —— m4a 省空间（1 小时约 14MB）/ WAV 高兼容（支持实时上屏、中途静音、波形图）
- **两种转写引擎** —— 云端 API 或系统离线识别，后者音频不出手机
- **导入已有音频** —— 从文件选，或从别的 App 直接分享进来
- **导出到你自己选的文件夹** —— 走系统目录授权，文件名模板可自定义

---

## 下载

去 **[Releases 页面](https://github.com/YesNewBeeBar/Android-NoteCraft/releases)** 下载最新的 `.apk`，在手机上点开安装。

- 需要 **Android 8.0（API 26）或更高**
- 首次安装需要在系统设置里允许「安装未知来源的应用」

---

## 怎么用

### 第一步：配好两个 API

打开 App → **设置**。这是唯一需要动手的地方，配一次就不用再管。

> ⚠️ **必须配两家。** DeepSeek 只能处理文字，**没有音频转写接口** —— 所以「整理」和「转写」得各找一家。

**① 笔记整理 —— DeepSeek**

| 字段 | 填什么 |
|---|---|
| API Key | 在 [platform.deepseek.com](https://platform.deepseek.com) 申请的 `sk-…` |
| BaseURL | `https://api.deepseek.com`（默认） |
| 模型 | `deepseek-chat`；想要更强推理用 `deepseek-reasoner` |

**② 音频转写 —— 任意 OpenAI 兼容服务**

默认已经填好**硅基流动**（国内直连、便宜、中文识别准），不换也行：

| 字段 | 默认值 |
|---|---|
| BaseURL | `https://api.siliconflow.cn/v1` |
| 模型 | `FunAudioLLM/SenseVoiceSmall` |

想换别家，改这两项就够：

| 供应商 | BaseURL | 模型 |
|---|---|---|
| OpenAI 官方 | `https://api.openai.com/v1` | `whisper-1` |
| Groq | `https://api.groq.com/openai/v1` | `whisper-large-v3` |
| 本地 vLLM / faster-whisper | 你自己的地址 | 你部署的模型名 |

> BaseURL 填到 `/v1` 为止，**不要**自己再带上 `/audio/transcriptions`。
>
> ⚠️ 硅基流动对单个文件限制 **≤ 1 小时、≤ 50MB**。1 小时 m4a 约 14MB 没问题，但 1 小时 WAV 约 115MB **会被直接拒收** —— 录长会议别选 WAV。

API Key 用 Android Keystore 加密后存在本机，**不参与云备份**。设置页有「运行密钥存储自检」可以验证加解密链路。

### 第二步：录音

1. 打开 App 就是录音页，**按下中间那个大圆按钮**开始
2. 录音时顶部有**红点**，通知栏会出现控制条 —— 这时候你可以锁屏、切后台、去干别的
3. 再按一下结束。**录完会自动开始转写和整理**，可以先去做别的事
4. 下面那排次要按钮是暂停 / 继续、静音、放弃（删掉这条）。故意放得离主按钮远一点，防误触

### 第三步：看和导出

- **笔记页**列出所有记录；点进去看详情：整理后的正文、要点、待办、标签，以及**未经 AI 处理的原始转写**（方便核对）
- 详情页右上角菜单可以单独重跑：重新转写 / 重新整理 / 全流程 / 重拟标题
- **导出 Markdown**：设置 → Markdown 存放位置 → 选一个文件夹，授权一次之后一直有效。会在你选的目录下再建一层子目录（默认 `NoteCraft`），避免把目录塞满散文件

导出的文件长这样：

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
```

文件名模板默认 `{date}-{time}-{title}`，可用占位符：`{date}` `{time}` `{datetime}` `{title}` `{id}`。

> **录音文件存在 App 私有目录，卸载 App 会跟着一起删掉。** 重要录音请先在详情页导出 —— 导出的 `.md` 会附上音频路径备注。

### 录音格式怎么选

| | m4a（默认） | WAV |
|---|---|---|
| 编码 | AAC-LC 32kbps 单声道 | PCM 16kHz 单声道 16bit |
| 每分钟 | ~0.24 MB | ~1.92 MB |
| 1 小时 | **~14 MB** | ~115 MB |
| 各家转写接口 | 都认 | 都认 |
| 实时上屏 | ❌ | ✅ |
| 录音中途静音 | ❌ | ✅ |
| 详情页波形图 | 不显示 | 显示 |

**实时上屏开着时会强制用 WAV**（切片需要原始 PCM）。界面会用徽标如实告诉你这次录的是哪种，不让你猜。

### 提示词：两套，别搞混

设置页右上角进入。这里有一个人容易混淆的点：

| | 转写提示词 | 整理提示词 |
|---|---|---|
| 给谁 | 转写模型（Whisper 一类） | DeepSeek 等文本模型 |
| 本质 | **术语线索**，不是命令 | **系统指令**，真正的提示词 |
| 写什么 | 这段录音里可能出现的专业词、人名、缩写 | 整理成什么样：分几段、突出什么、什么风格 |
| 长度 | 建议 50 字以内（有 token 上限，太长反而起反效果） | 几百字都行 |
| 写「请认真转写」有用吗 | **没用**，模型只当上下文参考 | 有用，但别写空话 |

整理提示词的优先级：**你手写的内容 > 选中的模板 > 内置默认**。

不论你写什么，App 都会自动附加 JSON 字段约定（标题 / 摘要 / 要点 / 待办 / 标签 / 正文），所以不必操心「模型能不能被程序解析」，写自然语言要求就行。

### 转写引擎二选一

| | 云端 API（默认） | 系统离线识别 |
|---|---|---|
| 音频是否出手机 | 会发送给服务商 | 不出手机 |
| 准确率 | 高 | 一般，长句 / 方言差 |
| 费用 | 按量计费 | 免费 |
| 能录下音频文件 | 能 | **不能**（要识别就得独占麦克风） |
| 能实时上屏 | 能 | 不能 |
| 需要联网 | 是 | 否（装了设备端离线包则完全离线） |

> 首次用「系统离线识别」前，建议先在手机系统设置里下载中文离线识别包 —— 部分机型默认没有，这时会退化成在线识别。

---

## 什么原理

### 主流程

```
录音.wav ──► [转写] ──► 原始文字 ──► [DeepSeek 整理] ──► 结构化 Markdown ──► [可选] 写入 .md
                                                            │
                                                  标题/摘要/要点/待办/标签
```

两个关键设计：

- **每一阶段完成都立刻写数据库。** 界面刷新、进程被杀、切页面，都不会丢进度。
- **录音结束后服务不立刻退出**，继续挂在前台把「转写 + 整理」跑完。否则用户一锁屏，系统就可能回收进程，转写跑到一半就没了。

### 为什么必须配两家 API

DeepSeek 只有 `chat/completions` 文本接口，**没有音频转写能力**。所以转写这一环对接的是事实标准 **OpenAI `/v1/audio/transcriptions`** 协议 —— 硅基流动、Groq、OpenAI 官方、本地 vLLM 都认这套，一套代码全覆盖，换供应商只要改 BaseURL + 模型名。

### 两个录音引擎

- **WAV 引擎**：`AudioRecord` 采原始 PCM，自己写 WAV 头。文件大，但**只有拿到 PCM 流才能按时间切片**，所以「实时上屏」和「录音中途静音」必须用它。
- **m4a 引擎**：`MediaRecorder` 直接录成 AAC。小 8 倍，但拿不到 PCM，切不了片，也静音不了。

两者对上层完全同构，界面只说结果，不让你关心底下是谁在干活。

### 实时上屏是怎么做的

把录音流按固定时长（默认 8 秒，可调 4～20 秒）切片，逐段上传云转写，再按顺序拼接显示。

- **只有云转写能做实时上屏**：系统离线识别必须独占麦克风，无法与录音并行
- 纯静音段会被自动跳过，省流量也省额度
- 关掉它之后，录音完全离线落盘，转写在结束后才开始

### 为什么没做成 webm

考虑过，但安卓上有三个坑，收益又不明显：

1. **`MediaMuxer` 不支持 WebM 输出**（只有 MP4/3GP/HEIF/OGG），要自己手写 Matroska/EBML 封装
2. WebM 里装的是 **Opus**，得走 `MediaCodec` 的软件编码器，**不是每台设备都有**
3. 部分国内转写服务商只收 mp3/wav/aac，**不收 webm**

压缩收益上：m4a 32kbps 是 0.24MB/分钟，webm+Opus 24kbps 约 0.18MB/分钟 —— 只差 25%，却要付出「自己写容器 + 赌设备编码器 + 赌服务商支持」的代价。

### 其他几个刻意的选择

- **存储走 SAF 目录授权，不用 `MANAGE_EXTERNAL_STORAGE`** —— 后者会被 Google Play 直接拒审，而且 SAF 在 Android 8～15 行为一致，不用写两套分支
- **自己写 Markdown 渲染器**（约 200 行）—— 需求只要「笔记读得舒服」，引 Markwon / compose-markdown 会带来 AndroidView 互操作和依赖链风险
- **不用 Hilt / Dagger** —— 手工依赖容器就够，少一个注解处理器就少一类「KSP 版本和 Kotlin 版本对不上」的编译错误
- **API Key 用 Android Keystore 的 AES/GCM 加密**存在本机，并且排除在云备份之外
- **不开 R8 混淆** —— 避免 Room / 序列化在混淆下出玄学问题

### 能导入哪些音频格式

wav / mp3 / m4a / aac / ogg / opus / flac / 3gp / amr / webm。

外部 wav 的头部如果是坏的（进程被杀留下的残头），会自动修复长度字段 —— 不修的话服务端会解出 0 秒。

---

<div align="center">

**[⬆ 回到顶部](#笔记整理器--notecraft)** · **[下载最新版](https://github.com/YesNewBeeBar/Android-NoteCraft/releases)** · **[反馈问题](https://github.com/YesNewBeeBar/Android-NoteCraft/issues)**

</div>
