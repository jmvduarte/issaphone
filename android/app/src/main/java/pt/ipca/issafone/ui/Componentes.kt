package pt.ipca.issafone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* Cores fixas da marca e de estado (iguais nos dois temas) */
val MarcaAzul = Color(0xFF2F5BFF)
val MarcaVerdeAgua = Color(0xFF14C8B4)
val CorChamar = Color(0xFF1DB954)
val CorDesligar = Color(0xFFE5484D)
val CorOnline = Color(0xFF22C55E)
val CorAviso = Color(0xFFFFB020)

/** Degradê da marca (cabeçalho, botões principais). */
val DegradeMarca = Brush.linearGradient(listOf(MarcaAzul, Color(0xFF3B82F6), MarcaVerdeAgua))

/** Fundo do ecrã de chamada. */
val DegradeChamada = Brush.verticalGradient(listOf(Color(0xFF0B1230), Color(0xFF13245E), Color(0xFF0E3B4A)))

/** Pares de cores para os avatares: cada nome fica sempre com a mesma cor. */
private val PaletaAvatares = listOf(
    Color(0xFF6366F1) to Color(0xFF8B5CF6),
    Color(0xFF0EA5E9) to Color(0xFF22D3EE),
    Color(0xFFF97316) to Color(0xFFFBBF24),
    Color(0xFFEC4899) to Color(0xFFF472B6),
    Color(0xFF10B981) to Color(0xFF34D399),
    Color(0xFF2F5BFF) to Color(0xFF14C8B4),
    Color(0xFFEF4444) to Color(0xFFF97316),
    Color(0xFF8B5CF6) to Color(0xFFEC4899),
)

fun iniciais(nome: String): String {
    val p = nome.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (p.isEmpty()) return "?"
    return (if (p.size > 1 && p[0].any { !it.isDigit() }) "${p[0][0]}${p.last()[0]}" else p[0].take(2)).uppercase()
}

/** Avatar redondo com as iniciais (ou "#" nos grupos) e, opcionalmente, o ponto de presença. */
@Composable
fun Avatar(
    nome: String,
    tamanho: Dp = 46.dp,
    grupo: Boolean = false,
    online: Boolean? = null,
    chave: String = nome,
) {
    val (a, b) = PaletaAvatares[(chave.hashCode() and 0x7fffffff) % PaletaAvatares.size]
    Box(Modifier.size(tamanho)) {
        Box(
            Modifier.matchParentSize()
                .clip(if (grupo) RoundedCornerShape(tamanho * 0.3f) else CircleShape)
                .background(Brush.linearGradient(listOf(a, b))),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (grupo) "#" else iniciais(nome),
                color = Color.White, fontWeight = FontWeight.SemiBold,
                fontSize = (tamanho.value * 0.38f).sp
            )
        }
        if (online != null) {
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .size(tamanho * 0.3f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(if (online) CorOnline else Color(0xFF9CA3AF))
            )
        }
    }
}

/** Pílula de estado (ex.: "Telefone" com ponto verde) para fundos escuros/coloridos. */
@Composable
fun PilulaEstado(texto: String, ok: Boolean?, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .18f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(8.dp).clip(CircleShape).background(
                when (ok) { true -> Color(0xFF7CF2B0); false -> Color(0xFFFF9B9B); null -> CorAviso }
            )
        )
        Spacer(Modifier.width(6.dp))
        Text(texto, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Título de secção pequeno, em maiúsculas. */
@Composable
fun TituloSecao(texto: String, modifier: Modifier = Modifier, acao: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(texto.uppercase(), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
        acao?.invoke()
    }
}

/** Botão redondo com ícone e legenda (ecrã de chamada). */
@Composable
fun BotaoCircular(
    icone: ImageVector,
    legenda: String,
    onClick: () -> Unit,
    fundo: Color = Color.White.copy(alpha = .14f),
    corIcone: Color = Color.White,
    ativo: Boolean = false,
    tamanho: Dp = 64.dp,
    corLegenda: Color = Color.White.copy(alpha = .85f),
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(tamanho + 24.dp)) {
        Box(
            Modifier.size(tamanho).clip(CircleShape)
                .background(if (ativo) Color.White else fundo)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icone, contentDescription = legenda, tint = if (ativo) Color(0xFF0B1230) else corIcone,
                modifier = Modifier.size(tamanho * 0.42f))
        }
        Spacer(Modifier.height(8.dp))
        Text(legenda, color = corLegenda, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/** Estado vazio de uma lista: ícone grande, título e explicação. */
@Composable
fun EstadoVazio(icone: ImageVector, titulo: String, texto: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) { Icon(icone, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp)) }
        Spacer(Modifier.height(16.dp))
        Text(titulo, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(texto, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
    }
}

/** Campo de pesquisa arredondado, preenchido. */
@Composable
fun CampoPesquisa(valor: String, onValor: (String) -> Unit, dica: String, modifier: Modifier = Modifier) {
    TextField(
        valor, onValor, singleLine = true,
        placeholder = { Text(dica) },
        leadingIcon = { Icon(Icones.Pesquisar, null) },
        trailingIcon = if (valor.isNotEmpty()) ({
            IconButton(onClick = { onValor("") }) { Icon(Icones.Fechar, "Limpar") }
        }) else null,
        shape = RoundedCornerShape(50),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = modifier.fillMaxWidth()
    )
}

/** Cartão de lista arredondado (agrupa linhas). */
@Composable
fun CartaoLista(modifier: Modifier = Modifier, conteudo: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.padding(horizontal = 12.dp).fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f), RoundedCornerShape(22.dp)),
        content = conteudo
    )
}

/** Botão de ação pequeno e redondo (ligar, vídeo, mensagem) numa linha de lista. */
@Composable
fun AcaoLinha(icone: ImageVector, descricao: String, cor: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(cor.copy(alpha = .12f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icone, descricao, tint = cor, modifier = Modifier.size(20.dp)) }
}
