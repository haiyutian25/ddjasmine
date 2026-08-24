package com.lhzkml.jasmine.feature.session.ui

import android.content.Context
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import coil3.ImageLoader
import coil3.asDrawable
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableTheme
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.ImageItem
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.image.SchemeHandler
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import io.noties.markwon.syntax.Prism4jThemeDarkula
import io.noties.markwon.syntax.Prism4jThemeDefault
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import java.io.IOException
import kotlinx.coroutines.runBlocking

/**
 * Markwon 的 Compose 封装：Markwon 是 View 系渲染器（Spanned → TextView），
 * 经 AndroidView 嵌入 LazyColumn。`setMarkdown` 同步解析，行重组时即带完整
 * 高度——与旧渲染器 immediate=true 的契约一致（异步零高度行会顶回滚动位置）。
 *
 * 插件集：GFM 表格/删除线（inline parser 是删除线的前置）、HTML、图片、
 * 链接自动识别、Prism4j 代码高亮、JLatexMath 公式（含 $...$ 行内）。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bodyColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest.toArgb()
    val linkColor = MaterialTheme.colorScheme.secondary.toArgb()
    val outlineColor = MaterialTheme.colorScheme.outlineVariant.toArgb()
    val darkTheme = isSystemInDarkTheme()
    val textSizePx = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.fontSize.toPx() }
    val markwon = remember(
        context, bodyColor, codeBackground, linkColor, outlineColor, darkTheme, textSizePx,
    ) {
        buildMarkwon(
            context = context,
            textSizePx = textSizePx,
            bodyColor = bodyColor,
            codeBackground = codeBackground,
            linkColor = linkColor,
            outlineColor = outlineColor,
            darkTheme = darkTheme,
        )
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                movementMethod = LinkMovementMethod.getInstance()
            }
        },
        update = { view ->
            // 文本未变则完全跳过：不 invalidate、不重解析。setter 全部收进
            // 分支内——在 LazyColumn 行里每次重组都 setTextColor/setTextSize
            // 会触发布局抖动（含表格的行对此敏感）。
            if (view.tag != text) {
                view.tag = text
                view.setTextColor(bodyColor)
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
                markwon.setMarkdown(view, text)
            }
        },
    )
}

/**
 * App 级共享 Coil ImageLoader：每行 Markdown 各自 new 会各持一份缓存，
 * 必须单例。显式注册 OkHttp 网络组件以复用项目统一的网络栈。
 */
private object MarkdownImageLoader {
    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader =
        instance ?: synchronized(this) {
            instance ?: ImageLoader.Builder(context.applicationContext)
                .components { add(OkHttpNetworkFetcherFactory()) }
                .build()
                .also { instance = it }
        }
}

/**
 * Markwon 图片的 Coil 桥：Markwon 的图片加载管线在后台线程调用
 * [SchemeHandler.handle]（AsyncDrawableLoader 的 Executor），此处阻塞执行
 * Coil 请求是安全的；成功直接把 Drawable 交给 Markwon 排版，失败抛异常由
 * Markwon 的 errorHandler 记录（不炸 UI）。
 */
private class CoilSchemeHandler(
    private val context: Context,
    private val imageLoader: ImageLoader,
) : SchemeHandler() {

    override fun supportedSchemes(): List<String> = listOf("http", "https")

    override fun handle(raw: String, uri: android.net.Uri): ImageItem {
        val request = ImageRequest.Builder(context).data(raw).build()
        val result = runBlocking { imageLoader.execute(request) }
        val drawable = (result as? SuccessResult)?.image?.asDrawable(context.resources)
            ?: throw IOException("Coil image load failed: $raw")
        return ImageItem.withResult(drawable)
    }
}

private fun buildMarkwon(
    context: Context,
    textSizePx: Float,
    bodyColor: Int,
    codeBackground: Int,
    linkColor: Int,
    outlineColor: Int,
    darkTheme: Boolean,
): Markwon {
    // Compose MaterialTheme 取色注入 Markwon 主题，深浅色随系统切换。
    val themePlugin = object : AbstractMarkwonPlugin() {
        override fun configureTheme(builder: MarkwonTheme.Builder) {
            builder
                .linkColor(linkColor)
                .codeTextColor(bodyColor)
                .codeBackgroundColor(codeBackground)
                .codeBlockTextColor(bodyColor)
                .codeBlockBackgroundColor(codeBackground)
                .blockQuoteColor(outlineColor)
        }
    }
    val tableTheme = TableTheme.emptyBuilder()
        .tableBorderColor(outlineColor)
        .tableBorderWidth(1)
        .tableHeaderRowBackgroundColor(codeBackground)
        .build()
    val prismTheme = if (darkTheme) Prism4jThemeDarkula.create() else Prism4jThemeDefault.create()
    return Markwon.builder(context)
        .usePlugin(themePlugin)
        .usePlugin(MarkwonInlineParserPlugin.create())
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(TablePlugin.create(tableTheme))
        .usePlugin(HtmlPlugin.create())
        .usePlugin(ImagesPlugin.create { plugin ->
            // http/https 图片走 Coil（缓存 + 统一 OkHttp），file:/data: 等
            // 其余 scheme 保留 Markwon 默认处理器。
            plugin.addSchemeHandler(CoilSchemeHandler(context, MarkdownImageLoader.get(context)))
        })
        .usePlugin(LinkifyPlugin.create())
        .usePlugin(SyntaxHighlightPlugin.create(PrismSupport.create(), prismTheme))
        .usePlugin(JLatexMathPlugin.create(textSizePx) { builder -> builder.inlinesEnabled(true) })
        .build()
}
