package pt.ipca.issafone.ui

import android.view.TextureView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.linphone.mediastream.video.capture.CaptureTextureView
import pt.ipca.issafone.AppViewModel
import pt.ipca.issafone.sip.CallUi
import pt.ipca.issafone.xmpp.Contacto
import pt.ipca.issafone.xmpp.Grupo
import pt.ipca.issafone.xmpp.Membro
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Verde = Color(0xFF2E7D32)
private val Vermelho = Color(0xFFC62828)
private val Azul = Color(0xFF2F5BFF)

private fun iniciais(nome: String): String {
    val p = nome.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (p.isEmpty()) return "?"
    return (if (p.size > 1 && p[0].any { !it.isDigit() }) "${p[0][0]}${p.last()[0]}" else p[0].take(2)).uppercase()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EcraPrincipal(vm: AppViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    var conversaCom by remember { mutableStateOf<String?>(null) }
    val chamada by vm.sip.chamada.collectAsState()
    val registo by vm.sip.registo.collectAsState()
    val estadoChat by vm.xmpp.estado.collectAsState()
    val atualizacao by vm.estadoAtualizacao.collectAsState()
    val aviso by vm.xmpp.aviso.collectAsState()
    val ctx = LocalContext.current

    // Tocar numa notificação de mensagem abre logo essa conversa
    val pedido by vm.abrirConversa.collectAsState()
    LaunchedEffect(pedido) {
        pedido?.let { tab = 3; conversaCom = it; vm.pedirConversa(null) }
    }
    // Sempre que a app volta ao ecrã, procura atualizações (no máximo de 10 em 10 min)
    val escopo = rememberCoroutineScope()
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        val c = vm.sip.chamada.value
        if (!c.ativa && !c.aTocar) escopo.launch { pt.ipca.issafone.atualizacao.Atualizador.verificarSeDevido(ctx) }
        onPauseOrDispose { }
    }
    LaunchedEffect(aviso) {
        aviso?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.xmpp.limparAviso() }
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("IssaFone", style = MaterialTheme.typography.titleLarge)
                    Text(" ${pt.ipca.issafone.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray, modifier = Modifier.weight(1f))
                    val tema by vm.tema.collectAsState()
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { menu = true }) { Text("⋮", fontSize = 22.sp) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Procurar atualizações") }, onClick = {
                                menu = false
                                escopo.launch { pt.ipca.issafone.atualizacao.Atualizador.verificarEInstalar(ctx, manual = true) }
                            })
                            DropdownMenuItem(text = { Text("Tema: ${tema.rotulo}") }, onClick = { vm.mudarTema() })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Sair", color = Vermelho) }, onClick = { menu = false; vm.sair() })
                        }
                    }
                }
                Text("Telefone: $registo", style = MaterialTheme.typography.bodySmall)
                Text("Chat: $estadoChat", style = MaterialTheme.typography.bodySmall)
                atualizacao?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        bottomBar = {
            NavigationBar {
                val perdidas by vm.sip.registoChamadas.perdidas.collectAsState()
                NavigationBarItem(tab == 0, { tab = 0; conversaCom = null }, icon = { Text("⌨") }, label = { Text("Teclado") })
                NavigationBarItem(tab == 1, { tab = 1 }, icon = { Text("👥") }, label = { Text("Contactos") })
                NavigationBarItem(tab == 2, { tab = 2; vm.sip.registoChamadas.marcarVistas() },
                    icon = { BadgedBox(badge = { if (perdidas > 0) Badge { Text("$perdidas") } }) { Text("📞") } },
                    label = { Text("Chamadas") })
                NavigationBarItem(tab == 3, { tab = 3 }, icon = { Text("💬") }, label = { Text("Chat") })
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                AvisosSegundoPlano()
                Box(Modifier.weight(1f)) {
                    when {
                        tab == 0 -> Teclado(onChamar = { n, video -> vm.sip.chamar(n, video) })
                        tab == 1 -> Agenda(vm, onMensagem = { conversaCom = it; tab = 3 })
                        tab == 2 -> ListaChamadas(vm)
                        conversaCom != null -> Conversa(vm, conversaCom!!,
                            onVoltar = { conversaCom = null },
                            onLigar = { jid, video -> vm.sip.chamar(jid.substringBefore("@"), video); tab = 0 })
                        else -> ListaChat(vm, onAbrir = { conversaCom = it })
                    }
                }
            }
        }
    }
    // O ecrã de chamada fica por cima de tudo (incluindo as barras)
    if (chamada.ativa || chamada.aTocar) PainelChamada(vm, chamada)
    }
}

/* ============================== Teclado ============================== */

@Composable
private fun Teclado(onChamar: (String, Boolean) -> Unit) {
    var numero by remember { mutableStateOf("") }
    val teclas = listOf("123", "456", "789", "*0#")
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(numero.ifEmpty { "Extensão ou número" }, fontSize = 30.sp,
            color = if (numero.isEmpty()) Color.Gray else Color.Unspecified,
            modifier = Modifier.padding(bottom = 24.dp))
        teclas.forEach { linha ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(6.dp)) {
                linha.forEach { c ->
                    Box(
                        Modifier.size(72.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { numero += c },
                        contentAlignment = Alignment.Center
                    ) { Text(c.toString(), fontSize = 26.sp) }
                }
            }
        }
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { numero = numero.dropLast(1) }) { Text("Apagar") }
            Button(
                onClick = { onChamar(numero, false) }, enabled = numero.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Verde)
            ) { Text("Ligar") }
            Button(
                onClick = { onChamar(numero, true) }, enabled = numero.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Azul)
            ) { Text("Vídeo") }
        }
    }
}

/* ============================== Agenda (contactos para ligar) ============================== */

@Composable
private fun Agenda(vm: AppViewModel, onMensagem: (String) -> Unit) {
    val contactos by vm.xmpp.contactos.collectAsState()
    var filtro by remember { mutableStateOf("") }
    fun simples(t: String) = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").lowercase()
    val lista = contactos
        .filter { c -> filtro.isBlank() || simples(c.nome + " " + c.jid.substringBefore("@")).contains(simples(filtro.trim())) }
        .sortedBy { simples(it.nome) }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            filtro, { filtro = it }, placeholder = { Text("Procurar nome ou extensão") },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(12.dp)
        )
        if (lista.isEmpty()) {
            Text(if (filtro.isBlank()) "Sem contactos." else "Nenhum contacto encontrado.",
                color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(lista, key = { it.jid }) { c ->
                val num = c.jid.substringBefore("@")
                Row(
                    Modifier.fillMaxWidth().clickable { vm.sip.chamar(num, false) }.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(42.dp)) {
                        Box(Modifier.matchParentSize().clip(CircleShape).background(Azul), contentAlignment = Alignment.Center) {
                            Text(iniciais(c.nome), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Box(Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape)
                            .background(if (c.online) Color(0xFF43A047) else Color.Gray))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.nome, style = MaterialTheme.typography.titleMedium)
                        Text("$num · ${if (c.online) "Disponível" else "Ausente"}",
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    TextButton(onClick = { onMensagem(c.jid) }, contentPadding = PaddingValues(8.dp)) { Text("💬", fontSize = 18.sp) }
                    TextButton(onClick = { vm.sip.chamar(num, false) }, contentPadding = PaddingValues(8.dp)) { Text("📞", fontSize = 18.sp) }
                    TextButton(onClick = { vm.sip.chamar(num, true) }, contentPadding = PaddingValues(8.dp)) { Text("🎥", fontSize = 18.sp) }
                }
                HorizontalDivider()
            }
        }
    }
}

/* ============================== Registo de chamadas ============================== */

@Composable
private fun ListaChamadas(vm: AppViewModel) {
    val lista by vm.sip.registoChamadas.lista.collectAsState()
    val contactos by vm.xmpp.contactos.collectAsState()
    var limpar by remember { mutableStateOf(false) }
    // Enquanto o registo está aberto, as perdidas contam como vistas
    LaunchedEffect(lista.size) { vm.sip.registoChamadas.marcarVistas() }

    val hoje = remember { java.util.Calendar.getInstance() }
    val fmtH = remember { SimpleDateFormat("HH:mm", Locale("pt", "PT")) }
    val fmtD = remember { SimpleDateFormat("dd/MM HH:mm", Locale("pt", "PT")) }
    fun quando(h: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = h }
        val mesmoDia = c.get(java.util.Calendar.YEAR) == hoje.get(java.util.Calendar.YEAR) &&
            c.get(java.util.Calendar.DAY_OF_YEAR) == hoje.get(java.util.Calendar.DAY_OF_YEAR)
        return if (mesmoDia) "hoje ${fmtH.format(Date(h))}" else fmtD.format(Date(h))
    }
    fun duracao(s: Int) = if (s >= 3600) "%dh %02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("CHAMADAS RECENTES", style = MaterialTheme.typography.labelMedium, color = Color.Gray, modifier = Modifier.weight(1f))
            if (lista.isNotEmpty()) TextButton(onClick = { limpar = true }) { Text("Limpar") }
        }
        if (lista.isEmpty()) {
            Text("Ainda não há chamadas.", color = Color.Gray, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(32.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(lista) { c ->
                val nome = contactos.firstOrNull { it.jid.substringBefore("@") == c.numero }?.nome ?: c.nome
                val (seta, cor, rotulo) = when (c.tipo) {
                    "feita" -> Triple("↗", Azul, "Feita")
                    "recebida" -> Triple("↙", Verde, "Recebida")
                    "recusada" -> Triple("↙", Vermelho, "Recusada")
                    else -> Triple("↙", Vermelho, "Perdida")
                }
                Row(
                    Modifier.fillMaxWidth().clickable { vm.sip.chamar(c.numero, false) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(seta, color = cor, fontSize = 22.sp, modifier = Modifier.width(30.dp))
                    Column(Modifier.weight(1f)) {
                        Text(nome, style = MaterialTheme.typography.titleMedium,
                            color = if (c.tipo == "perdida") Vermelho else MaterialTheme.colorScheme.onSurface)
                        Text(
                            listOfNotNull(
                                rotulo + if (c.video) " · vídeo" else "",
                                c.numero.takeIf { it != nome },
                                quando(c.hora),
                                c.duracao.takeIf { it > 0 }?.let { duracao(it) }
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray
                        )
                    }
                    TextButton(onClick = { vm.sip.chamar(c.numero, true) }) { Text("🎥") }
                }
                HorizontalDivider()
            }
        }
    }

    if (limpar) AlertDialog(
        onDismissRequest = { limpar = false },
        title = { Text("Limpar registo") },
        text = { Text("Apagar todas as chamadas do registo deste telemóvel?") },
        confirmButton = { Button(onClick = { vm.sip.registoChamadas.limpar(); limpar = false }) { Text("Limpar") } },
        dismissButton = { TextButton(onClick = { limpar = false }) { Text("Cancelar") } }
    )
}

/* ============================== Chamada ============================== */

@Composable
private fun PainelChamada(vm: AppViewModel, c: CallUi) {
    val contactos by vm.xmpp.contactos.collectAsState()
    val nome = c.nome.ifBlank { contactos.firstOrNull { it.jid.substringBefore("@") == c.remoto }?.nome ?: c.remoto }
    var mute by remember { mutableStateOf(false) }
    var teclas by remember { mutableStateOf(false) }
    val camaraLigada by vm.sip.camaraLigada.collectAsState()
    var segundos by remember { mutableIntStateOf(0) }
    LaunchedEffect(c.estado == "Em chamada") {
        segundos = 0
        while (c.estado == "Em chamada") { kotlinx.coroutines.delay(1000); segundos++ }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF111318))) {
        if (c.comVideo) {
            // Vídeo do outro lado a ocupar o ecrã
            AndroidView(
                factory = { TextureView(it).also(vm.sip::definirVistaRemota) },
                modifier = Modifier.fillMaxSize(),
                onRelease = { vm.sip.definirVistaRemota(null) }
            )
            // Pré-visualização da nossa câmara
            if (camaraLigada) {
                AndroidView(
                    factory = { CaptureTextureView(it).also(vm.sip::definirVistaLocal) },
                    modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp)
                        .size(width = 110.dp, height = 150.dp).clip(RoundedCornerShape(14.dp)),
                    onRelease = { vm.sip.definirVistaLocal(null) }
                )
            }
        }

        Column(
            Modifier.fillMaxSize().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (c.comVideo) Arrangement.Bottom else Arrangement.Center
        ) {
            if (!c.comVideo) {
                Box(
                    Modifier.size(96.dp).clip(CircleShape).background(Azul),
                    contentAlignment = Alignment.Center
                ) { Text(iniciais(nome), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(12.dp))
            }
            Text(nome, color = Color.White, fontSize = if (c.comVideo) 20.sp else 30.sp, textAlign = TextAlign.Center)
            if (nome != c.remoto && !c.comVideo) Text(c.remoto, color = Color.LightGray)
            Text(
                if (c.estado == "Em chamada") "%02d:%02d".format(segundos / 60, segundos % 60) else c.estado,
                color = Color.LightGray, modifier = Modifier.padding(6.dp)
            )
            if (teclas && c.ativa) TecladoDtmf { vm.sip.dtmf(it) }
            Spacer(Modifier.height(20.dp))

            if (c.aTocar) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (c.ofereceVideo) {
                        BotaoRedondo("Só áudio", Verde) { vm.sip.atender(false) }
                        BotaoRedondo("Com vídeo", Verde) { vm.sip.atender(true) }
                    } else {
                        BotaoRedondo("Atender", Verde) { vm.sip.atender(false) }
                    }
                    BotaoRedondo("Recusar", Vermelho) { vm.sip.desligar() }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BotaoRedondo(if (mute) "Ativar micro" else "Silenciar", Color(0x33FFFFFF)) { mute = vm.sip.alternarMute() }
                    if (c.comVideo) {
                        BotaoRedondo(if (camaraLigada) "Câmara off" else "Câmara on", Color(0x33FFFFFF)) { vm.sip.alternarCamara() }
                        BotaoRedondo("Trocar", Color(0x33FFFFFF)) { vm.sip.trocarCamara() }
                    } else {
                        BotaoRedondo("Teclas", Color(0x33FFFFFF)) { teclas = !teclas }
                        BotaoRedondo("Vídeo", Color(0x33FFFFFF)) { vm.sip.alternarVideo() }
                    }
                }
                Spacer(Modifier.height(14.dp))
                BotaoRedondo("Desligar", Vermelho) { vm.sip.desligar() }
            }
        }
    }
}

@Composable
private fun BotaoRedondo(texto: String, cor: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = cor, contentColor = Color.White),
        shape = RoundedCornerShape(28.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) { Text(texto, fontSize = 14.sp) }
}

@Composable
private fun TecladoDtmf(onTecla: (Char) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        listOf("123", "456", "789", "*0#").forEach { linha ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(4.dp)) {
                linha.forEach { t ->
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(Color(0x22FFFFFF)).clickable { onTecla(t) },
                        contentAlignment = Alignment.Center
                    ) { Text(t.toString(), color = Color.White, fontSize = 22.sp) }
                }
            }
        }
    }
}

/* ============================== Lista: grupos e contactos ============================== */

@Composable
private fun ListaChat(vm: AppViewModel, onAbrir: (String) -> Unit) {
    val contactos by vm.xmpp.contactos.collectAsState()
    val grupos by vm.xmpp.grupos.collectAsState()
    val mensagens by vm.xmpp.mensagens.collectAsState()
    var novo by remember { mutableStateOf("") }
    var criar by remember { mutableStateOf(false) }

    fun ultima(jid: String) = mensagens.lastOrNull { it.contacto == jid }?.let {
        (if (it.enviada) "Tu: " else it.autor?.let { a -> "${vm.xmpp.nomeExt(a)}: " } ?: "") + it.texto
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                OutlinedTextField(novo, { novo = it.trim() }, label = { Text("Adicionar colega (ex.: 102)") },
                    singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(onClick = { vm.adicionarContacto(novo); novo = "" }, enabled = novo.isNotBlank()) { Text("+") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("GRUPOS", style = MaterialTheme.typography.labelMedium, color = Color.Gray, modifier = Modifier.weight(1f))
                TextButton(onClick = { criar = true }, enabled = contactos.isNotEmpty()) { Text("+ Novo grupo") }
            }
        }
        if (grupos.isEmpty()) item {
            Text("Ainda não estás em nenhum grupo.", color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
        }
        items(grupos, key = { it.jid }) { g ->
            Linha(g.nome, ultima(g.jid) ?: "${g.ligados.size + 1} ligado(s)", grupo = true, online = true) { onAbrir(g.jid) }
        }
        item {
            Text("CONTACTOS", style = MaterialTheme.typography.labelMedium, color = Color.Gray,
                modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
        }
        if (contactos.isEmpty()) item {
            Text("Sem contactos. No Openfire, ativa a partilha da lista do grupo IssaFone.",
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp), color = Color.Gray)
        }
        items(contactos, key = { it.jid }) { c ->
            val num = c.jid.substringBefore("@")
            val estado = ultima(c.jid) ?: if (c.online) "Disponível" else "Ausente"
            Linha(c.nome, if (c.nome != num) "$num · $estado" else estado, online = c.online) { onAbrir(c.jid) }
        }
    }

    if (criar) DialogoNovoGrupo(contactos, onCancelar = { criar = false }) { nome, membros ->
        criar = false
        vm.criarGrupo(nome, membros) { jid -> jid?.let(onAbrir) }
    }
}

@Composable
private fun Linha(nome: String, sub: String, grupo: Boolean = false, online: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(42.dp)) {
            Box(
                Modifier.matchParentSize().clip(if (grupo) RoundedCornerShape(12.dp) else CircleShape).background(Azul),
                contentAlignment = Alignment.Center
            ) { Text(if (grupo) "#" else iniciais(nome), color = Color.White, fontWeight = FontWeight.Bold) }
            if (!grupo) Box(
                Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape)
                    .background(if (online) Color(0xFF43A047) else Color.Gray)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(nome, style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1)
        }
    }
    HorizontalDivider()
}

@Composable
private fun DialogoNovoGrupo(contactos: List<Contacto>, onCancelar: () -> Unit, onCriar: (String, List<String>) -> Unit) {
    var nome by remember { mutableStateOf("") }
    val escolhidos = remember { mutableStateListOf<String>() }
    var erro by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onCancelar,
        title = { Text("Novo grupo") },
        text = {
            Column {
                OutlinedTextField(nome, { nome = it.take(40); erro = null }, label = { Text("Nome do grupo") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text("Membros", style = MaterialTheme.typography.labelLarge)
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    contactos.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (c.jid in escolhidos) escolhidos.remove(c.jid) else escolhidos.add(c.jid); erro = null
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(c.jid in escolhidos, onCheckedChange = {
                                if (it) escolhidos.add(c.jid) else escolhidos.remove(c.jid); erro = null
                            })
                            Text(c.nome)
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
private fun Conversa(vm: AppViewModel, jid: String, onVoltar: () -> Unit, onLigar: (String, Boolean) -> Unit) {
    val todas by vm.xmpp.mensagens.collectAsState()
    val grupos by vm.xmpp.grupos.collectAsState()
    val grupo = grupos.firstOrNull { it.jid == jid }
    val ehGrupo = vm.xmpp.ehGrupo(jid)
    val msgs = todas.filter { it.contacto.equals(jid, ignoreCase = true) }
    var texto by remember { mutableStateOf("") }
    var verMembros by remember { mutableStateOf(false) }
    var confirmarRemover by remember { mutableStateOf(false) }
    val lista = rememberLazyListState()
    val hora = remember { SimpleDateFormat("HH:mm", Locale("pt", "PT")) }
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) lista.animateScrollToItem(msgs.lastIndex) }

    // O grupo deixou de existir (saímos, fomos removidos ou foi apagado)
    LaunchedEffect(ehGrupo, grupo) { if (ehGrupo && grupo == null && grupos.isNotEmpty()) onVoltar() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onVoltar) { Text("‹") }
            val titulo = vm.xmpp.nomeDe(jid).let { n -> if (ehGrupo || n == jid.substringBefore("@")) n else "$n (${jid.substringBefore("@")})" }
            Text(titulo, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (ehGrupo) {
                TextButton(onClick = { verMembros = true }) { Text("Membros") }
            } else {
                Button(onClick = { onLigar(jid, false) }, colors = ButtonDefaults.buttonColors(containerColor = Verde)) { Text("Ligar") }
                Spacer(Modifier.width(6.dp))
                Button(onClick = { onLigar(jid, true) }, colors = ButtonDefaults.buttonColors(containerColor = Azul)) { Text("Vídeo") }
                var menu by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { menu = true }) { Text("⋮", fontSize = 20.sp) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Remover contacto", color = Vermelho) },
                            onClick = { menu = false; confirmarRemover = true }
                        )
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = lista) {
            items(msgs) { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = if (m.enviada) Arrangement.End else Arrangement.Start) {
                    Column(
                        Modifier.clip(RoundedCornerShape(14.dp))
                            .background(if (m.enviada) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .widthIn(max = 280.dp)
                    ) {
                        val cor = if (m.enviada) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        if (m.autor != null && !m.enviada) {
                            Text(vm.xmpp.nomeExt(m.autor), color = Azul, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Text(m.texto, color = cor)
                        Text(hora.format(Date(m.hora)), color = cor.copy(alpha = .7f), fontSize = 11.sp)
                    }
                }
            }
        }
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(texto, { texto = it }, placeholder = { Text("Mensagem") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.enviar(jid, texto); texto = "" }, enabled = texto.isNotBlank()) { Text("Enviar") }
        }
    }

    if (verMembros && grupo != null) DialogoMembros(vm, grupo, onFechar = { verMembros = false }, onSaiu = onVoltar)
    if (confirmarRemover) AlertDialog(
        onDismissRequest = { confirmarRemover = false },
        title = { Text("Remover contacto") },
        text = { Text("Remover ${vm.xmpp.nomeDe(jid)} dos contactos? A conversa também é apagada.") },
        confirmButton = {
            Button(onClick = {
                confirmarRemover = false
                vm.removerContacto(jid) { ok -> if (ok) onVoltar() }
            }, colors = ButtonDefaults.buttonColors(containerColor = Vermelho)) { Text("Remover") }
        },
        dismissButton = { TextButton(onClick = { confirmarRemover = false }) { Text("Cancelar") } }
    )
}

@Composable
private fun DialogoMembros(vm: AppViewModel, g: Grupo, onFechar: () -> Unit, onSaiu: () -> Unit) {
    val contactos by vm.xmpp.contactos.collectAsState()
    var membros by remember { mutableStateOf<List<Membro>?>(null) }
    var recarregar by remember { mutableIntStateOf(0) }
    var adicionar by remember { mutableStateOf(false) }
    LaunchedEffect(g.jid, recarregar) { membros = if (g.dono) vm.membros(g.jid) else null }
    val nome = { j: String -> contactos.firstOrNull { it.jid == j }?.nome ?: j.substringBefore("@") }

    AlertDialog(
        onDismissRequest = onFechar,
        title = { Text("Membros de ${g.nome}") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                if (g.dono) {
                    val lista = membros
                    if (lista == null) Text("A carregar…", color = Color.Gray)
                    else lista.forEach { m ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            val ligado = m.jid.substringBefore("@") in g.ligados
                            Text(nome(m.jid) + if (ligado) " ●" else "", modifier = Modifier.weight(1f))
                            if (m.dono) Text("Dono", color = Color.Gray, fontSize = 12.sp)
                            else TextButton(onClick = { vm.removerMembro(g.jid, m.jid); recarregar++ }) {
                                Text("Remover", color = Vermelho)
                            }
                        }
                    }
                    val ja = membros?.map { it.jid }?.toSet() ?: emptySet()
                    val candidatos = contactos.filter { it.jid !in ja }
                    if (candidatos.isNotEmpty()) {
                        TextButton(onClick = { adicionar = !adicionar }) { Text("+ Adicionar membro") }
                        if (adicionar) candidatos.forEach { c ->
                            Text(c.nome, modifier = Modifier.fillMaxWidth().clickable {
                                vm.adicionarMembro(g.jid, c.jid); adicionar = false; recarregar++
                            }.padding(10.dp))
                        }
                    }
                } else {
                    Text("Ligados agora:", style = MaterialTheme.typography.labelLarge)
                    Text("Tu")
                    g.ligados.forEach { l -> Text(contactos.firstOrNull { it.jid.substringBefore("@") == l }?.nome ?: l) }
                }
            }
        },
        confirmButton = { Button(onClick = onFechar) { Text("Fechar") } },
        dismissButton = {
            if (g.dono) TextButton(onClick = { vm.apagarGrupo(g.jid); onFechar(); onSaiu() }) { Text("Apagar grupo", color = Vermelho) }
            else TextButton(onClick = { vm.sairGrupo(g.jid); onFechar(); onSaiu() }) { Text("Sair do grupo", color = Vermelho) }
        }
    )
}
