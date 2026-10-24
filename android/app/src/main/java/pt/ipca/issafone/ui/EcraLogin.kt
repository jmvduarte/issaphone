package pt.ipca.issafone.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import pt.ipca.issafone.AppViewModel
import pt.ipca.issafone.BuildConfig
import pt.ipca.issafone.Config
import pt.ipca.issafone.R

@Composable
fun EcraLogin(vm: AppViewModel) {
    val atual by vm.config.collectAsState()
    var utilizador by remember { mutableStateOf(atual.utilizador) }
    var passSip by remember { mutableStateOf(atual.passwordSip) }

    // Opções avançadas (já vêm preenchidas para o servidor do projeto)
    var avancado by remember { mutableStateOf(false) }
    var servidor by remember { mutableStateOf(atual.servidor.ifBlank { BuildConfig.SERVIDOR_PADRAO }) }
    var dominio by remember { mutableStateOf(atual.dominioXmpp) }
    var passXmpp by remember { mutableStateOf(atual.passwordXmpp) }
    var tls by remember { mutableStateOf(atual.usarTls) }

    val podeEntrar = servidor.isNotBlank() && utilizador.isNotBlank() && passSip.isNotBlank()
    val entrar = { if (podeEntrar) vm.entrar(Config(servidor, utilizador, passSip, dominio, passXmpp, tls)) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))
        Image(
            painterResource(R.drawable.logo_issafone), contentDescription = null,
            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(24.dp))
        )
        Text("IssaFone", style = MaterialTheme.typography.headlineMedium)
        Text("Chamadas e chat interno do Issabel", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            utilizador, { utilizador = it.trim() }, label = { Text("Extensão") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
        )
        OutlinedTextField(
            passSip, { passSip = it }, label = { Text("Palavra-passe") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)
        )
        Button(onClick = entrar, enabled = podeEntrar, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("Entrar")
        }

        TextButton(onClick = { avancado = !avancado }) {
            Text(if (avancado) "Esconder opções avançadas" else "Opções avançadas")
        }
        if (avancado) {
            OutlinedTextField(
                servidor, { servidor = it.trim() }, label = { Text("Servidor") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            OutlinedTextField(
                dominio, { dominio = it.trim() }, label = { Text("Domínio XMPP (vazio = igual ao servidor)") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                passXmpp, { passXmpp = it }, label = { Text("Palavra-passe do chat (vazio = a mesma)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Switch(tls, { tls = it })
                Spacer(Modifier.width(8.dp))
                Text("Chat cifrado (TLS)")
            }
        }

        Text(
            "Versão ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}
