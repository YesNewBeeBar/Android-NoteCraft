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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
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

    /** 这一帧是从哪儿来的：layer / pixelcopy / none，排查用 */
    var engine: String by mutableStateOf("none")
        internal set

    /** 最近一次失败的原因 */
    var reason: String? by mutableStateOf(null)
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
fun CaptureBackdropEffect(state: BackdropState, key: Any?, activity: android.app.Activity? = null) {
    LaunchedEffect(key) {
        // 注意：这里**不能** clear()。清掉会让底栏瞬间退回兜底底色，
        // 切页时就是那一下「闪出被遮住的内容」。旧图用到新图来为止。
        var round = 0
        while (true) {
            withFrameNanos { }
            var captured: Bitmap? = null

            // 路线一：Compose 自己的图层回读
            val layer = state.layer
            val size = layer.size
            if (size.width > 0 && size.height > 0) {
                runCatching {
                    val full = layer.toImageBitmap()
                    val raw = full.asAndroidBitmap()
                    val soft = if (raw.config == Bitmap.Config.HARDWARE) {
                        raw.copy(Bitmap.Config.ARGB_8888, false) ?: raw
                    } else {
                        raw
                    }
                    Bitmap.createScaledBitmap(
                        soft,
                        (soft.width / 2).coerceAtLeast(1),
                        (soft.height / 2).coerceAtLeast(1),
                        true
                    )
                }.onFailure {
                    state.reason = "layer: " + (it.message ?: it::class.java.simpleName)
                }.getOrNull()?.let { bmp ->
                    if (!looksBlank(bmp)) {
                        captured = bmp
                        state.engine = "layer"
                    } else {
                        state.reason = "layer: 截出来是空白"
                    }
                }
            }

            // 路线二：图层回读拿不到就直接从窗口拷屏幕上真实像素。
            // 慢一些（要过一次合成器），但几乎在所有机型上都可用。
            if (captured == null && activity != null) {
                runCatching { captureWindow(activity, 0.5f) }
                    .onFailure {
                        state.reason = "pixelcopy: " + (it.message ?: it::class.java.simpleName)
                    }
                    .getOrNull()?.let { bmp ->
                        captured = bmp
                        state.engine = "pixelcopy"
                    }
            }

            captured?.let {
                if (!looksBlank(it)) {
                    state.bitmap = it.asImageBitmap()
                    state.sizePx = IntSize(it.width, it.height)
                    state.scale = 0.5f
                }
            }
            round++
            delay(if (round < 3) 200 else 1200)
        }
    }
}

/** 直接从窗口抓一帧（包含当前界面，所以底栏会出现在里面，但底栏很淡，反馈可忽略） */
private suspend fun captureWindow(activity: android.app.Activity, scale: Float): Bitmap? =
    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val view = activity.window?.decorView
        val width = view?.width ?: 0
        val height = view?.height ?: 0
        if (width <= 0 || height <= 0) {
            cont.resumeWith(Result.success(null))
            return@suspendCancellableCoroutine
        }
        val target = Bitmap.createBitmap(
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val rect = android.graphics.Rect(0, 0, width, height)
        var resumed = false
        runCatching {
            android.view.PixelCopy.request(
                activity.window,
                rect,
                target,
                { result ->
                    if (!resumed) {
                        resumed = true
                        cont.resumeWith(
                            Result.success(
                                if (result == android.view.PixelCopy.SUCCESS) target else null
                            )
                        )
                    }
                },
                android.os.Handler(android.os.Looper.getMainLooper())
            )
        }.onFailure {
            if (!resumed) {
                resumed = true
                cont.resumeWith(Result.success(null))
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
        bitmap == null -> "玻璃：等待首帧快照… ${state.reason.orEmpty()}".trim()
        brush == null -> "玻璃：着色器不可用 —— ${reason ?: "未知原因"}"
        else -> "玻璃：着色器运行中（${state.engine}）"
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
/**
 * 底栏/玻璃球的玻璃底。
 *
 * **不截图**。内容已经录进 [BackdropState.layer]，这里给这个图层挂一个
 * `RenderEffect` 模糊，再原样画一遍、由调用方的 `clip()` 裁成底栏形状。
 *
 * 这就是 Flutter `BackdropFilter` 在 Android 上的等价物：
 *  · 采样的是当帧真实内容，没有滞后
 *  · 图层里没有底栏自己，不存在「越叠越脏」的正反馈
 *  · 全程 GPU，不需要每隔一段时间做一次全屏回读
 *
 * @param originX/originY 这块玻璃在整屏里的位置：图层是整屏坐标，
 *        而 DrawScope 的原点在这块玻璃的左上角，要平移回去才能对齐。
 */
fun Modifier.backdropGlass(
    state: BackdropState,
    originX: Float,
    originY: Float,
    blurPx: Float,
    tint: Color,
    edge: Color,
    fallback: Color,
    cornerPx: Float = 0f,
    rimPx: Float = 0f
): Modifier = drawBehind {
    val layer = state.layer
    var drawn = false
    if (layer.size.width > 0 && layer.size.height > 0) {
        drawn = runCatching {
            layer.renderEffect = BlurEffect(
                radiusX = blurPx,
                radiusY = blurPx,
                edgeTreatment = TileMode.Clamp
            )
            translate(left = -originX, top = -originY) {
                drawLayer(layer)
            }

            /*
             * 折射。
             *
             * 真正做折射要能采样「背后那张纹理」，但 Compose 的图层不接受自定义着色器。
             * 退一步用几何近似：把边缘一圈的内容以中心为基准放大 6% 再画一遍，
             * 遮罩成内侧的一圈环 —— 边缘看到的是「被放大的画面」，
             * 这正是玻璃边缘该有的光学错觉。中间那圈不画，否则整块都糊成大图。
             */
            if (rimPx > 0f && size.width > rimPx * 2 && size.height > rimPx * 2) {
                /*
                 * 三层同心环，放大倍率由外向内递减（1.22 → 1.14 → 1.07）。
                 *
                 * 只画一层的话边缘是一道「台阶」，看着像贴纸；
                 * 分三层就有了连续变化的斜率 —— 越靠边压得越狠，
                 * 这正是凸透镜在边缘把画面弯折的样子。
                 */
                val scales = floatArrayOf(1.32f, 1.20f, 1.09f)
                scales.forEachIndexed { index, factor ->
                    val outer = rimPx * index / scales.size
                    val inner = rimPx * (index + 1) / scales.size
                    val ring = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRoundRect(
                            RoundRect(
                                outer, outer, size.width - outer, size.height - outer,
                                CornerRadius((cornerPx - outer).coerceAtLeast(0f))
                            )
                        )
                        addRoundRect(
                            RoundRect(
                                inner, inner, size.width - inner, size.height - inner,
                                CornerRadius((cornerPx - inner).coerceAtLeast(0f))
                            )
                        )
                    }
                    clipPath(ring) {
                        scale(
                            scaleX = factor,
                            scaleY = factor,
                            pivot = center
                        ) {
                            translate(left = -originX, top = -originY) {
                                drawLayer(layer)
                            }
                        }
                    }
                }
            }

            layer.renderEffect = null
            true
        }.getOrDefault(false)
        if (!drawn) {
            // 出岔子时把渲染效果清掉，别把整个图层留在模糊状态
            runCatching { layer.renderEffect = null }
        }
    }
    // 染色：玻璃得有自己的颜色，否则只是「能看见背景」的透明洞
    drawRect(tint)
    if (!drawn) {
        // 连图层都没有（第一帧）：至少给个能看见背景的浅浅一层
        drawRect(fallback)
    }
    // 镜面反光：左上到右下的柔和扫光。玻璃最容易识别的特征其实是这道光，
    // 而不只是「透」—— 背景全黑时也只有它能说明这是一块玻璃
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.20f),
                Color.Transparent,
                Color.White.copy(alpha = 0.10f)
            ),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height)
        )
    )
    // 玻璃的「厚度」：一道很细的亮边
    val stroke = 1.5.dp.toPx()
    drawLine(
        color = edge,
        start = Offset(0f, stroke / 2f),
        end = Offset(size.width, stroke / 2f),
        strokeWidth = stroke
    )
    drawLine(
        color = edge.copy(alpha = edge.alpha * 0.5f),
        start = Offset(0f, size.height - stroke / 2f),
        end = Offset(size.width, size.height - stroke / 2f),
        strokeWidth = stroke
    )
}

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
