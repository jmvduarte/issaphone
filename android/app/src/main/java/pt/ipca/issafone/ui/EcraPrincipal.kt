package pt.ipca.issafone.ui

import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.linphone.mediastream.video.capture.CaptureTextureView
import pt.ipca.issafone.AppViewModel
import pt.ipca.issafone.BuildConfig
import pt.ipca.issafone.R
import pt.ipca.issafone.sip.CallUi
import pt.ipca.issafone.sip.Chamada
import pt.ipca.issafone.xmpp.Contacto
import pt.ipca.issafone.xmpp.Grupo
import pt.ipca.issafone.xmpp.Membro
import pt.ipca.issafone.xmpp.Mensagem
import java.text.Normalizer
import java.text.SimpleDateFormat
import androidx.lifecycle.compose.currentStateAsState
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val PT = Locale("pt", "PT")
private fun simples(t: String) = Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()
private fun ext(jid: String) = jid.substringBefore("@")

/* =============================================================================================
 *  Ecrã principal (ligado ao ViewModel). Os ecrãs em si são "sem estado" (mais abaixo), para
 *  poderem ser desenhados nas capturas automáticas do GitHub.
 * ============================================================================================= */

@Composable
fun EcraPrincipal(vm: AppViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    var conversaCom by remember { mutableStateOf<String?>(null) }
    val chamada by vm.sip.chamada.collectAsState()
    val registo by vm.sip.registo.collectAsState()
    val estadoChat by vm.xmpp.estado.collectAsState()
    val atualizacao by vm.estadoAtualizacao.collectAsState()
    val aviso by vm.xmpp.aviso.collectAsState()
    val contactos by vm.xmpp.contactos.collectAsState()
    val grupos by vm.xmpp.grupos.collectAsState()
    val mensagens by vm.xmpp.mensagens.collectAsState()
    val perdidas by vm.sip.registoChamadas.perdidas.collectAsState()
    val tema by vm.tema.collectAsState()
    val ctx = LocalContext.current
    val escopo = rememberCoroutineScope()

    // Tocar numa notificação de mensagem abre logo essa conversa
    val pedido by vm.abrirConversa.collectAsState()
    LaunchedEffect(pedido) { pedido?.let { tab = 3; conversaCom = it; vm.pedirConversa(null) } }
    // Sempre que a app volta ao ecrã, procura atualizações (no máximo de 10 em 10 min)
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        val c = vm.sip.chamada.value
        if (!c.ativa && !c.aTocar) escopo.launch { pt.ipca.issafone.atualizacao.Atualizador.verificarSeDevido(ctx) }
        onPauseOrDispose { }
    }
    LaunchedEffect(aviso) { aviso?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.xmpp.limparAviso() } }

    val ligar = { num: String, video: Boolean -> vm.sip.chamar(num, video) }
    val ultima = { jid: String ->
        mensagens.lastOrNull { it.contacto.equals(jid, ignoreCase = true) }?.let {
            val quem = if (it.enviada) "Tu: " else it.autor?.let { a -> "${vm.xmpp.nomeExt(a)}: " } ?: ""
            UltimaMensagem(quem + pt.ipca.issafone.xmpp.resumoMensagem(it.texto), it.hora)
        }
    }

    Box(Modifier.fillMaxSize()) {
        MolduraPrincipal(
            cabecalho = {
                Cabecalho(
                    registo = registo, chat = estadoChat, atualizacao = atualizacao, tema = tema.rotulo,
                    onAtualizar = { escopo.launch { pt.ipca.issafone.atualizacao.Atualizador.verificarEInstalar(ctx, manual = true) } },
                    onTema = { vm.mudarTema() },
                    onSair = { vm.sair() }
                )
            },
            tab = tab, perdidas = perdidas,
            onTab = { novo ->
                tab = novo
                if (novo == 2) vm.sip.registoChamadas.marcarVistas()
                if (novo != 3) conversaCom = null
            },
            avisos = { AvisosSegundoPlano() }
        ) {
            when (tab) {
                0 -> {
                    var numero by rememberSaveable { mutableStateOf("") }
                    TecladoUi(numero, { numero = it }, onChamar = { video -> ligar(numero, video) })
                }
                1 -> AgendaUi(contactos, onLigar = ligar, onMensagem = { conversaCom = it; tab = 3 })
                2 -> {
                    val lista by vm.sip.registoChamadas.lista.collectAsState()
                    LaunchedEffect(lista.size) { vm.sip.registoChamadas.marcarVistas() }
                    RecentesUi(
                        lista,
                        nomeDe = { num, nome -> contactos.firstOrNull { ext(it.jid) == num }?.nome ?: nome },
                        onLigar = ligar,
                        onLimpar = { vm.sip.registoChamadas.limpar() }
                    )
                }
                else -> {
                    var criar by remember { mutableStateOf(false) }
                    ListaChatUi(
                        contactos, grupos, ultima,
                        onAbrir = { conversaCom = it },
                        onAdicionar = { vm.adicionarContacto(it) },
                        onNovoGrupo = { criar = true }
                    )
                    if (criar) DialogoNovoGrupo(contactos, onCancelar = { criar = false }) { nome, membros ->
                        criar = false
                        vm.criarGrupo(nome, membros) { jid -> jid?.let { conversaCom = it } }
                    }
                }
            }
        }

        // A conversa abre por cima de tudo (ecrã inteiro)
        conversaCom?.let { jid ->
            BackHandler { conversaCom = null }
            Conversa(vm, jid, onVoltar = { conversaCom = null },
                onLigar = { video -> vm.sip.chamar(ext(jid), video) })
        }

        // O ecrã de chamada fica por cima de tudo (incluindo as barras)
        if (chamada.ativa || chamada.aTocar) PainelChamada(vm, chamada)
    }
}

/** Esqueleto: cabeçalho, avisos, conteúdo e barra de navegação. */
@Composable
internal fun MolduraPrincipal(
    cabecalho: @Composable () -> Unit,
    tab: Int,
    perdidas: Int,
    onTab: (Int) -> Unit,
    avisos: @Composable () -> Unit = {},
    conteudo: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = cabecalho,
        bottomBar = { BarraNavegacao(tab, perdidas, onTab) }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            avisos()
            Box(Modifier.weight(1f)) { conteudo() }
        }
    }
}

@Composable
private fun BarraNavegacao(tab: Int, perdidas: Int, onTab: (Int) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        val cores = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
        )
        NavigationBarItem(tab == 0, { onTab(0) }, icon = { Icon(Icones.Teclado, null) }, label = { Text("Teclado") }, colors = cores)
        NavigationBarItem(tab == 1, { onTab(1) }, icon = { Icon(Icones.Contactos, null) }, label = { Text("Contactos") }, colors = cores)
        NavigationBarItem(tab == 2, { onTab(2) },
            icon = { BadgedBox(badge = { if (perdidas > 0) Badge { Text("$perdidas") } }) { Icon(Icones.Recentes, null) } },
            label = { Text("Recentes") }, colors = cores)
        NavigationBarItem(tab == 3, { onTab(3) }, icon = { Icon(Icones.Chat, null) }, label = { Text("Chat") }, colors = cores)
    }
}

/** Cabeçalho com o degradê da marca, estado do telefone e do chat, e menu. */
@Composable
internal fun Cabecalho(
    registo: String,
    chat: String,
    atualizacao: String?,
    tema: String,
    onAtualizar: () -> Unit,
    onTema: () -> Unit,
    onSair: () -> Unit,
) {
    val telOk = when {
        registo.startsWith("Registado", ignoreCase = true) -> true
        registo.startsWith("A registar") -> null
        else -> false
    }
    val chatOk = when {
        chat.startsWith("Ligado") -> true
        chat.startsWith("A ligar") -> null
        else -> false
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .background(DegradeMarca)
            .statusBarsPadding()
            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.logo_issafone), null,
                Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).border(1.dp, Color.White.copy(alpha = .5f), RoundedCornerShape(11.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(BuildConfig.NOME_APP, color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text("v${BuildConfig.VERSION_NAME}", color = Color.White.copy(alpha = .7f), fontSize = 11.sp)
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icones.Menu, "Menu", tint = Color.White) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Procurar atualizações") }, leadingIcon = { Icon(Icones.Atualizar, null) },
                        onClick = { menu = false; onAtualizar() })
                    DropdownMenuItem(text = { Text("Tema: $tema") }, leadingIcon = { Icon(Icones.Tema, null) }, onClick = onTema)
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Sair", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icones.Sair, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onSair() })
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PilulaEstado("Telefone · ${registo.lowercase().removePrefix("falhou: ").take(24)}", telOk)
            PilulaEstado(if (chatOk == true) "Chat · ligado" else "Chat · ${chat.lowercase().take(24)}", chatOk)
        }
        atualizacao?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Color.White, fontSize = 12.sp, maxLines = 2,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onAtualizar))
        }
    }
}

/* ============================== Teclado ============================== */

private val Letras = mapOf('2' to "ABC", '3' to "DEF", '4' to "GHI", '5' to "JKL", '6' to "MNO", '7' to "PQRS", '8' to "TUV", '9' to "WXYZ", '0' to "+")

@Composable
internal fun TecladoUi(numero: String, onNumero: (String) -> Unit, onChamar: (video: Boolean) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            numero.ifEmpty { "Extensão ou número" },
            fontSize = if (numero.isEmpty()) 22.sp else 38.sp,
            fontWeight = if (numero.isEmpty()) FontWeight.Normal else FontWeight.Light,
            color = if (numero.isEmpty()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onBackground,
            letterSpacing = if (numero.isEmpty()) 0.sp else 2.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(52.dp).wrapContentHeight()
        )
        Spacer(Modifier.height(20.dp))
        listOf("123", "456", "789", "*0#").forEach { linha ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp), modifier = Modifier.padding(vertical = 7.dp)) {
                linha.forEach { c ->
                    Column(
                        Modifier.size(76.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f), CircleShape)
                            .clickable { onNumero(numero + c) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(c.toString(), fontSize = 28.sp, fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface)
                        Letras[c]?.let {
                            Text(it, fontSize = 10.sp, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            // Videochamada
            Box(
                Modifier.size(60.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(enabled = numero.isNotEmpty()) { onChamar(true) },
                contentAlignment = Alignment.Center
            ) { Icon(Icones.Video, "Videochamada", tint = MaterialTheme.colorScheme.primary) }
            // Ligar
            Box(
                Modifier.size(76.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF22C55E), Color(0xFF16A34A))))
                    .clickable(enabled = numero.isNotEmpty()) { onChamar(false) },
                contentAlignment = Alignment.Center
            ) { Icon(Icones.Ligar, "Ligar", tint = Color.White, modifier = Modifier.size(34.dp)) }
            // Apagar
            Box(
                Modifier.size(60.dp).clip(CircleShape).clickable(enabled = numero.isNotEmpty()) { onNumero(numero.dropLast(1)) },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icones.Apagar, "Apagar",
                    tint = if (numero.isEmpty()) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/* ============================== Contactos (agenda) ============================== */

@Composable
internal fun AgendaUi(
    contactos: List<Contacto>,
    onLigar: (String, Boolean) -> Unit,
    onMensagem: (String) -> Unit,
) {
    var filtro by rememberSaveable { mutableStateOf("") }
    val lista = contactos
        .filter { c -> filtro.isBlank() || simples(c.nome + " " + ext(c.jid)).contains(simples(filtro.trim())) }
        .sortedBy { simples(it.nome) }
    val porLetra = lista.groupBy { simples(it.nome).firstOrNull()?.uppercaseChar()?.takeIf { l -> l.isLetter() } ?: '#' }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            CampoPesquisa(filtro, { filtro = it }, "Procurar nome ou extensão",
                Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
        }
        if (lista.isEmpty()) item {
            EstadoVazio(Icones.Contactos,
                if (filtro.isBlank()) "Ainda sem contactos" else "Nenhum contacto encontrado",
                if (filtro.isBlank()) "As extensões criadas no Issabel aparecem aqui automaticamente."
                else "Experimenta outro nome ou o número da extensão.")
        }
        porLetra.forEach { (letra, grupo) ->
            item(key = "letra-$letra") { TituloSecao(letra.toString()) }
            item(key = "cartao-$letra") {
                CartaoLista {
                    grupo.forEachIndexed { i, c ->
                        val num = ext(c.jid)
                        Row(
                            Modifier.fillMaxWidth().clickable { onLigar(num, false) }.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(c.nome, online = c.online, chave = num)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.nome, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "Ext. $num · ${if (c.online) "disponível" else "ausente"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (c.online) CorOnline else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AcaoLinha(Icones.Chat, "Mensagem", MaterialTheme.colorScheme.secondary) { onMensagem(c.jid) }
                                AcaoLinha(Icones.Video, "Videochamada", MaterialTheme.colorScheme.primary) { onLigar(num, true) }
                                AcaoLinha(Icones.Ligar, "Ligar", CorChamar) { onLigar(num, false) }
                            }
                        }
                        if (i < grupo.lastIndex) HorizontalDivider(Modifier.padding(start = 74.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                    }
                }
            }
        }
    }
}

/* ============================== Recentes (registo de chamadas) ============================== */

@Composable
internal fun RecentesUi(
    lista: List<Chamada>,
    nomeDe: (numero: String, nome: String) -> String,
    onLigar: (String, Boolean) -> Unit,
    onLimpar: () -> Unit,
    agora: Long = System.currentTimeMillis(),
) {
    var limpar by remember { mutableStateOf(false) }
    val fmtH = remember { SimpleDateFormat("HH:mm", PT) }
    val fmtD = remember { SimpleDateFormat("d MMM", PT) }
    fun dia(h: Long): Int { val c = Calendar.getInstance().apply { timeInMillis = h }; return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR) }
    val hoje = dia(agora)
    val ontem = dia(agora - 86_400_000L)
    fun secao(h: Long) = when (dia(h)) { hoje -> "Hoje"; ontem -> "Ontem"; else -> "Mais antigas" }
    fun duracao(s: Int) = if (s >= 3600) "%dh%02d".format(s / 3600, s % 3600 / 60) else "%d:%02d".format(s / 60, s % 60)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (lista.isEmpty()) item {
            EstadoVazio(Icones.Recentes, "Sem chamadas recentes", "As chamadas que fizeres e receberes aparecem aqui.",
                Modifier.padding(top = 24.dp))
        }
        lista.groupBy { secao(it.hora) }.forEach { (titulo, chamadas) ->
            item(key = "sec-$titulo") {
                val primeira = titulo == secao(lista.first().hora)
                TituloSecao(titulo, acao = {
                    if (primeira) TextButton(onClick = { limpar = true }) { Text("Limpar") }
                })
            }
            item(key = "cartao-$titulo") {
                CartaoLista {
                    chamadas.forEachIndexed { i, c ->
                        val nome = nomeDe(c.numero, c.nome)
                        val perdida = c.tipo == "perdida" || c.tipo == "recusada"
                        val (icone, cor, rotulo) = when (c.tipo) {
                            "feita" -> Triple(Icones.Feita, MaterialTheme.colorScheme.primary, "Efetuada")
                            "recebida" -> Triple(Icones.Recebida, CorOnline, "Recebida")
                            "recusada" -> Triple(Icones.Perdida, CorDesligar, "Recusada")
                            else -> Triple(Icones.Perdida, CorDesligar, "Não atendida")
                        }
                        Row(
                            Modifier.fillMaxWidth().clickable { onLigar(c.numero, false) }.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(nome, chave = c.numero)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(nome, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (perdida) CorDesligar else MaterialTheme.colorScheme.onSurface)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(icone, null, tint = cor, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        listOfNotNull(
                                            rotulo + if (c.video) " · vídeo" else "",
                                            c.duracao.takeIf { it > 0 }?.let { duracao(it) }
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                if (dia(c.hora) == hoje || dia(c.hora) == ontem) fmtH.format(Date(c.hora)) else fmtD.format(Date(c.hora)),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(10.dp))
                            AcaoLinha(if (c.video) Icones.Video else Icones.Ligar, "Ligar", CorChamar) { onLigar(c.numero, c.video) }
                        }
                        if (i < chamadas.lastIndex) HorizontalDivider(Modifier.padding(start = 74.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                    }
                }
            }
        }
    }

    if (limpar) AlertDialog(
        onDismissRequest = { limpar = false },
        icon = { Icon(Icones.Lixo, null) },
        title = { Text("Limpar recentes") },
        text = { Text("Apagar todas as chamadas do registo deste telemóvel?") },
        confirmButton = { Button(onClick = { onLimpar(); limpar = false }) { Text("Limpar") } },
        dismissButton = { TextButton(onClick = { limpar = false }) { Text("Cancelar") } }
    )
}

/* ============================== Chat: lista de conversas ============================== */

data class UltimaMensagem(val texto: String, val hora: Long)

@Composable
internal fun ListaChatUi(
    contactos: List<Contacto>,
    grupos: List<Grupo>,
    ultima: (String) -> UltimaMensagem?,
    onAbrir: (String) -> Unit,
    onAdicionar: (String) -> Unit,
    onNovoGrupo: () -> Unit,
    agora: Long = System.currentTimeMillis(),
) {
    var novo by rememberSaveable { mutableStateOf("") }
    val fmtH = remember { SimpleDateFormat("HH:mm", PT) }
    val fmtD = remember { SimpleDateFormat("d/MM", PT) }
    fun quando(h: Long) = if (agora - h < 20 * 3_600_000L) fmtH.format(Date(h)) else fmtD.format(Date(h))
    // Conversas com mensagens primeiro (mais recentes em cima), depois os restantes contactos por nome
    val ordenados = contactos.sortedWith(compareByDescending<Contacto> { ultima(it.jid)?.hora ?: 0L }.thenBy { simples(it.nome) })

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    novo, { novo = it.trim() }, singleLine = true,
                    placeholder = { Text("Adicionar colega (ex.: 102)") },
                    leadingIcon = { Icon(Icones.Adicionar, null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(50),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier.weight(1f)
                )
                if (novo.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(onClick = { onAdicionar(novo); novo = "" }) { Icon(Icones.Adicionar, "Adicionar") }
                }
            }
        }
        item {
            TituloSecao("Grupos", acao = {
                TextButton(onClick = onNovoGrupo, enabled = contactos.isNotEmpty()) {
                    Icon(Icones.NovoGrupo, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Novo grupo")
                }
            })
        }
        if (grupos.isEmpty()) item {
            Text("Ainda não estás em nenhum grupo.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        } else item {
            CartaoLista {
                grupos.forEachIndexed { i, g ->
                    val u = ultima(g.jid)
                    LinhaConversa(g.nome, u?.texto ?: "${g.ligados.size + 1} ligados", u?.let { quando(it.hora) },
                        grupo = true, online = null, chave = g.jid) { onAbrir(g.jid) }
                    if (i < grupos.lastIndex) HorizontalDivider(Modifier.padding(start = 76.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                }
            }
        }
        item { TituloSecao("Conversas") }
        if (contactos.isEmpty()) item {
            EstadoVazio(Icones.Chat, "Sem contactos",
                "No Openfire, ativa a partilha da lista de contactos do grupo IssaFone.")
        } else item {
            CartaoLista {
                ordenados.forEachIndexed { i, c ->
                    val u = ultima(c.jid)
                    val num = ext(c.jid)
                    LinhaConversa(c.nome, u?.texto ?: "Ext. $num · ${if (c.online) "disponível" else "ausente"}",
                        u?.let { quando(it.hora) }, grupo = false, online = c.online, chave = num) { onAbrir(c.jid) }
                    if (i < ordenados.lastIndex) HorizontalDivider(Modifier.padding(start = 76.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                }
            }
        }
    }
}

@Composable
private fun LinhaConversa(nome: String, sub: String, hora: String?, grupo: Boolean, online: Boolean?, chave: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(nome, tamanho = 48.dp, grupo = grupo, online = online, chave = chave)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(nome, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                hora?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DialogoNovoGrupo(contactos: List<Contacto>, onCancelar: () -> Unit, onCriar: (String, List<String>) -> Unit) {
    var nome by remember { mutableStateOf("") }
    val escolhidos = remember { mutableStateListOf<String>() }
    var erro by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onCancelar,
        icon = { Icon(Icones.NovoGrupo, null) },
        title = { Text("Novo grupo") },
        text = {
            Column {
                OutlinedTextField(nome, { nome = it.take(40); erro = null }, label = { Text("Nome do grupo") },
                    singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text("Membros", style = MaterialTheme.typography.labelLarge)
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    contactos.forEach { c ->
                        val marcado = c.jid in escolhidos
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                if (marcado) escolhidos.remove(c.jid) else escolhidos.add(c.jid); erro = null
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(c.nome, tamanho = 36.dp, chave = ext(c.jid))
                            Spacer(Modifier.width(12.dp))
                            Text(c.nome, modifier = Modifier.weight(1f))
                            Checkbox(marcado, onCheckedChange = {
                                if (it) escolhidos.add(c.jid) else escolhidos.remove(c.jid); erro = null
                            })
                        }
                    }
                }
                erro?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = {
                erro = when {
                    nome.isBlank() -> "Escreve um nome para o grupo."
                    escolhidos.isEmpty() -> "Escolhe pelo menos um membro."
                    else -> null
                }
                if (erro == null) onCriar(nome.trim(), escolhidos.toList())
            }) { Text("Criar grupo") }
        },
        dismissButton = { TextButton(onClick = onCancelar) { Text("Cancelar") } }
    )
}

/* ============================== Conversa ============================== */

@Composable
private fun Conversa(vm: AppViewModel, jid: String, onVoltar: () -> Unit, onLigar: (Boolean) -> Unit) {
    val todas by vm.xmpp.mensagens.collectAsState()
    val grupos by vm.xmpp.grupos.collectAsState()
    val contactos by vm.xmpp.contactos.collectAsState()
    val grupo = grupos.firstOrNull { it.jid == jid }
    val ehGrupo = vm.xmpp.ehGrupo(jid)
    val msgs = todas.filter { it.contacto.equals(jid, ignoreCase = true) }
    var verMembros by remember { mutableStateOf(false) }
    var confirmarRemover by remember { mutableStateOf(false) }
    val contacto = contactos.firstOrNull { it.jid.equals(jid, ignoreCase = true) }
    val progresso by vm.anexo.collectAsState()
    val ctx = LocalContext.current
    val escolherFicheiro = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { vm.enviarAnexo(jid, it) } }

    // Conversa aberta e app à frente: avisa o outro de que as mensagens foram lidas
    val ciclo by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    LaunchedEffect(msgs.lastOrNull()?.id, ciclo) {
        if (!ehGrupo && ciclo.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) vm.xmpp.marcarLidas(jid)
    }

    // O grupo deixou de existir (saímos, fomos removidos ou foi apagado)
    LaunchedEffect(ehGrupo, grupo) { if (ehGrupo && grupo == null && grupos.isNotEmpty()) onVoltar() }

    ConversaUi(
        titulo = vm.xmpp.nomeDe(jid),
        subtitulo = when {
            ehGrupo -> "${(grupo?.ligados?.size ?: 0) + 1} ligados"
            contacto?.online == true -> "Ext. ${ext(jid)} · disponível"
            else -> "Ext. ${ext(jid)} · ausente"
        },
        online = if (ehGrupo) null else contacto?.online == true,
        ehGrupo = ehGrupo,
        chave = if (ehGrupo) jid else ext(jid),
        mensagens = msgs,
        nomeAutor = { vm.xmpp.nomeExt(it) },
        onVoltar = onVoltar,
        onLigar = onLigar,
        onMembros = { verMembros = true },
        onRemover = { confirmarRemover = true },
        onEnviar = { vm.enviar(jid, it) },
        onAnexar = { escolherFicheiro.launch("*/*") },
        progressoAnexo = progresso,
        onAbrirAnexo = { url ->
            runCatching {
                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    )

    if (verMembros && grupo != null) DialogoMembros(vm, grupo, onFechar = { verMembros = false }, onSaiu = onVoltar)
    if (confirmarRemover) AlertDialog(
        onDismissRequest = { confirmarRemover = false },
        icon = { Icon(Icones.Lixo, null) },
        title = { Text("Remover contacto") },
        text = { Text("Remover ${vm.xmpp.nomeDe(jid)} dos contactos? A conversa também é apagada.") },
        confirmButton = {
            Button(onClick = {
                confirmarRemover = false
                vm.removerContacto(jid) { ok -> if (ok) onVoltar() }
            }, colors = ButtonDefaults.buttonColors(containerColor = CorDesligar)) { Text("Remover") }
        },
        dismissButton = { TextButton(onClick = { confirmarRemover = false }) { Text("Cancelar") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversaUi(
    titulo: String,
    subtitulo: String,
    online: Boolean?,
    ehGrupo: Boolean,
    chave: String,
    mensagens: List<Mensagem>,
    nomeAutor: (String) -> String,
    onVoltar: () -> Unit,
    onLigar: (Boolean) -> Unit,
    onMembros: () -> Unit,
    onRemover: () -> Unit,
    onEnviar: (String) -> Unit,
    onAnexar: () -> Unit = {},
    progressoAnexo: Float? = null,
    onAbrirAnexo: (String) -> Unit = {},
) {
    var texto by rememberSaveable { mutableStateOf("") }
    val lista = rememberLazyListState()
    val hora = remember { SimpleDateFormat("HH:mm", PT) }
    LaunchedEffect(mensagens.size) { if (mensagens.isNotEmpty()) lista.animateScrollToItem(mensagens.lastIndex) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        // Barra de topo
        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onVoltar) { Icon(Icones.Voltar, "Voltar") }
                Avatar(titulo, tamanho = 40.dp, grupo = ehGrupo, online = online, chave = chave)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(titulo, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitulo, style = MaterialTheme.typography.bodySmall,
                        color = if (online == true) CorOnline else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (ehGrupo) {
                    IconButton(onClick = onMembros) { Icon(Icones.Grupo, "Membros") }
                } else {
                    IconButton(onClick = { onLigar(true) }) { Icon(Icones.Video, "Videochamada", tint = MaterialTheme.colorScheme.primary) }
                    IconButton(onClick = { onLigar(false) }) { Icon(Icones.Ligar, "Ligar", tint = CorChamar) }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icones.Menu, "Mais") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Remover contacto", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icones.Lixo, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; onRemover() }
                            )
                        }
                    }
                }
            }
        }

        // Mensagens
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = lista,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (mensagens.isEmpty()) item {
                EstadoVazio(Icones.Chat, "Começa a conversa", "As mensagens ficam guardadas neste telemóvel.")
            }
            items(mensagens) { m ->
                val minha = m.enviada
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (minha) Arrangement.End else Arrangement.Start) {
                    val forma = RoundedCornerShape(
                        topStart = 20.dp, topEnd = 20.dp,
                        bottomStart = if (minha) 20.dp else 6.dp, bottomEnd = if (minha) 6.dp else 20.dp
                    )
                    Column(
                        Modifier.widthIn(max = 290.dp).clip(forma)
                            .then(
                                if (minha) Modifier.background(Brush.linearGradient(listOf(MarcaAzul, Color(0xFF3B82F6))))
                                else Modifier.background(MaterialTheme.colorScheme.surface)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f), forma)
                            )
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                    ) {
                        val cor = if (minha) Color.White else MaterialTheme.colorScheme.onSurface
                        if (m.autor != null && !minha) {
                            Text(nomeAutor(m.autor), color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        }
                        val anexo = pt.ipca.issafone.xmpp.anexoDe(m.texto)
                        if (anexo == null) Text(m.texto, color = cor, style = MaterialTheme.typography.bodyLarge)
                        else BolhaAnexo(anexo, minha, onAbrirAnexo)
                        Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(hora.format(Date(m.hora)), color = cor.copy(alpha = .65f), fontSize = 11.sp)
                            // Vistos: ✓ enviada · ✓✓ entregue · ✓✓ verde lida (azul não se via na bolha azul)
                            if (minha && m.estado != null) Icon(
                                if (m.estado == Mensagem.ENVIADA) Icones.Enviada else Icones.Entregue,
                                contentDescription = when (m.estado) {
                                    Mensagem.LIDA -> "Lida"; Mensagem.ENTREGUE -> "Entregue"; else -> "Enviada"
                                },
                                tint = if (m.estado == Mensagem.LIDA) Color(0xFF4ADE80) else cor.copy(alpha = .65f),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }
        }

        // A enviar um anexo
        progressoAnexo?.let { p ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("A enviar anexo… ${(p * 100).toInt()}%", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(150.dp))
                LinearProgressIndicator(progress = { p }, modifier = Modifier.weight(1f).clip(RoundedCornerShape(50)))
            }
        }
        // Escrever
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
                .navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onAnexar, enabled = progressoAnexo == null) {
                Icon(Icones.Anexo, "Anexar ficheiro", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextField(
                texto, { texto = it }, placeholder = { Text("Mensagem") }, maxLines = 5,
                shape = RoundedCornerShape(26.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.weight(1f).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(26.dp))
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(52.dp).clip(CircleShape)
                    .background(if (texto.isNotBlank()) DegradeMarca else Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant)))
                    .clickable(enabled = texto.isNotBlank()) { onEnviar(texto.trim()); texto = "" },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icones.Enviar, "Enviar",
                    tint = if (texto.isNotBlank()) Color.White else MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/** Anexo dentro de um balão: a imagem (ou um cartão com o nome do ficheiro). Tocar abre-o. */
@Composable
private fun BolhaAnexo(a: pt.ipca.issafone.xmpp.Anexo, minha: Boolean, onAbrir: (String) -> Unit) {
    val cor = if (minha) Color.White else MaterialTheme.colorScheme.onSurface
    if (a.imagem) {
        coil.compose.SubcomposeAsyncImage(
            model = a.url, contentDescription = a.nome,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            loading = {
                Box(Modifier.fillMaxSize().background(cor.copy(alpha = .1f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = cor, strokeWidth = 2.dp)
                }
            },
            error = {
                Box(Modifier.fillMaxSize().background(cor.copy(alpha = .1f)), contentAlignment = Alignment.Center) {
                    Icon(Icones.Imagem, null, tint = cor.copy(alpha = .7f))
                }
            },
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp).size(width = 230.dp, height = 170.dp)
                .clip(RoundedCornerShape(14.dp)).clickable { onAbrir(a.url) }
        )
    } else {
        Row(
            Modifier.padding(top = 2.dp, bottom = 4.dp).clip(RoundedCornerShape(14.dp))
                .background(cor.copy(alpha = .12f)).clickable { onAbrir(a.url) }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(cor.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
                Icon(Icones.Ficheiro, null, tint = cor)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.widthIn(max = 180.dp)) {
                Text(a.nome, color = cor, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                Text("${a.extensao.ifBlank { "Ficheiro" }} · toca para abrir", color = cor.copy(alpha = .7f), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun DialogoMembros(vm: AppViewModel, g: Grupo, onFechar: () -> Unit, onSaiu: () -> Unit) {
    val contactos by vm.xmpp.contactos.collectAsState()
    var membros by remember { mutableStateOf<List<Membro>?>(null) }
    var recarregar by remember { mutableIntStateOf(0) }
    var adicionar by remember { mutableStateOf(false) }
    LaunchedEffect(g.jid, recarregar) { membros = if (g.dono) vm.membros(g.jid) else null }
    val nome = { j: String -> contactos.firstOrNull { it.jid == j }?.nome ?: ext(j) }

    AlertDialog(
        onDismissRequest = onFechar,
        icon = { Icon(Icones.Grupo, null) },
        title = { Text(g.nome) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                if (g.dono) {
                    val lista = membros
                    if (lista == null) Text("A carregar…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else lista.forEach { m ->
                        val ligado = ext(m.jid) in g.ligados
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(nome(m.jid), tamanho = 36.dp, online = ligado, chave = ext(m.jid))
                            Spacer(Modifier.width(12.dp))
                            Text(nome(m.jid), modifier = Modifier.weight(1f))
                            if (m.dono) Text("Dono", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            else TextButton(onClick = { vm.removerMembro(g.jid, m.jid); recarregar++ }) {
                                Text("Remover", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    val ja = membros?.map { it.jid }?.toSet() ?: emptySet()
                    val candidatos = contactos.filter { it.jid !in ja }
                    if (candidatos.isNotEmpty()) {
                        TextButton(onClick = { adicionar = !adicionar }) {
                            Icon(Icones.Adicionar, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Adicionar membro")
                        }
                        if (adicionar) candidatos.forEach { c ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                vm.adicionarMembro(g.jid, c.jid); adicionar = false; recarregar++
                            }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(c.nome, tamanho = 32.dp, chave = ext(c.jid)); Spacer(Modifier.width(12.dp)); Text(c.nome)
                            }
                        }
                    }
                } else {
                    Text("Ligados agora", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    Text("Tu")
                    g.ligados.forEach { l -> Text(contactos.firstOrNull { ext(it.jid) == l }?.nome ?: l) }
                }
            }
        },
        confirmButton = { Button(onClick = onFechar) { Text("Fechar") } },
        dismissButton = {
            if (g.dono) TextButton(onClick = { vm.apagarGrupo(g.jid); onFechar(); onSaiu() }) { Text("Apagar grupo", color = MaterialTheme.colorScheme.error) }
            else TextButton(onClick = { vm.sairGrupo(g.jid); onFechar(); onSaiu() }) { Text("Sair do grupo", color = MaterialTheme.colorScheme.error) }
        }
    )
}

/* ============================== Chamada ============================== */

@Composable
private fun PainelChamada(vm: AppViewModel, c: CallUi) {
    val contactos by vm.xmpp.contactos.collectAsState()
    val nomeDe = { num: String -> contactos.firstOrNull { ext(it.jid) == num }?.nome ?: num }
    var escolher by remember { mutableStateOf<String?>(null) }   // "transferir" ou "adicionar"
    var mute by remember { mutableStateOf(false) }
    var teclas by remember { mutableStateOf(false) }
    val camaraLigada by vm.sip.camaraLigada.collectAsState()
    val altaVoz by vm.sip.altaVoz.collectAsState()
    var segundos by remember { mutableIntStateOf(0) }
    LaunchedEffect(c.estado == "Em chamada" || c.conferencia) {
        segundos = 0
        while (c.estado == "Em chamada" || c.conferencia) { kotlinx.coroutines.delay(1000); segundos++ }
    }
    BackHandler(enabled = true) { /* durante a chamada o "voltar" não fecha o ecrã */ }

    ChamadaUi(
        c = c,
        nome = if (c.conferencia) "Conferência" else c.nome.ifBlank { nomeDe(c.remoto) },
        nomeEspera = c.emEspera.takeIf { it.isNotBlank() }?.let(nomeDe),
        segundos = segundos, mute = mute, altaVoz = altaVoz, camaraLigada = camaraLigada, teclas = teclas,
        onAtender = { vm.sip.atender(it) },
        onDesligar = { vm.sip.desligar() },
        onMute = { mute = vm.sip.alternarMute() },
        onTeclas = { teclas = !teclas },
        onTecla = { vm.sip.dtmf(it) },
        onAltaVoz = { vm.sip.alternarAltaVoz() },
        onVideo = { if (c.comVideo) vm.sip.alternarCamara() else vm.sip.alternarVideo() },
        onTrocarCamara = { vm.sip.trocarCamara() },
        onTransferir = { escolher = "transferir" },
        onAdicionar = { escolher = "adicionar" },
        onAlternar = { vm.sip.alternar() },
        onJuntar = { vm.sip.juntar() },
        onLigarOsDois = { vm.sip.ligarOsDois() },
        videoRemoto = {
            AndroidView(
                factory = { TextureView(it).also(vm.sip::definirVistaRemota) },
                modifier = Modifier.fillMaxSize(),
                onRelease = { vm.sip.definirVistaRemota(null) }
            )
        },
        videoLocal = {
            AndroidView(
                factory = { CaptureTextureView(it).also(vm.sip::definirVistaLocal) },
                modifier = Modifier.fillMaxSize(),
                onRelease = { vm.sip.definirVistaLocal(null) }
            )
        }
    )

    escolher?.let { acao ->
        EscolherExtensao(
            titulo = if (acao == "transferir") "Transferir para…" else "Ligar a mais alguém",
            contactos = contactos,
            aoFechar = { escolher = null },
            aoEscolher = { e ->
                escolher = null
                if (acao == "transferir") vm.sip.transferir(e) else vm.sip.adicionar(e)
            }
        )
    }
}

@Composable
internal fun ChamadaUi(
    c: CallUi,
    nome: String,
    nomeEspera: String?,
    segundos: Int,
    mute: Boolean,
    altaVoz: Boolean,
    camaraLigada: Boolean,
    teclas: Boolean,
    onAtender: (Boolean) -> Unit,
    onDesligar: () -> Unit,
    onMute: () -> Unit,
    onTeclas: () -> Unit,
    onTecla: (Char) -> Unit,
    onAltaVoz: () -> Unit,
    onVideo: () -> Unit,
    onTrocarCamara: () -> Unit,
    onTransferir: () -> Unit,
    onAdicionar: () -> Unit,
    onAlternar: () -> Unit,
    onJuntar: () -> Unit,
    onLigarOsDois: () -> Unit,
    videoRemoto: @Composable () -> Unit = {},
    videoLocal: @Composable () -> Unit = {},
) {
    val emCurso = c.estado == "Em chamada" || c.conferencia
    val tempo = "%02d:%02d".format(segundos / 60, segundos % 60)

    Box(Modifier.fillMaxSize().background(DegradeChamada)) {
        if (c.comVideo && !c.aTocar) {
            videoRemoto()
            // Escurecer em baixo, para os botões se lerem por cima do vídeo
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = .35f), .25f to Color.Transparent, .6f to Color.Transparent, 1f to Color.Black.copy(alpha = .75f))))
            if (camaraLigada) {
                Box(
                    Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp)
                        .size(width = 112.dp, height = 156.dp).clip(RoundedCornerShape(18.dp))
                        .border(2.dp, Color.White.copy(alpha = .6f), RoundedCornerShape(18.dp))
                ) { videoLocal() }
            }
        }

        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Cifra (SRTP) sempre ativa
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .12f)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icones.Cadeado, null, tint = Color.White.copy(alpha = .85f), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (c.comVideo) "Videochamada cifrada" else "Chamada cifrada", color = Color.White.copy(alpha = .85f), fontSize = 12.sp)
            }

            if (!(c.comVideo && !c.aTocar)) {
                Spacer(Modifier.weight(.6f))
                // Avatar com anéis (a pulsar visualmente quando está a tocar)
                Box(contentAlignment = Alignment.Center) {
                    if (c.aTocar || c.estado == "A chamar…") {
                        Box(Modifier.size(196.dp).clip(CircleShape).background(Color.White.copy(alpha = .05f)))
                        Box(Modifier.size(164.dp).clip(CircleShape).background(Color.White.copy(alpha = .08f)))
                    }
                    if (c.conferencia) {
                        Box(Modifier.size(132.dp).clip(CircleShape).background(DegradeMarca), contentAlignment = Alignment.Center) {
                            Icon(Icones.Grupo, null, tint = Color.White, modifier = Modifier.size(64.dp))
                        }
                    } else Avatar(nome, tamanho = 132.dp, chave = c.remoto)
                }
                Spacer(Modifier.height(24.dp))
            } else Spacer(Modifier.weight(1f))

            Text(nome, color = Color.White, fontSize = if (c.comVideo && !c.aTocar) 22.sp else 32.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2)
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    c.conferencia -> "${c.participantes} pessoas · $tempo"
                    emCurso -> tempo
                    c.aTocar && c.ofereceVideo -> "Videochamada a chegar…"
                    c.aTocar -> "Chamada a chegar…"
                    else -> c.estado
                } + if (!c.conferencia && nome != c.remoto && c.remoto.isNotBlank()) "  ·  Ext. ${c.remoto}" else "",
                color = Color.White.copy(alpha = .75f), fontSize = 16.sp
            )
            nomeEspera?.takeIf { !c.conferencia }?.let {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(CorAviso.copy(alpha = .22f)).padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(CorAviso))
                    Spacer(Modifier.width(8.dp))
                    Text("Em espera: $it", color = Color.White, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.weight(1f))

            if (c.aTocar) {
                // Chamada a chegar
                Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    BotaoCircular(Icones.Desligar, "Recusar", onDesligar, fundo = CorDesligar, tamanho = 76.dp)
                    if (c.ofereceVideo) BotaoCircular(Icones.Video, "Com vídeo", { onAtender(true) }, fundo = MarcaAzul, tamanho = 76.dp)
                    BotaoCircular(Icones.Ligar, if (c.ofereceVideo) "Só áudio" else "Atender", { onAtender(false) }, fundo = CorChamar, tamanho = 76.dp)
                }
            } else {
                if (teclas) {
                    TecladoDtmf(onTecla)
                    Spacer(Modifier.height(16.dp))
                }
                val linhas = buildList {
                    add(listOf(
                        Triple(if (mute) Icones.MicroDesligado else Icones.Micro, if (mute) "Sem som" else "Silenciar", mute) to onMute,
                        Triple(Icones.Teclado, "Teclado", teclas) to onTeclas,
                        Triple(Icones.AltaVoz, "Alta voz", altaVoz) to onAltaVoz,
                    ))
                    if (emCurso) add(listOf(
                        if (c.comVideo) Triple(if (camaraLigada) Icones.Video else Icones.VideoDesligado, "Câmara", false) to onVideo
                        else Triple(Icones.Video, "Vídeo", false) to onVideo,
                        if (c.comVideo) Triple(Icones.TrocarCamara, "Trocar", false) to onTrocarCamara
                        else Triple(Icones.Transferir, "Transferir", false) to onTransferir,
                        Triple(Icones.Adicionar, "Adicionar", false) to onAdicionar,
                    ))
                    if (c.chamadas >= 2 && !c.conferencia) add(listOf(
                        Triple(Icones.Alternar, "Alternar", false) to onAlternar,
                        Triple(Icones.Juntar, "Juntar", false) to onJuntar,
                        Triple(Icones.Transferir, "Ligar os 2", false) to onLigarOsDois,
                    ))
                }
                linhas.forEach { linha ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        linha.forEach { (info, acao) -> BotaoCircular(info.first, info.second, acao, ativo = info.third) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                BotaoCircular(Icones.Desligar, "Desligar", onDesligar, fundo = CorDesligar, tamanho = 76.dp)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun TecladoDtmf(onTecla: (Char) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        listOf("123", "456", "789", "*0#").forEach { linha ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(4.dp)) {
                linha.forEach { t ->
                    Box(
                        Modifier.size(58.dp).clip(CircleShape).background(Color.White.copy(alpha = .12f)).clickable { onTecla(t) },
                        contentAlignment = Alignment.Center
                    ) { Text(t.toString(), color = Color.White, fontSize = 24.sp) }
                }
            }
        }
    }
}

/** Escolher uma extensão: escrever o número ou tocar num contacto. */
@Composable
private fun EscolherExtensao(
    titulo: String,
    contactos: List<Contacto>,
    aoFechar: () -> Unit,
    aoEscolher: (String) -> Unit
) {
    var numero by remember { mutableStateOf("") }
    val lista = contactos.filter {
        numero.isBlank() || simples(it.nome).contains(simples(numero)) || ext(it.jid).startsWith(numero)
    }
    AlertDialog(
        onDismissRequest = aoFechar,
        title = { Text(titulo) },
        text = {
            Column {
                CampoPesquisa(numero, { numero = it }, "Extensão ou nome")
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(lista, key = { it.jid }) { ct ->
                        val e = ext(ct.jid)
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { aoEscolher(e) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(ct.nome, tamanho = 38.dp, online = ct.online, chave = e)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(ct.nome, style = MaterialTheme.typography.titleSmall)
                                Text("Ext. $e" + if (ct.online) " · disponível" else "",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (numero.isNotBlank()) aoEscolher(numero.trim()) }, enabled = numero.any { it.isDigit() }) {
                Text("Ligar")
            }
        },
        dismissButton = { TextButton(onClick = aoFechar) { Text("Cancelar") } }
    )
}
