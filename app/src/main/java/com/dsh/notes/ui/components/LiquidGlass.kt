package com.dsh.notes.ui.components

import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dsh.notes.R
import kotlinx.coroutines.delay

/**
 * 底栏背后画面的快照。
 *
 * 液态玻璃的关键不是「半透明」，而是**真的能看见背后那一层东西**：
 * 先让页面内容画满整屏（不再被 Scaffold 的 bottomBar 顶开），
 * 再把这块内容录进 GraphicsLayer 截成位图，最后交给 AGSL 着色器去
 * 模糊 / 折射 / 色散 / 打光。
 *
 * 截图是异步的，所以 [bitmap] 允许暂时为 null —— 调用方拿到 null 时
 * 退回原来的渐变，不会闪一下白底。
 */
class BackdropState internal constructor(val layer: GraphicsLayer) {

    /** 最近一次截到的画面，坐标是设备像素 */
    var bitmap: ImageBitmap? by mutableStateOf(null)
        internal set

    /** 快照的像素尺寸，用来给着色器夹取采样点 */
    var sizePx: IntSize by mutableStateOf(IntSize.Zero)
        internal set

    /** 快照相对屏幕的缩放比例（0.5 = 半分辨率截图，省内存也省采样） */
    var scale: Float by mutableStateOf(1f)
        internal set

    internal fun clear() {
        bitmap = null
        sizePx = IntSize.Zero
    }
}

@Composable
fun rememberBackdropState(): BackdropState {
    val layer = rememberGraphicsLayer()
    return remember(layer) { BackdropState(layer) }
}

/** 把这一层内容录进 GraphicsLayer 并照常画出来（画的内容不变，只是多录一份） */
fun Modifier.captureBackdrop(state: BackdropState): Modifier = drawWithContent {
    val w = size.width.toInt()
    val h = size.height.toInt()
    if (w > 0 && h > 0) {
        state.layer.record(
            density = this,
            layoutDirection = layoutDirection,
            size = IntSize(w, h)
        ) {
            this@drawWithContent.drawContent()
        }
        drawLayer(state.layer)
    } else {
        drawContent()
    }
}

/**
 * 隔一段时间重截一次。
 *
 * 理想情况是每帧都截，那等于每帧做一次全屏回读，太贵；
 * 这里用 600ms 的节流换流畅度 —— 滚动时玻璃会略微滞后，
 * 停下来就是准的。对底栏这种静态区域足够了。
 */
@Composable
fun CaptureBackdropEffect(state: BackdropState, key: Any?) {
    LaunchedEffect(key) {
        // 注意：这里**不能** clear()。清掉会让底栏瞬间退回兜底底色，
        // 切页时就是那一下「闪出被遮住的内容」。旧图用到新图来为止。
        while (true) {
            withFrameNanos { }
            val layer = state.layer
            val size = layer.size
            if (size.width > 0 && size.height > 0) {
                runCatching {
                    val full = layer.toImageBitmap()
                    // 全屏 ARGB_8888 一次就是 10MB 上下，隔一会儿截一次已经够贵，
                    // 存下来和采样都用半分辨率 —— 玻璃本来就是模糊的，看不出来。
                    val raw = full.asAndroidBitmap()
                    val soft = if (raw.config == Bitmap.Config.HARDWARE) {
                        raw.copy(Bitmap.Config.ARGB_8888, false) ?: raw
                    } else {
                        raw
                    }
                    val half = Bitmap.createScaledBitmap(
                        soft,
                        (soft.width / 2).coerceAtLeast(1),
                        (soft.height / 2).coerceAtLeast(1),
                        true
                    )
                    Triple(half, IntSize(half.width, half.height), 0.5f)
                }.getOrNull()?.let { (bitmap, pixels, scale) ->
                    // 截出来是纯黑的说明这次没采到（图层还没内容 / 被合成器丢了）。
                    // 与其拿一张黑图去当玻璃，不如当作没有，退回半透明底色 ——
                    // 至少那样背后是真的能看见。
                    if (!looksBlank(bitmap)) {
                        state.bitmap = bitmap.asImageBitmap()
                        state.sizePx = pixels
                        state.scale = scale
                    }
                }
            }
            delay(1200)
        }
    }
}

/** 抽样判断这张图是不是「什么都没有」 */
private fun looksBlank(bitmap: Bitmap): Boolean {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return true
    var sum = 0L
    var count = 0
    for (i in 0 until 8) {
        for (j in 0 until 8) {
            val px = bitmap.getPixel(w * i / 8, h * j / 8)
            sum += (android.graphics.Color.red(px) +
                android.graphics.Color.green(px) +
                android.graphics.Color.blue(px)) / 3
            count++
        }
    }
    return count == 0 || sum / count < 8
}

/**
 * 生成玻璃画笔。
 *
 * 返回 null 的三种情况，调用方都应当退回渐变：
 *  · 还没有截到画面
 *  · 系统低于 Android 13（没有 AGSL）
 *  · 着色器编译失败
 */
@Composable
fun rememberLiquidGlassBrush(
    state: BackdropState,
    offsetPx: Offset,
    sizePx: Size,
    cornerPx: Float,
    tint: Color,
    blurPx: Float,
    refraction: Float,
    dispersion: Float,
    highlight: Float,
    lift: Float = 0f
): Brush? {
    val context = LocalContext.current
    val bitmap = state.bitmap
    var reason: String? = null

    val brush = remember(
        bitmap, offsetPx, sizePx, cornerPx, tint, blurPx, refraction, dispersion, highlight, lift, state.scale
    ) {
        if (bitmap == null) return@remember null
        if (sizePx.width < 1f || sizePx.height < 1f) return@remember null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@remember null
        runCatching {
            buildGlassShader(
                context = context,
                bitmap = bitmap,
                offsetPx = offsetPx,
                sizePx = sizePx,
                cornerPx = cornerPx,
                tint = tint,
                blurPx = blurPx,
                scale = state.scale,
                refraction = refraction,
                dispersion = dispersion,
                highlight = highlight,
                lift = lift
            )
        }.onFailure {
            reason = it.message?.take(120) ?: it::class.java.simpleName
        }.getOrNull()
    }

    // 把真实原因写出来，省得「没效果」和「没生效」两件事分不清
    val statusText = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ->
            "玻璃：系统低于 Android 13，已退回渐变"
        bitmap == null -> "玻璃：等待首帧快照…"
        brush == null -> "玻璃：着色器不可用 —— ${reason ?: "未知原因"}"
        else -> "玻璃：着色器运行中"
    }
    SideEffect { GlassDiagnostics.status = statusText }

    return brush
}

/** 玻璃当前的实际状态，显示在设置页，方便判断到底是没生效还是没做好 */
object GlassDiagnostics {
    var status: String by mutableStateOf("玻璃：未启用")
        internal set
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun buildGlassShader(
    context: android.content.Context,
    bitmap: ImageBitmap,
    offsetPx: Offset,
    sizePx: Size,
    cornerPx: Float,
    tint: Color,
    blurPx: Float,
    scale: Float,
    refraction: Float,
    dispersion: Float,
    highlight: Float,
    lift: Float
): ShaderBrush {
    val source = context.resources.openRawResource(R.raw.liquid_glass)
        .bufferedReader()
        .use { it.readText() }

    val shader = android.graphics.RuntimeShader(source)

    // 硬件位图不能直接当输入纹理，先落回普通位图
    val src = bitmap.asAndroidBitmap()
    val soft = if (src.config == Bitmap.Config.HARDWARE) {
        src.copy(Bitmap.Config.ARGB_8888, false) ?: src
    } else {
        src
    }

    // 位图要包成 BitmapShader 才能当着色器的输入纹理
    val texture = android.graphics.BitmapShader(
        soft,
        android.graphics.Shader.TileMode.CLAMP,
        android.graphics.Shader.TileMode.CLAMP
    )
    shader.setInputBuffer("uContent", texture)
    shader.setFloatUniform("uSize", sizePx.width, sizePx.height)
    shader.setFloatUniform("uOffset", offsetPx.x, offsetPx.y)
    shader.setFloatUniform("uMax", soft.width.toFloat(), soft.height.toFloat())
    shader.setFloatUniform("uScale", scale)
    shader.setFloatUniform("uCorner", cornerPx)
    shader.setFloatUniform("uBlur", blurPx * scale)
    shader.setFloatUniform("uRefraction", refraction)
    shader.setFloatUniform("uDispersion", dispersion)
    shader.setFloatUniform("uHighlight", highlight)
    shader.setFloatUniform("uLift", lift)
    // uTint 是普通 float4，不是 layout(color)，只能用浮点接口塞进去；
    // 用 setColorUniform 会抛 "non-color specific APIs"，整个着色器就废了
    shader.setFloatUniform(
        "uTint",
        tint.red,
        tint.green,
        tint.blue,
        tint.alpha
    )

    return ShaderBrush(shader)
}

/**
 * 底栏背景：有画笔就走玻璃，没有就退回渐变。
 * 两种情况都补一条顶边高光，否则玻璃的「边」会糊在背景里。
 */
fun Modifier.liquidGlassBackground(
    brush: Brush?,
    fallbackTop: Color,
    fallbackBottom: Color,
    edge: Color
): Modifier = drawBehind {
    val gradient = Brush.verticalGradient(listOf(fallbackTop, fallbackBottom))
    drawRect(brush ?: gradient)
    val stroke = 1.dp.toPx()
    drawLine(
        color = edge,
        start = Offset(0f, stroke / 2f),
        end = Offset(size.width, stroke / 2f),
        strokeWidth = stroke
    )
}
