package pt.ipca.issafone.atualizacao

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import pt.ipca.issafone.servico.Notificacoes

/** Recebe o resultado da instalação pedida pelo [Atualizador]. */
class ResultadoInstalacaoReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_VERSAO = "versao"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val versao = intent.getStringExtra(EXTRA_VERSAO) ?: ""
        when (val estado = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {

            // O Android quer confirmação do utilizador (1.ª atualização, ou Android < 12)
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmar = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirmar ?: return
                confirmar.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                val visivel = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                if (visivel) {
                    Atualizador.definirEstado("Confirma a instalação da versão $versao")
                    context.startActivity(confirmar)
                } else {
                    // Com a app fechada não se pode abrir janelas: avisa por notificação
                    Notificacoes.atualizacaoPronta(context, versao, confirmar)
                }
            }

            PackageInstaller.STATUS_SUCCESS ->
                Atualizador.definirEstado(null) // a app é reiniciada pelo sistema

            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                Atualizador.definirEstado(
                    "Não foi possível atualizar: a versão instalada tem outra assinatura. " +
                        "Desinstala a app e instala a versão $versao uma vez à mão."
                )

            PackageInstaller.STATUS_FAILURE_ABORTED ->
                Atualizador.definirEstado("Atualização cancelada")

            else -> Atualizador.definirEstado(
                "Falha na atualização ($estado): " +
                    (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "erro desconhecido")
            )
        }
    }
}
