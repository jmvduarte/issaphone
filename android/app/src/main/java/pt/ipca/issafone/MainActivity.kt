package pt.ipca.issafone

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import pt.ipca.issafone.servico.Notificacoes
import pt.ipca.issafone.ui.EcraLogin
import pt.ipca.issafone.ui.EcraPrincipal

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    private val pedirPermissoes =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permissoes = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)       // videochamadas
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT) // auriculares Bluetooth
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        pedirPermissoes.launch(permissoes.toTypedArray())

        tratarIntent(intent)
        mostrarSobreBloqueioDuranteChamadas()

        // Desenha por baixo das barras do sistema (o degradê do cabeçalho vai até ao topo)
        enableEdgeToEdge()
        setContent {
            val tema by vm.tema.collectAsState()
            pt.ipca.issafone.ui.IssaFoneTema(tema) {
                androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) {
                    val ligado by vm.ligado.collectAsState()
                    if (ligado) EcraPrincipal(vm) else EcraLogin(vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tratarIntent(intent)
    }

    /** Ações vindas das notificações: atender chamada ou abrir uma conversa. */
    private fun tratarIntent(intent: Intent?) {
        when {
            intent?.action == Notificacoes.ACAO_ATENDER -> {
                vm.sip.atender()
                Notificacoes.cancelarChamada(this)
            }
            intent?.hasExtra(Notificacoes.EXTRA_CONVERSA) == true ->
                vm.pedirConversa(intent.getStringExtra(Notificacoes.EXTRA_CONVERSA))
        }
        // Evita repetir a ação se a Activity for recriada (ex.: rodar o ecrã)
        intent?.action = null
        intent?.removeExtra(Notificacoes.EXTRA_CONVERSA)
    }

    /**
     * Com o telemóvel bloqueado, o ecrã de chamada aparece por cima do bloqueio
     * e acende o ecrã — mas só enquanto há chamada (o chat não fica exposto).
     */
    private fun mostrarSobreBloqueioDuranteChamadas() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        lifecycleScope.launch {
            vm.sip.chamada.collect { c ->
                val emChamada = c.aTocar || c.ativa
                setShowWhenLocked(emChamada)
                setTurnScreenOn(emChamada)
            }
        }
    }
}
