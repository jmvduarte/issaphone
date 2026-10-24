package pt.ipca.issafone.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import pt.ipca.issafone.AppViewModel
import pt.ipca.issafone.BuildConfig
import pt.ipca.issafone.Config

@Composable
fun EcraLogin(vm: AppViewModel) {
    val atual by vm.config.collectAsState()
    var servidor by remember { mutableStateOf(atual.servidor) }
    var utilizador by remember { mutableStateOf(atual.utilizador) }
    var passSip by remember { mutableStateOf(atual.passwordSip) }
    var dominio by remember { mutableStateOf(atual.dominioXmpp) }
    var passXmpp by remember { mutableStateOf(atual.passwordXmpp) }
    var tls by remember { mutableStateOf(atual.usarTls) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("IssaFone", style = MaterialTheme.typography.headlineMedium)
        Text("Chamadas e chat interno do Issabel · versão ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodyMedium)

        Text("Servidor", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(servidor, { servidor = it.trim() }, label = { Text("IP do Issabel") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))

        Text("Telefone (SIP)", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(utilizador, { utilizador = it.trim() }, label = { Text("Extensão") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(passSip, { passSip = it }, label = { Text("Palavra-passe da extensão") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth())

        Text("Chat (XMPP / Openfire)", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(dominio, { dominio = it.trim() },
            label = { Text("Domínio XMPP (vazio = IP do servidor)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(passXmpp, { passXmpp = it },
            label = { Text("Palavra-passe do chat (vazio = a mesma da extensão)") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(tls, { tls = it })
            Spacer(Modifier.width(8.dp))
            Text("Exigir TLS no chat (requer certificado válido)")
        }

        Button(
            onClick = {
                vm.entrar(Config(servidor, utilizador, passSip, dominio, passXmpp, tls))
            },
            enabled = servidor.isNotBlank() && utilizador.isNotBlank() && passSip.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Entrar") }
    }
}
