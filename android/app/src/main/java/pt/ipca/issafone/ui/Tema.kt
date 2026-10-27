package pt.ipca.issafone.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Modos de tema: segue o sistema, ou fixo em claro/escuro. */
enum class ModoTema(val rotulo: String) {
    AUTO("Automático"), CLARO("Claro"), ESCURO("Escuro");

    fun seguinte() = entries[(ordinal + 1) % entries.size]
}

private val Claro = lightColorScheme(
    primary = MarcaAzul,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E9FF),
    onPrimaryContainer = Color(0xFF0B1A5C),
    secondary = MarcaVerdeAgua,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5F7F2),
    background = Color(0xFFF3F5FA),
    onBackground = Color(0xFF111827),
    surface = Color.White,
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFECEFF5),
    onSurfaceVariant = Color(0xFF5B6475),
    outline = Color(0xFF9AA3B2),
    outlineVariant = Color(0xFFDDE2EB),
    error = CorDesligar,
)

private val Escuro = darkColorScheme(
    primary = Color(0xFF7C9BFF),
    onPrimary = Color(0xFF001A6E),
    primaryContainer = Color(0xFF1C2A5E),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = MarcaVerdeAgua,
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF0F3D38),
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFE6E9F0),
    surface = Color(0xFF151922),
    onSurface = Color(0xFFE6E9F0),
    surfaceVariant = Color(0xFF1F2430),
    onSurfaceVariant = Color(0xFFA3ABBB),
    outline = Color(0xFF5B6475),
    outlineVariant = Color(0xFF2A303D),
    error = Color(0xFFFF6B6F),
)

private val Formas = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val base = Typography()
private val Letras = base.copy(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
)

@Composable
fun IssaFoneTema(modo: ModoTema, conteudo: @Composable () -> Unit) {
    val escuro = when (modo) {
        ModoTema.AUTO -> isSystemInDarkTheme()
        ModoTema.CLARO -> false
        ModoTema.ESCURO -> true
    }
    MaterialTheme(
        colorScheme = if (escuro) Escuro else Claro,
        shapes = Formas,
        typography = Letras,
        content = conteudo
    )
}
