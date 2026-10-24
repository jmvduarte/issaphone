package pt.ipca.issafone.xmpp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.ReconnectionManager
import org.jivesoftware.smack.android.AndroidSmackInitializer
import org.jivesoftware.smack.chat2.ChatManager
import org.jivesoftware.smack.roster.Roster
import org.jivesoftware.smack.roster.RosterListener
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate
import java.net.InetAddress

data class Mensagem(
    val contacto: String,   // JID "utilizador@dominio"
    val texto: String,
    val enviada: Boolean,
    val hora: Long = System.currentTimeMillis()
)

data class Contacto(val jid: String, val nome: String, val online: Boolean)

/**
 * Chat interno via XMPP contra o Openfire instalado no Issabel, usando o Smack.
 */
class XmppManager(context: Context) {

    init {
        AndroidSmackInitializer.initialize(context.applicationContext)
    }

    private var ligacao: XMPPTCPConnection? = null

    private val _estado = MutableStateFlow("Desligado")
    val estado: StateFlow<String> = _estado

    private val _mensagens = MutableStateFlow<List<Mensagem>>(emptyList())
    val mensagens: StateFlow<List<Mensagem>> = _mensagens

    private val _contactos = MutableStateFlow<List<Contacto>>(emptyList())
    val contactos: StateFlow<List<Contacto>> = _contactos

    /**
     * @param dominio XMPP Domain configurado no Openfire (ver consola :9090)
     * @param host IP do servidor Issabel
     * @param usarTls false para servidores de laboratório com certificado autoassinado
     */
    suspend fun ligar(
        utilizador: String,
        password: String,
        dominio: String,
        host: String,
        porta: Int = 5222,
        usarTls: Boolean = false
    ) = withContext(Dispatchers.IO) {
        try {
            _estado.value = "A ligar…"
            desligarInterno()

            val config = XMPPTCPConnectionConfiguration.builder()
                .setUsernameAndPassword(utilizador, password)
                .setXmppDomain(dominio)
                .setHostAddress(InetAddress.getByName(host))
                .setPort(porta)
                .setResource("IssaFone")
                .setSecurityMode(
                    if (usarTls) ConnectionConfiguration.SecurityMode.required
                    else ConnectionConfiguration.SecurityMode.disabled
                )
                .setSendPresence(true)
                .build()

            val con = XMPPTCPConnection(config)
            con.connect()
            con.login()
            ReconnectionManager.getInstanceFor(con).enableAutomaticReconnection()
            ligacao = con

            ChatManager.getInstanceFor(con).addIncomingListener { from, message, _ ->
                val corpo = message.body ?: return@addIncomingListener
                _mensagens.value = _mensagens.value + Mensagem(from.asEntityBareJidString(), corpo, enviada = false)
            }

            val roster = Roster.getInstanceFor(con)
            roster.subscriptionMode = Roster.SubscriptionMode.accept_all
            roster.addRosterListener(object : RosterListener {
                override fun entriesAdded(addresses: Collection<Jid>) = atualizarContactos()
                override fun entriesUpdated(addresses: Collection<Jid>) = atualizarContactos()
                override fun entriesDeleted(addresses: Collection<Jid>) = atualizarContactos()
                override fun presenceChanged(presence: Presence) = atualizarContactos()
            })
            roster.reloadAndWait()
            atualizarContactos()

            _estado.value = "Ligado como $utilizador@$dominio"
        } catch (e: Exception) {
            _estado.value = "Erro: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun atualizarContactos() {
        val con = ligacao ?: return
        val roster = Roster.getInstanceFor(con)
        _contactos.value = roster.entries.map {
            Contacto(
                jid = it.jid.asBareJid().toString(),
                nome = it.name ?: it.jid.localpartOrNull?.toString() ?: it.jid.toString(),
                online = roster.getPresence(it.jid).isAvailable
            )
        }.sortedWith(compareByDescending<Contacto> { it.online }.thenBy { it.nome })
    }

    /** Aceita "101" (completa com o domínio) ou "101@dominio". */
    suspend fun enviar(destino: String, texto: String) = withContext(Dispatchers.IO) {
        val con = ligacao ?: return@withContext
        if (texto.isBlank()) return@withContext
        val jidTexto = if ("@" in destino) destino else "$destino@${con.xmppServiceDomain}"
        try {
            val jid = JidCreate.entityBareFrom(jidTexto)
            ChatManager.getInstanceFor(con).chatWith(jid).send(texto)
            _mensagens.value = _mensagens.value + Mensagem(jidTexto, texto, enviada = true)
        } catch (e: Exception) {
            _estado.value = "Erro ao enviar: ${e.message}"
        }
    }

    /** Adiciona um colega à lista de contactos (pede subscrição de presença). */
    suspend fun adicionarContacto(utilizador: String) = withContext(Dispatchers.IO) {
        val con = ligacao ?: return@withContext
        val jidTexto = if ("@" in utilizador) utilizador else "$utilizador@${con.xmppServiceDomain}"
        try {
            Roster.getInstanceFor(con).createItemAndRequestSubscription(
                JidCreate.entityBareFrom(jidTexto), utilizador.substringBefore("@"), null
            )
        } catch (e: Exception) {
            _estado.value = "Erro ao adicionar: ${e.message}"
        }
    }

    private fun desligarInterno() {
        ligacao?.disconnect()
        ligacao = null
    }

    suspend fun desligar() = withContext(Dispatchers.IO) {
        desligarInterno()
        _estado.value = "Desligado"
    }
}
