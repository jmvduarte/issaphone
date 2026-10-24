package pt.ipca.issafone.servico

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import pt.ipca.issafone.MainActivity

object Notificacoes {

    const val CANAL_SERVICO = "servico"
    const val CANAL_CHAMADAS = "chamadas"
    const val CANAL_MENSAGENS = "mensagens"

    const val ID_SERVICO = 1
    const val ID_CHAMADA = 2
    private const val ID_MENSAGEM_BASE = 1000

    const val ACAO_ATENDER = "pt.ipca.issafone.ATENDER"
    const val ACAO_RECUSAR = "pt.ipca.issafone.RECUSAR"
    const val EXTRA_CONVERSA = "conversa"

    fun criarCanais(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CANAL_SERVICO, "Ligação ao servidor", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Mantém o IssaFone ligado ao Issabel"
                setShowBadge(false)
            },
            NotificationChannel(CANAL_CHAMADAS, "Chamadas recebidas", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Ecrã de chamada a entrar"
                // Sem som no canal: quem toca é o Linphone, com o toque do telemóvel
                setSound(null, null)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
            NotificationChannel(CANAL_MENSAGENS, "Mensagens", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Mensagens do chat interno"
            }
        ))
    }

    private fun abrirApp(ctx: Context, requestCode: Int, acao: String? = null, conversa: String? = null) =
        PendingIntent.getActivity(
            ctx, requestCode,
            Intent(ctx, MainActivity::class.java).apply {
                action = acao
                conversa?.let { putExtra(EXTRA_CONVERSA, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun servico(ctx: Context, estado: String): Notification =
        NotificationCompat.Builder(ctx, CANAL_SERVICO)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("IssaFone ativo")
            .setContentText(estado)
            .setContentIntent(abrirApp(ctx, 10))
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    fun chamadaRecebida(ctx: Context, remoto: String) {
        val pessoa = Person.Builder().setName(remoto).setImportant(true).build()
        val recusar = PendingIntent.getBroadcast(
            ctx, 20,
            Intent(ctx, CallActionReceiver::class.java).setAction(ACAO_RECUSAR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val atender = abrirApp(ctx, 21, ACAO_ATENDER)
        val ecraCheio = abrirApp(ctx, 22)

        val n = NotificationCompat.Builder(ctx, CANAL_CHAMADAS)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Chamada de $remoto")
            .setContentText("IssaFone")
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(pessoa, recusar, atender))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(ecraCheio, true)   // acende o ecrã se estiver bloqueado
            .setContentIntent(ecraCheio)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
        notificar(ctx, ID_CHAMADA, n)
    }

    fun cancelarChamada(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_CHAMADA)

    fun mensagem(ctx: Context, de: String, texto: String) {
        val nome = de.substringBefore("@")
        val n = NotificationCompat.Builder(ctx, CANAL_MENSAGENS)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(nome)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(abrirApp(ctx, 30 + de.hashCode() % 1000, conversa = de))
            .setAutoCancel(true)
            .build()
        notificar(ctx, ID_MENSAGEM_BASE + (de.hashCode() and 0xFFFF), n)
    }

    private fun notificar(ctx: Context, id: Int, n: Notification) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(ctx).notify(id, n)
        }
    }
}
