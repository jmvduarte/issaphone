package pt.ipca.issafone.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Modos de tema: segue o sistema, ou fixo em claro/escuro. */
enum class ModoTema(val rotulo: String) {
    AUTO("Automático"), CLARO("Claro"), ESCURO("Escuro");

    fun seguinte() = entries[(ordinal + 1) % entries.size]
}

// Cores da marca (as mesmas do ícone e da webapp)
private val AzulMarca = Color(0xFF2F5BFF)
private val VerdeAgua = Color(0xFF14C8B4)

private val Claro = lightColorScheme(
    primary = AzulMarca,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE4FF),
    onPrimaryContainer = Color(0xFF001257),
    secondary = VerdeAgua,
    onSecondary = Color.White,
    background = Color(0xFFF4F6FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE7EBF2),
    onSurfaceVariant = Color(0xFF3F4656),
)

private val Escuro = darkColorScheme(
    primary = Color(0xFF8FA6FF),
    onPrimary = Color(0xFF00218C),
    primaryContainer = Color(0xFF1F2B57),
    onPrimaryContainer = Color(0xFFDDE4FF),
    secondary = VerdeAgua,
    onSecondary = Color(0xFF003731),
    background = Color(0xFF0F1218),
    surface = Color(0xFF181C25),
    surfaceVariant = Color(0xFF262D3B),
    onSurfaceVariant = Color(0xFFC3C9D6),
)

@Composable
fun IssaFoneTema(modo: ModoTema, conteudo: @Composable () -> Unit) {
    val escuro = when (modo) {
        ModoTema.AUTO -> isSystemInDarkTheme()
        ModoTema.CLARO -> false
        ModoTema.ESCURO -> true
    }
    MaterialTheme(colorScheme = if (escuro) Escuro else Claro, content = conteudo)
}
