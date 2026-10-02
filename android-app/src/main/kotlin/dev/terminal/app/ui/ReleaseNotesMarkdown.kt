package dev.terminal.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.TextViewCompat
import dev.terminal.app.BundledFonts
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.core.MarkwonTheme
import org.commonmark.node.Image
import java.net.URI
import kotlin.math.roundToInt

@Composable
internal fun ReleaseNotesMarkdown(markdown: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val body = typography.bodyMedium
    val textSize = with(density) { body.fontSize.toPx() }
    val lineHeight = with(density) { body.lineHeight.toPx().roundToInt() }
    val renderer = remember(context, colors, typography, density) {
        createReleaseNotesMarkwon(context) { theme ->
            theme.linkColor(colors.tertiary.toArgb())
                .codeTextColor(colors.onSurface.toArgb())
                .codeBackgroundColor(colors.surfaceContainerHigh.toArgb())
                .codeTextSize(textSize.roundToInt())
                .blockMargin(with(density) { 16.dp.roundToPx() })
                .blockQuoteColor(colors.outlineVariant.toArgb())
                .headingBreakHeight(0)
                .headingTextSizeMultipliers(floatArrayOf(
                    typography.headlineSmall.fontSize.value / body.fontSize.value,
                    typography.titleLarge.fontSize.value / body.fontSize.value,
                    typography.titleMedium.fontSize.value / body.fontSize.value,
                    typography.titleSmall.fontSize.value / body.fontSize.value,
                    typography.labelLarge.fontSize.value / body.fontSize.value,
                    typography.labelMedium.fontSize.value / body.fontSize.value,
                ))
                .thematicBreakColor(colors.outlineVariant.toArgb())
                .thematicBreakHeight(with(density) { 1.dp.roundToPx() })
        }
    }
    val rendered = remember(renderer, markdown) { renderer.toMarkdown(markdown) }
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { TextView(it).apply {
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            setTextIsSelectable(true)
            movementMethod = LinkMovementMethod.getInstance()
        } },
        update = { view ->
            view.typeface = BundledFonts.googleSans(view.context)
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
            TextViewCompat.setLineHeight(view, lineHeight)
            view.setTextColor(colors.onSurface.toArgb())
            view.setLinkTextColor(colors.tertiary.toArgb())
            view.highlightColor = colors.primaryContainer.toArgb()
            view.letterSpacing = body.letterSpacing.value / body.fontSize.value
            if (view.tag !== rendered) {
                renderer.setParsedMarkdown(view, rendered)
                view.tag = rendered
            }
        },
    )
}

internal fun isReleaseNotesWebLink(destination: String): Boolean = runCatching {
    val uri = URI(destination)
    (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) &&
        !uri.host.isNullOrBlank() && uri.rawUserInfo == null
}.getOrDefault(false)

/** Core rendering keeps release notes local; only a tapped web link opens another app. */
internal fun createReleaseNotesMarkwon(
    context: Context,
    configureTheme: (MarkwonTheme.Builder) -> Unit = {},
): Markwon = Markwon.builder(context).usePlugin(object : AbstractMarkwonPlugin() {
    override fun configureTheme(builder: MarkwonTheme.Builder) = configureTheme.invoke(builder)

    override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
        builder.linkResolver { view, destination ->
            if (isReleaseNotesWebLink(destination)) {
                try {
                    view.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(destination))
                        .addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: ActivityNotFoundException) {
                    // A device without a browser can still read the notes and install the update.
                } catch (_: SecurityException) {
                    // A blocked browser must not interrupt the update screen.
                }
            }
        }
    }

    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        // Keep image labels readable without downloading remote images.
        builder.on(Image::class.java) { visitor, image -> visitor.visitChildren(image) }
    }
}).build()
