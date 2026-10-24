package pt.ipca.issafone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pt.ipca.issafone.AppViewModel
import pt.ipca.issafone.sip.CallUi
import pt.ipca.issafone.xmpp.Contacto

@Composable
fun EcraPrincipal(vm: AppViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    var conversaCom by remember { mutableStateOf<String?>(null) }
    val chamada by vm.sip.chamada.collectAsState()
    val registo by vm.sip.registo.collectAsState()
    val estadoChat by vm.xmpp.estado.collectAsState()
    val atualizacao by vm.estadoAtualizacao.collectAsState()

    // Tocar numa notificação de mensagem abre logo essa conversa
    val pedido by vm.abrirConversa.collectAsState()
    LaunchedEffect(pedido) {
        pedido?.let { tab = 1; conversaCom = it; vm.pedirConversa(null) }
    }

    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("IssaFone", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.sair() }) { Text("Sair") }
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
                NavigationBarItem(tab == 0, { tab = 0; conversaCom = null }, icon = { Text("📞") }, label = { Text("Teclado") })
                NavigationBarItem(tab == 1, { tab = 1 }, icon = { Text("💬") }, label = { Text("Chat") })
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                AvisosSegundoPlano()
                Box(Modifier.weight(1f)) {
                    when {
                        tab == 0 -> Teclado(onChamar = { vm.sip.chamar(it) })
                        conversaCom != null -> Conversa(vm, conversaCom!!,
                            onVoltar = { conversaCom = null },
                            onLigar = { vm.sip.chamar(it.substringBefore("@")); tab = 0 })
                        else -> ListaContactos(vm, onAbrir = { conversaCom = it })
                    }
                }
            }
            if (chamada.ativa || chamada.aTocar) {
                PainelChamada(chamada,
                    onAtender = { vm.sip.atender() },
                    onDesligar = { vm.sip.desligar() },
                    onMute = { vm.sip.alternarMute() },
                    onDtmf = { vm.sip.dtmf(it) })
            }
        }
    }
}

@Composable
private fun Teclado(onChamar: (String) -> Unit) {
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
        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = { numero = numero.dropLast(1) }) { Text("Apagar") }
            Button(
                onClick = { onChamar(numero) },
                enabled = numero.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
            ) { Text("Ligar") }
        }
    }
}

@Composable
private fun PainelChamada(
    c: CallUi, onAtender: () -> Unit, onDesligar: () -> Unit,
    onMute: () -> Boolean, onDtmf: (Char) -> Unit
) {
    var mute by remember { mutableStateOf(false) }
    var dtmf by remember { mutableStateOf("") }
    Surface(Modifier.fillMaxSize(), color = Color(0xEE1B1B1F)) {
        Column(
            Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(c.remoto, color = Color.White, fontSize = 34.sp)
            Text(c.estado, color = Color.LightGray, modifier = Modifier.padding(8.dp))
            if (c.ativa) {
                OutlinedTextField(dtmf, {
                    if (it.length > dtmf.length) onDtmf(it.last())
                    dtmf = it
                }, label = { Text("Teclas (DTMF)") }, singleLine = true)
            }
            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (c.aTocar) {
                    Button(onAtender, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))) { Text("Atender") }
                } else {
                    OutlinedButton({ mute = onMute() }) { Text(if (mute) "Ativar microfone" else "Silenciar", color = Color.White) }
                }
                Button(onDesligar, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))) { Text("Desligar") }
            }
        }
    }
}

@Composable
private fun ListaContactos(vm: AppViewModel, onAbrir: (String) -> Unit) {
    val contactos by vm.xmpp.contactos.collectAsState()
    var novo by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(novo, { novo = it.trim() }, label = { Text("Adicionar colega (ex.: 102)") },
                singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.adicionarContacto(novo); novo = "" }, enabled = novo.isNotBlank()) { Text("+") }
        }
        Spacer(Modifier.height(8.dp))
        if (contactos.isEmpty()) {
            Text("Sem contactos. Adiciona colegas acima ou cria grupos partilhados no Openfire.",
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp), color = Color.Gray)
        }
        LazyColumn {
            items(contactos, key = { it.jid }) { c -> LinhaContacto(c) { onAbrir(c.jid) } }
        }
    }
}

@Composable
private fun LinhaContacto(c: Contacto, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (c.online) Color(0xFF43A047) else Color.Gray))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(c.nome, style = MaterialTheme.typography.titleMedium)
            Text(c.jid, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
    HorizontalDivider()
}

@Composable
private fun Conversa(vm: AppViewModel, jid: String, onVoltar: () -> Unit, onLigar: (String) -> Unit) {
    val todas by vm.xmpp.mensagens.collectAsState()
    val msgs = todas.filter { it.contacto.equals(jid, ignoreCase = true) }
    var texto by remember { mutableStateOf("") }
    val lista = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) lista.animateScrollToItem(msgs.lastIndex) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onVoltar) { Text("‹ Voltar") }
            Text(jid.substringBefore("@"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(onClick = { onLigar(jid) }) { Text("📞 Ligar") }
        }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = lista) {
            items(msgs) { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = if (m.enviada) Arrangement.End else Arrangement.Start) {
                    Text(m.texto,
                        Modifier.clip(RoundedCornerShape(14.dp))
                            .background(if (m.enviada) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .widthIn(max = 280.dp),
                        color = if (m.enviada) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(texto, { texto = it }, placeholder = { Text("Mensagem") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.enviar(jid, texto); texto = "" }, enabled = texto.isNotBlank()) { Text("Enviar") }
        }
    }
}
