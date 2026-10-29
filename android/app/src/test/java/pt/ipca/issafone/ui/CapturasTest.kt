package pt.ipca.issafone.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.detectEnvironment
import org.junit.Rule
import org.junit.Test
import pt.ipca.issafone.Config
import pt.ipca.issafone.sip.CallUi
import pt.ipca.issafone.sip.Chamada
import pt.ipca.issafone.xmpp.Contacto
import pt.ipca.issafone.xmpp.Grupo
import pt.ipca.issafone.xmpp.Mensagem

/**
 * Capturas de ecrã automáticas (Paparazzi): desenham os ecrãs com dados de exemplo,
 * sem telemóvel nem emulador. O GitHub publica as imagens no ramo "capturas".
 */
class CapturasTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        theme = "android:Theme.Material.Light.NoActionBar",
        maxPercentDifference = 100.0,
        // O Paparazzi 1.3 só sabe desenhar com a plataforma 34; a app compila com a 36
        environment = detectEnvironment().run {
            copy(compileSdkVersion = 34)
        },
    )

    private val agora = 1_793_100_000_000L   // hora fixa, para as capturas não mudarem

    private val contactos = listOf(
        Contacto("101@issa", "Jorge Duarte", true),
        Contacto("102@issa", "Ana Ribeiro", true),
        Contacto("103@issa", "Bruno Costa", false),
        Contacto("104@issa", "Carla Mendes", false),
        Contacto("105@issa", "Rui Fernandes", true),
        Contacto("106@issa", "Sofia Lopes", false),
        Contacto("201@issa", "Receção", true),
    )
    private val grupos = listOf(Grupo("suporte@conference.issa", "Suporte", true, listOf("102", "105")))
    private val mensagens = listOf(
        Mensagem("102@issa", "Olá Jorge! Já tens o relatório da reunião?", false, agora - 3_600_000),
        Mensagem("102@issa", "Sim, envio-te daqui a 5 minutos 👍", true, agora - 3_500_000),
        Mensagem("102@issa", "Perfeito, obrigada.", false, agora - 3_400_000),
        Mensagem("102@issa", "Já está no teu email. Liga-me se precisares de alguma coisa.", true, agora - 600_000),
        Mensagem("102@issa", "https://rsi-a36357.ddns.net/httpfileupload/abc123/Relatorio_outubro.pdf", false, agora - 300_000),
    )
    private val ultima = { jid: String ->
        when (jid) {
            "102@issa" -> UltimaMensagem("Tu: Já está no teu email.", agora - 600_000)
            "suporte@conference.issa" -> UltimaMensagem("Rui: O servidor já está atualizado", agora - 1_800_000)
            "105@issa" -> UltimaMensagem("Até amanhã!", agora - 90_000_000)
            else -> null
        }
    }
    private val recentes = listOf(
        Chamada("102", "Ana Ribeiro", "recebida", false, agora - 1_200_000, 312),
        Chamada("105", "Rui Fernandes", "perdida", false, agora - 5_400_000, 0),
        Chamada("201", "Receção", "feita", true, agora - 9_000_000, 95),
        Chamada("103", "Bruno Costa", "feita", false, agora - 90_000_000, 48),
        Chamada("104", "Carla Mendes", "recusada", false, agora - 95_000_000, 0),
    )

    private fun tela(modo: ModoTema = ModoTema.CLARO, conteudo: @Composable () -> Unit) {
        paparazzi.snapshot {
            IssaFoneTema(modo) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) { conteudo() }
                }
            }
        }
    }

    private fun principal(tab: Int, modo: ModoTema = ModoTema.CLARO, conteudo: @Composable () -> Unit) = tela(modo) {
        MolduraPrincipal(
            cabecalho = { Cabecalho("Registado", "Ligado como 101@issa", null, "Automático", {}, {}, {}) },
            tab = tab, perdidas = 1, onTab = {}, conteudo = conteudo
        )
    }

    @Test fun teclado() = principal(0) { TecladoUi("102", {}, {}) }
    @Test fun tecladoEscuro() = principal(0, ModoTema.ESCURO) { TecladoUi("", {}, {}) }
    @Test fun contactos() = principal(1) { AgendaUi(contactos, { _, _ -> }, {}) }
    @Test fun recentes() = principal(2) { RecentesUi(recentes, { _, n -> n }, { _, _ -> }, {}, agora) }
    @Test fun chat() = principal(3) { ListaChatUi(contactos, grupos, ultima, {}, {}, {}, agora) }
    @Test fun chatEscuro() = principal(3, ModoTema.ESCURO) { ListaChatUi(contactos, grupos, ultima, {}, {}, {}, agora) }

    @Test fun conversa() = tela {
        ConversaUi("Ana Ribeiro", "Ext. 102 · disponível", true, false, "102", mensagens, { it },
            {}, {}, {}, {}, {})
    }

    private fun chamada(c: CallUi, espera: String? = null) = tela {
        ChamadaUi(c, c.nome.ifBlank { c.remoto }, espera, 83, mute = false, altaVoz = true, camaraLigada = true, teclas = false,
            onAtender = {}, onDesligar = {}, onMute = {}, onTeclas = {}, onTecla = {}, onAltaVoz = {}, onVideo = {},
            onTrocarCamara = {}, onTransferir = {}, onAdicionar = {}, onAlternar = {}, onJuntar = {}, onLigarOsDois = {})
    }

    @Test fun chamadaATocar() = chamada(CallUi("A receber chamada", "102", aTocar = true, nome = "Ana Ribeiro"))
    @Test fun chamadaEmCurso() = chamada(CallUi("Em chamada", "102", ativa = true, nome = "Ana Ribeiro"))
    @Test fun chamadaEmEspera() = chamada(
        CallUi("Em chamada", "105", ativa = true, nome = "Rui Fernandes", emEspera = "102", chamadas = 2), "Ana Ribeiro")

    @Test fun login() = tela { EcraLoginUi(Config(servidor = "rsi-a36357.ddns.net")) {} }
}
