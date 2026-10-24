package pt.ipca.issafone.servico

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import pt.ipca.issafone.ConfigStore
import pt.ipca.issafone.IssaFoneApp

/** Botão "Recusar" da notificação de chamada (não precisa de abrir a app). */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Notificacoes.ACAO_RECUSAR) {
            (context.applicationContext as IssaFoneApp).sip.desligar()
            Notificacoes.cancelarChamada(context)
        }
    }
}

/** Volta a ligar o IssaFone depois de o telemóvel reiniciar. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val c = ConfigStore.carregar(context)
        if (ConfigStore.sessaoAtiva(context) && c.valida) {
            ContextCompat.startForegroundService(context, Intent(context, IssaFoneService::class.java))
        }
    }
}
