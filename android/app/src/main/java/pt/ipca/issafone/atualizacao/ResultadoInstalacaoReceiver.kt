package pt.ipca.issafone.atualizacao

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Já não é usado (a app deixou de se instalar sozinha), mas fica registado: o Android pode
 * ainda entregar o resultado de uma instalação antiga e, sem esta classe, a app fechava.
 */
class ResultadoInstalacaoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Atualizador.definirEstado(null)
    }
}
