package dev.terminal.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.terminal.app.ShellTokenKind
import dev.terminal.app.shellTokens

@Composable
internal fun ShellScriptEditor(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier, onTextLayout: (TextLayoutResult) -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    val transformation = remember(colors.tertiary, colors.onSurfaceVariant, colors.onSurface) {
        ShellSyntaxTransformation(colors.tertiary, colors.onSurfaceVariant, colors.onSurface)
    }
    Surface(modifier, color = colors.surfaceContainerLow, shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, colors.outline)) {
        BasicTextField(value, onValueChange,
            modifier = Modifier.fillMaxSize().padding(16.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = GoogleSansCodeFontFamily,
                fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp, color = colors.onSurface),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            cursorBrush = SolidColor(colors.primary), visualTransformation = transformation,
            onTextLayout = onTextLayout)
    }
}

/** Only spans change, so selection, composing text, tabs and line breaks retain their original offsets. */
internal class ShellSyntaxTransformation(keyword: Color, comment: Color, operator: Color) : VisualTransformation {
    private val styles = mapOf(
        ShellTokenKind.KEYWORD to SpanStyle(color = keyword, fontWeight = FontWeight.SemiBold),
        ShellTokenKind.STRING to SpanStyle(color = Color(0xFFE6C384)),
        ShellTokenKind.COMMENT to SpanStyle(color = comment, fontStyle = FontStyle.Italic),
        ShellTokenKind.VARIABLE to SpanStyle(color = Color(0xFFD8BDF8)),
        ShellTokenKind.OPERATOR to SpanStyle(color = operator, fontWeight = FontWeight.SemiBold),
    )
    override fun filter(text: AnnotatedString) = TransformedText(buildAnnotatedString {
        append(text)
        shellTokens(text.text).forEach { token -> addStyle(styles.getValue(token.kind), token.start, token.end) }
    }, OffsetMapping.Identity)
}
