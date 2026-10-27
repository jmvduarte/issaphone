package pt.ipca.issafone.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pt.ipca.issafone.AppViewModel
import pt.ipca.issafone.BuildConfig
import pt.ipca.issafone.Config
import pt.ipca.issafone.R

@Composable
fun EcraLogin(vm: AppViewModel) {
    val atual by vm.config.collectAsState()
    EcraLoginUi(atual) { vm.entrar(it) }
}

@Composable
internal fun EcraLoginUi(atual: Config, onEntrar: (Config) -> Unit) {
    var utilizador by remember { mutableStateOf(atual.utilizador) }
    var passSip by remember { mutableStateOf(atual.passwordSip) }

    // Opções avançadas (já vêm preenchidas para o servidor do projeto)
    var avancado by remember { mutableStateOf(false) }
    var servidor by remember { mutableStateOf(atual.servidor.ifBlank { BuildConfig.SERVIDOR_PADRAO }) }
    var dominio by remember { mutableStateOf(atual.dominioXmpp) }
    var passXmpp by remember { mutableStateOf(atual.passwordXmpp) }
    var tls by remember { mutableStateOf(atual.usarTls) }

    val podeEntrar = servidor.isNotBlank() && utilizador.isNotBlank() && passSip.isNotBlank()
    val entrar = { if (podeEntrar) onEntrar(Config(servidor, utilizador, passSip, dominio, passXmpp, tls)) }
    val forma = RoundedCornerShape(16.dp)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Fundo: degradê da marca na parte de cima
        Box(
            Modifier.fillMaxWidth().fillMaxHeight(.46f)
                .clip(RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp))
                .background(DegradeMarca)
        )
        Column(
            Modifier.fillMaxSize().systemBarsPadding().imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))
            Image(
                painterResource(R.drawable.logo_issafone), contentDescription = null,
                modifier = Modifier.size(92.dp).shadow(16.dp, RoundedCornerShape(26.dp)).clip(RoundedCornerShape(26.dp))
                    .border(2.dp, Color.White.copy(alpha = .6f), RoundedCornerShape(26.dp))
            )
            Spacer(Modifier.height(18.dp))
            Text("IssaFone", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            Text("Chamadas e chat da tua empresa", color = Color.White.copy(alpha = .85f), fontSize = 15.sp)
            Spacer(Modifier.height(32.dp))

            // Cartão com os campos
            Column(
                Modifier.fillMaxWidth()
                    .shadow(20.dp, RoundedCornerShape(28.dp), ambientColor = MarcaAzul.copy(alpha = .3f), spotColor = MarcaAzul.copy(alpha = .3f))
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Entrar", style = MaterialTheme.typography.headlineSmall)
                Text("Usa o número da tua extensão e a respetiva palavra-passe.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    utilizador, { utilizador = it.trim() }, label = { Text("Extensão") },
                    leadingIcon = { Icon(Icones.Teclado, null) },
                    singleLine = true, shape = forma, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    passSip, { passSip = it }, label = { Text("Palavra-passe") },
                    leadingIcon = { Icon(Icones.Cadeado, null) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = forma,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)
                )
                Box(
                    Modifier.fillMaxWidth().height(54.dp).clip(forma)
                        .background(if (podeEntrar) DegradeMarca else androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant)))
                        .clickable(enabled = podeEntrar) { entrar() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("Entrar", color = if (podeEntrar) Color.White else MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.titleMedium)
                }

                TextButton(onClick = { avancado = !avancado }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(if (avancado) "Esconder opções avançadas" else "Opções avançadas")
                }
                if (avancado) {
                    OutlinedTextField(
                        servidor, { servidor = it.trim() }, label = { Text("Servidor") },
                        singleLine = true, shape = forma, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                    OutlinedTextField(
                        dominio, { dominio = it.trim() }, label = { Text("Domínio do chat (vazio = igual ao servidor)") },
                        singleLine = true, shape = forma, modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        passXmpp, { passXmpp = it }, label = { Text("Palavra-passe do chat (vazio = a mesma)") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = forma,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Chat cifrado (TLS)", modifier = Modifier.weight(1f))
                        Switch(tls, { tls = it })
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("Versão ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(16.dp))
        }
    }
}
