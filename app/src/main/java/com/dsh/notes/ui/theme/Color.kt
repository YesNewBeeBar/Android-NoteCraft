package com.dsh.notes.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 配色思路。
 *
 * 浅色：米白纸感背景（不是纯白，长时间看不刺眼）+ 墨绿主色 + 琥珀强调。
 * 深色：**不用纯黑**（#000 上的白字对比过强、边缘发糊），
 *       用带一点绿调的深灰，层级靠 surface 的明度差拉开。
 *
 * 所有前景/背景组合都按「正文至少 4.5:1 对比度」挑过一遍，
 * 户外强光下也能看清——这是笔记类 App 的真实使用场景（通勤、教室、走廊）。
 */

// ---------------- 浅色 ----------------

/** 主色：墨绿。用于按钮、选中态、关键文字 */
val TealLightPrimary = Color(0xFF16624F)
val TealLightOnPrimary = Color(0xFFFFFFFF)
val TealLightContainer = Color(0xFFB6EBD8)
val TealLightOnContainer = Color(0xFF00281E)

/** 次色：琥珀。用于待办、录音强调、需要注意的标记 */
val AmberLightSecondary = Color(0xFF7A5900)
val AmberLightOnSecondary = Color(0xFFFFFFFF)
val AmberLightContainer = Color(0xFFFFDEA6)
val AmberLightOnContainer = Color(0xFF261A00)

/** 第三色：偏蓝的灰绿，用于「处理中」这类中性状态 */
val SlateLightTertiary = Color(0xFF3A6560)
val SlateLightOnTertiary = Color(0xFFFFFFFF)
val SlateLightContainer = Color(0xFFBCEBE5)
val SlateLightOnContainer = Color(0xFF00201E)

/** 米白纸感背景：比纯白柔和，配合墨绿文字更耐看 */
val PaperLight = Color(0xFFF6F8F4)
val OnPaperLight = Color(0xFF171D1B)
/** 卡片/输入框表面：比背景再亮一点，形成轻微浮起感 */
val SurfaceLightHigh = Color(0xFFFBFFFC)
val SurfaceVariantLight = Color(0xFFDBE5E0)
val OnSurfaceVariantLight = Color(0xFF3F4946)
val OutlineLight = Color(0xFF6F7975)
val OutlineVariantLight = Color(0xFFBFC9C4)

val ErrorLight = Color(0xFFB3261E)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFF9DEDC)
val OnErrorContainerLight = Color(0xFF410E0B)

// ---------------- 深色 ----------------

val TealDarkPrimary = Color(0xFF9AD5C2)
val TealDarkOnPrimary = Color(0xFF00382C)
val TealDarkContainer = Color(0xFF00513F)
val TealDarkOnContainer = Color(0xFFB6EBD8)

val AmberDarkSecondary = Color(0xFFF5C24C)
val AmberDarkOnSecondary = Color(0xFF3F2E00)
val AmberDarkContainer = Color(0xFF5C4300)
val AmberDarkOnContainer = Color(0xFFFFDEA6)

val SlateDarkTertiary = Color(0xFFA0CFC9)
val SlateDarkOnTertiary = Color(0xFF003733)
val SlateDarkContainer = Color(0xFF1F4E4A)
val SlateDarkOnContainer = Color(0xFFBCEBE5)

/** 带绿调的深灰，不是纯黑：纯黑上白字会有光晕感 */
val InkDark = Color(0xFF101512)
val OnInkDark = Color(0xFFDFE4E0)
val SurfaceDarkHigh = Color(0xFF1A211D)
val SurfaceVariantDark = Color(0xFF3F4945)
val OnSurfaceVariantDark = Color(0xFFBFC9C4)
val OutlineDark = Color(0xFF89938F)
val OutlineVariantDark = Color(0xFF3F4945)

val ErrorDark = Color(0xFFF2B8B5)
val OnErrorDark = Color(0xFF601410)
val ErrorContainerDark = Color(0xFF8C1D18)
val OnErrorContainerDark = Color(0xFFF9DEDC)

// ---------------- 功能色 ----------------

/** 录音按钮：红得克制一点，和主色区分明显但不刺眼 */
val RecordRed = Color(0xFFC2382F)
val RecordRedDark = Color(0xFFFF8A80)

/** 录音按钮的外圈光晕 */
val RecordHalo = Color(0x1FC2382F)
val RecordHaloDark = Color(0x33FF8A80)
