package pt.ipca.issafone.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Uma definição do sistema que impede o IssaFone de tocar com a app fechada. */
private data class Aviso(val texto: String, val botao: String, val intent: Intent)

@SuppressLint("BatteryLife") // pedir exceção de bateria é legítimo numa app VoIP
private fun avisosPendentes(ctx: Context): List<Aviso> = buildList {
    val pkg = Uri.parse("package:${ctx.packageName}")

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) add(Aviso(
        "As notificações estão desligadas: não vais ver chamadas nem mensagens.",
        "Ativar notificações",
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
    ))

    val pm = ctx.getSystemService(PowerManager::class.java)
    if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) add(Aviso(
        "A otimização de bateria pode desligar o IssaFone com o ecrã apagado.",
        "Permitir em segundo plano",
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
    ))

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        !ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    ) add(Aviso(
        "Sem esta permissão, a chamada aparece só como notificação com o telemóvel bloqueado.",
        "Ecrã de chamada",
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
    ))

    // Sem isto, as atualizações automáticas ficam a meio ("Atualização cancelada")
    if (!ctx.packageManager.canRequestPackageInstalls()) add(Aviso(
        "Para a app se atualizar sozinha, autoriza o IssaFone a instalar apps.",
        "Permitir atualizações",
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg)
    ))
}

/** Cartão no topo do ecrã principal; desaparece quando está tudo autorizado. */
@Composable
fun AvisosSegundoPlano() {
    val ctx = LocalContext.current
    var avisos by remember { mutableStateOf(avisosPendentes(ctx)) }

    // Volta a verificar quando o utilizador regressa das Definições
    LifecycleResumeEffect(Unit) {
        avisos = avisosPendentes(ctx)
        onPauseOrDispose { }
    }

    if (avisos.isEmpty()) return
    Card(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = CorAviso.copy(alpha = .14f)),
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(Icones.Aviso, null, tint = CorAviso)
                Spacer(Modifier.width(10.dp))
                Text("Falta autorizar", style = MaterialTheme.typography.titleSmall)
            }
            avisos.forEach { a ->
                Text(a.texto, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = {
                    runCatching { ctx.startActivity(a.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text(a.botao) }
            }
        }
    }
}
