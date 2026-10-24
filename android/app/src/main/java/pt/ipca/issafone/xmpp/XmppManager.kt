package pt.ipca.issafone.xmpp

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jivesoftware.smack.ConnectionConfiguration
import org.jivesoftware.smack.ReconnectionManager
import org.jivesoftware.smack.android.AndroidSmackInitializer
import org.jivesoftware.smack.chat2.ChatManager
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.Presence
import org.jivesoftware.smack.roster.Roster
import org.jivesoftware.smack.roster.RosterListener
import org.jivesoftware.smack.tcp.XMPPTCPConnection
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration
import org.jivesoftware.smackx.bookmarks.BookmarkManager
import org.jivesoftware.smackx.delay.packet.DelayInformation
import org.jivesoftware.smackx.muc.MultiUserChat
import org.jivesoftware.smackx.muc.MultiUserChatManager
import org.jivesoftware.smackx.muc.UserStatusListener
import org.jivesoftware.smackx.muc.packet.MUCUser
import org.jxmpp.jid.EntityBareJid
import org.jxmpp.jid.Jid
import org.jxmpp.jid.impl.JidCreate
import org.jxmpp.jid.parts.Resourcepart
import java.net.InetAddress
import java.text.Normalizer

data class Mensagem(
    val contacto: String,          // JID do contacto ou do grupo
    val texto: String,
    val enviada: Boolean,
    val hora: Long = System.currentTimeMillis(),
    val autor: String? = null,     // nos grupos: quem escreveu (extensão)
    val historico: Boolean = false // mensagem antiga reenviada pelo servidor (não notifica)
)

data class Contacto(val jid: String, val nome: String, val online: Boolean)

data class Grupo(
    val jid: String,
    val nome: String,
    val dono: Boolean = false,
    val ligados: List<String> = emptyList()   // extensões ligadas ao grupo neste momento
)

data class Membro(val jid: String, val dono: Boolean)

/**
 * Chat interno via XMPP contra o Openfire do Issabel, usando o Smack.
 * Grupos = salas MUC (XEP-0045) permanentes e só para membros; a lista de grupos
 * de cada utilizador fica no servidor (marcadores, XEP-0048), igual à webapp.
 */
class XmppManager(context: Context) {

    init {
        AndroidSmackInitializer.initialize(context.applicationContext)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ligacao: XMPPTCPConnection? = null
    private var alcunha = ""
    private val salas = mutableMapOf<String, MultiUserChat>()

    private val _estado = MutableStateFlow("Desligado")
    val estado: StateFlow<String> = _estado

    private val _mensagens = MutableStateFlow<List<Mensagem>>(emptyList())
    val mensagens: StateFlow<List<Mensagem>> = _mensagens

    private val _contactos = MutableStateFlow<List<Contacto>>(emptyList())
    val contactos: StateFlow<List<Contacto>> = _contactos

    private val _grupos = MutableStateFlow<List<Grupo>>(emptyList())
    val grupos: StateFlow<List<Grupo>> = _grupos

    /** Avisos curtos para a interface (ex.: "Foste adicionado ao grupo X"). */
    private val _aviso = MutableStateFlow<String?>(null)
    val aviso: StateFlow<String?> = _aviso
    fun limparAviso() { _aviso.value = null }

    fun ehGrupo(jid: String) = jid.contains("@conference.")
    fun nomeDe(jid: String) = _grupos.value.firstOrNull { it.jid == jid }?.nome
        ?: _contactos.value.firstOrNull { it.jid == jid }?.nome
        ?: jid.substringBefore("@")

    /**
     * @param dominio XMPP Domain configurado no Openfire
     * @param host endereço do servidor Issabel
     * @param usarTls exige ligação cifrada (o Openfire tem certificado válido)
     */
    suspend fun ligar(
        utilizador: String,
        password: String,
        dominio: String,
        host: String,
        porta: Int = 5222,
        usarTls: Boolean = true
    ) = withContext(Dispatchers.IO) {
        try {
            _estado.value = "A ligar…"
            desligarInterno()
            alcunha = utilizador

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
                // Convites para grupos chegam como mensagens: são tratados pelo MUC
                if (message.hasExtension(MUCUser.ELEMENT, MUCUser.NAMESPACE)) return@addIncomingListener
                val corpo = message.body ?: return@addIncomingListener
                juntar(Mensagem(from.asEntityBareJidString(), corpo, enviada = false))
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

            prepararGrupos(con)

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

    /**
     * Junta uma mensagem à conversa. Nos grupos, o servidor reenvia o histórico ao
     * entrar na sala: a mesma mensagem do mesmo autor com < 3 min de diferença é ignorada.
     */
    private fun juntar(m: Mensagem) {
        _mensagens.update { lista ->
            val repetida = m.autor != null && lista.any {
                it.contacto == m.contacto && it.autor == m.autor && it.texto == m.texto &&
                    kotlin.math.abs(it.hora - m.hora) < 180_000
            }
            if (repetida) lista else (lista + m).sortedBy { it.hora }
        }
    }

    /** Aceita "101" (completa com o domínio) ou "101@dominio"; também envia para grupos. */
    suspend fun enviar(destino: String, texto: String) = withContext(Dispatchers.IO) {
        val con = ligacao ?: return@withContext
        if (texto.isBlank()) return@withContext
        try {
            if (ehGrupo(destino)) {
                // A sala devolve a mensagem a todos (incluindo a quem envia): é aí que aparece
                salas[destino]?.sendMessage(texto)
                return@withContext
            }
            val jidTexto = if ("@" in destino) destino else "$destino@${con.xmppServiceDomain}"
            ChatManager.getInstanceFor(con).chatWith(JidCreate.entityBareFrom(jidTexto)).send(texto)
            juntar(Mensagem(jidTexto, texto, enviada = true))
        } catch (e: Exception) {
            _aviso.value = "Erro ao enviar: ${e.message}"
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
            _aviso.value = "Erro ao adicionar: ${e.message}"
        }
    }

    /* ======================= Grupos ======================= */

    private fun prepararGrupos(con: XMPPTCPConnection) {
        salas.clear()
        _grupos.value = emptyList()
        val mucs = MultiUserChatManager.getInstanceFor(con)
        mucs.setAutoJoinOnReconnect(true)

        // Convite recebido: entra logo e guarda nos marcadores
        mucs.addInvitationListener { _, sala, _, razao, _, _, _ ->
            scope.launch {
                val jid = sala.room.toString()
                if (salas.containsKey(jid)) return@launch
                val nome = razao?.takeIf { it.isNotBlank() } ?: jid.substringBefore("@")
                if (entrar(sala, nome)) {
                    guardarMarcador(jid, nome)
                    _aviso.value = "Foste adicionado ao grupo $nome"
                }
            }
        }

        // Grupos guardados no servidor
        scope.launch {
            val marcadores = runCatching { BookmarkManager.getBookmarkManager(con).bookmarkedConferences }.getOrDefault(emptyList())
            for (b in marcadores) {
                val sala = mucs.getMultiUserChat(b.jid)
                if (!entrar(sala, b.name ?: b.jid.toString().substringBefore("@"))) {
                    // Já não é membro ou o grupo foi apagado
                    runCatching { BookmarkManager.getBookmarkManager(con).removeBookmarkedConference(b.jid) }
                }
            }
        }
    }

    /** Entra numa sala e passa a receber as mensagens. Devolve false se não for possível. */
    private fun entrar(sala: MultiUserChat, nome: String, comHistorico: Boolean = true): Boolean {
        val jid = sala.room.toString()
        return try {
            if (!sala.isJoined) {
                val cfg = sala.getEnterConfigurationBuilder(Resourcepart.from(alcunha))
                    .apply { if (comHistorico) requestMaxStanzasHistory(50) else requestNoHistory() }
                    .build()
                sala.join(cfg)
            }
            registar(sala, nome)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun registar(sala: MultiUserChat, nome: String) {
        val jid = sala.room.toString()
        if (salas.put(jid, sala) == null) {
            sala.addMessageListener { m -> receberDoGrupo(jid, m) }
            sala.addParticipantListener { atualizarGrupo(jid) }
            sala.addUserStatusListener(object : UserStatusListener {
                override fun membershipRevoked() = esquecer(jid, "Foste removido do grupo ${nomeDe(jid)}")
                override fun kicked(actor: Jid?, reason: String?) = esquecer(jid, "Saíste do grupo ${nomeDe(jid)}")
            })
        }
        val dono = runCatching { sala.owners.any { it.jid.asBareJid().toString() == ligacao?.user?.asBareJid().toString() } }
            .getOrDefault(false)
        _grupos.update { lista ->
            (lista.filter { it.jid != jid } + Grupo(jid, nome, dono, ligadosEm(sala))).sortedBy { it.nome.lowercase() }
        }
    }

    private fun ligadosEm(sala: MultiUserChat) =
        sala.occupants.mapNotNull { it.resourceOrNull?.toString() }.filter { it != alcunha }

    private fun atualizarGrupo(jid: String) {
        val sala = salas[jid] ?: return
        _grupos.update { lista -> lista.map { if (it.jid == jid) it.copy(ligados = ligadosEm(sala)) else it } }
    }

    private fun receberDoGrupo(jid: String, m: Message) {
        val corpo = m.body ?: return
        val autor = m.from?.resourceOrNull?.toString() ?: ""
        val atraso = DelayInformation.from(m)
        juntar(
            Mensagem(
                contacto = jid,
                texto = corpo,
                enviada = autor == alcunha,
                hora = atraso?.stamp?.time ?: System.currentTimeMillis(),
                autor = autor,
                historico = atraso != null
            )
        )
    }

    /** Cria um grupo permanente, só para membros, e convida os membros escolhidos. */
    suspend fun criarGrupo(nome: String, membros: List<String>): String? = withContext(Dispatchers.IO) {
        val con = ligacao ?: return@withContext null
        try {
            val base = Normalizer.normalize(nome, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}"), "").lowercase()
                .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).ifEmpty { "grupo" }
            val sufixo = (1..4).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
            val jid = JidCreate.entityBareFrom("$base-$sufixo@conference.${con.xmppServiceDomain}")
            val sala = MultiUserChatManager.getInstanceFor(con).getMultiUserChat(jid)

            sala.create(Resourcepart.from(alcunha))
            val form = sala.configurationForm.fillableForm
            runCatching { form.setAnswer("muc#roomconfig_roomname", nome) }
            runCatching { form.setAnswer("muc#roomconfig_persistentroom", true) }
            runCatching { form.setAnswer("muc#roomconfig_membersonly", true) }
            runCatching { form.setAnswer("muc#roomconfig_publicroom", false) }
            runCatching { form.setAnswer("muc#roomconfig_whois", "anyone") }
            sala.sendConfigurationForm(form)

            val jids = membros.map { JidCreate.entityBareFrom(it) }
            if (jids.isNotEmpty()) {
                sala.grantMembership(jids)
                jids.forEach { sala.invite(it, nome) }
            }
            registar(sala, nome)
            guardarMarcador(jid.toString(), nome)
            jid.toString()
        } catch (e: Exception) {
            _aviso.value = "Não foi possível criar o grupo: ${e.message ?: e.javaClass.simpleName}"
            null
        }
    }

    /** Membros e donos do grupo (só o dono pode pedir esta lista). */
    suspend fun membros(jid: String): List<Membro> = withContext(Dispatchers.IO) {
        val sala = salas[jid] ?: return@withContext emptyList()
        val donos = runCatching { sala.owners.map { Membro(it.jid.asBareJid().toString(), true) } }.getOrDefault(emptyList())
        val membros = runCatching { sala.members.map { Membro(it.jid.asBareJid().toString(), false) } }.getOrDefault(emptyList())
        donos + membros
    }

    suspend fun adicionarMembro(jid: String, membro: String) = withContext(Dispatchers.IO) {
        val sala = salas[jid] ?: return@withContext
        try {
            val m = JidCreate.entityBareFrom(membro)
            sala.grantMembership(m)
            sala.invite(m, nomeDe(jid))
        } catch (e: Exception) {
            _aviso.value = "Não foi possível adicionar: ${e.message}"
        }
    }

    suspend fun removerMembro(jid: String, membro: String) = withContext(Dispatchers.IO) {
        val sala = salas[jid] ?: return@withContext
        try {
            sala.revokeMembership(JidCreate.entityBareFrom(membro))
        } catch (e: Exception) {
            _aviso.value = "Não foi possível remover: ${e.message}"
        }
    }

    suspend fun sairGrupo(jid: String) = withContext(Dispatchers.IO) {
        runCatching { salas[jid]?.leave() }
        esquecer(jid, null)
    }

    suspend fun apagarGrupo(jid: String) = withContext(Dispatchers.IO) {
        try {
            salas[jid]?.destroy("Grupo apagado", null)
            esquecer(jid, null)
        } catch (e: Exception) {
            _aviso.value = "Não foi possível apagar o grupo: ${e.message}"
        }
    }

    private fun esquecer(jid: String, aviso: String?) {
        salas.remove(jid)
        _grupos.update { lista -> lista.filter { it.jid != jid } }
        _mensagens.update { lista -> lista.filter { it.contacto != jid } }
        val con = ligacao
        if (con != null) scope.launch {
            runCatching { BookmarkManager.getBookmarkManager(con).removeBookmarkedConference(JidCreate.entityBareFrom(jid)) }
        }
        if (aviso != null) _aviso.value = aviso
    }

    private fun guardarMarcador(jid: String, nome: String) {
        val con = ligacao ?: return
        runCatching {
            BookmarkManager.getBookmarkManager(con).addBookmarkedConference(
                nome, JidCreate.entityBareFrom(jid) as EntityBareJid, true, Resourcepart.from(alcunha), null
            )
        }
    }

    private fun desligarInterno() {
        salas.clear()
        _grupos.value = emptyList()
        ligacao?.disconnect()
        ligacao = null
    }

    suspend fun desligar() = withContext(Dispatchers.IO) {
        desligarInterno()
        _estado.value = "Desligado"
    }
}
