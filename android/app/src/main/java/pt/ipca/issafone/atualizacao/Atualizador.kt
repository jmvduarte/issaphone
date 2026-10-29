package pt.ipca.issafone.atualizacao

import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pt.ipca.issafone.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualizações automáticas a partir das Releases do GitHub.
 *
 * Cada build do GitHub Actions publica a Release "android-N", com N = versionCode.
 * A app consulta a Release mais recente; se N for maior que a versão instalada,
 * avisa e abre o APK no browser para a pessoa o instalar.
 */
object Atualizador {

    // Cada app (IssaFone / IssaLink) tem as suas Releases: "android-N" / "issalink-N"
    private val NOME_APK = BuildConfig.NOME_APK
    private val api = "https://api.github.com/repos/${BuildConfig.REPO_ATUALIZACOES}/releases?per_page=30"

    data class Versao(val codigo: Int, val nome: String, val urlApk: String)

    /** Texto para mostrar na interface (null = nada a dizer). */
    private val _estado = MutableStateFlow<String?>(null)
    val estado: StateFlow<String?> = _estado

    private val emCurso = Mutex()

    fun definirEstado(texto: String?) { _estado.value = texto }

    /** Resultado de uma procura no GitHub. */
    sealed class Resultado {
        data class Nova(val versao: Versao) : Resultado()
        object EmDia : Resultado()
        data class Erro(val motivo: String) : Resultado()
    }

    /** Consulta o GitHub e diz se há versão nova, se está em dia ou porque falhou. */
    suspend fun procurar(): Resultado = withContext(Dispatchers.IO) {
        try {
            val con = (URL(api).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "${BuildConfig.NOME_APP}/${BuildConfig.VERSION_NAME}")
            }
            try {
                when (con.responseCode) {
                    200 -> {}
                    403, 429 -> return@withContext Resultado.Erro("o GitHub limitou os pedidos; tenta daqui a uns minutos")
                    else -> return@withContext Resultado.Erro("o GitHub respondeu ${con.responseCode}")
                }
                val todas = org.json.JSONArray(con.inputStream.bufferedReader().use { it.readText() })
                // A mais recente desta app (as Releases vêm da mais nova para a mais antiga)
                val json = (0 until todas.length()).map { todas.getJSONObject(it) }
                    .filter { !it.optBoolean("draft") && it.getString("tag_name").startsWith(BuildConfig.PREFIXO_RELEASE) }
                    .maxByOrNull { it.getString("tag_name").removePrefix(BuildConfig.PREFIXO_RELEASE).toIntOrNull() ?: 0 }
                    ?: return@withContext Resultado.Erro("ainda não há versões publicadas")
                val codigo = json.getString("tag_name").removePrefix(BuildConfig.PREFIXO_RELEASE).toIntOrNull()
                    ?: return@withContext Resultado.Erro("versão desconhecida (${json.optString("tag_name")})")
                if (codigo <= BuildConfig.VERSION_CODE) return@withContext Resultado.EmDia

                val lista = json.getJSONArray("assets")
                val assets = (0 until lista.length())
                    .map { lista.getJSONObject(it) }
                    .associate { it.getString("name") to it.getString("browser_download_url") }
                // Primeiro o APK só para o processador deste telemóvel (1/3 do tamanho);
                // se não houver, o APK universal
                val base = NOME_APK.removeSuffix(".apk")
                val url = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { assets["$base-$it.apk"] }
                    ?: assets[NOME_APK]
                    ?: return@withContext Resultado.Erro("a versão $codigo ainda não tem o APK")
                Resultado.Nova(Versao(codigo, "1.0.$codigo", url))
            } finally {
                con.disconnect()
            }
        } catch (e: Exception) {
            Resultado.Erro("sem ligação à Internet (${e.javaClass.simpleName})")
        }
    }

    private var ultimaProcura = 0L

    /** Para quando a app volta ao ecrã: no máximo uma procura a cada 2 minutos. */
    suspend fun verificarSeDevido(ctx: Context) {
        if (System.currentTimeMillis() - ultimaProcura < 2 * 60 * 1000L) return
        verificarEInstalar(ctx)
    }

    /** Versão nova encontrada, à espera de a pessoa a descarregar. */
    private var pendente: Versao? = null

    /**
     * Procura uma versão nova. A app já não se instala sozinha: o Play Protect trata
     * apps que descarregam e instalam APKs como perigosas. Quando há versão nova,
     * avisa no cabeçalho; ao tocar (ou com [manual]), o APK abre no browser e
     * instala-se pelo instalador do Android, como qualquer APK.
     */
    suspend fun verificarEInstalar(ctx: Context, manual: Boolean = false) {
        if (manual) pendente?.let { abrir(ctx, it); return }
        if (!emCurso.tryLock()) return
        ultimaProcura = System.currentTimeMillis()
        try {
            if (manual) _estado.value = "A procurar atualizações…"
            when (val r = procurar()) {
                is Resultado.EmDia -> {
                    pendente = null
                    if (manual) {
                        _estado.value = "Já tens a versão mais recente (${BuildConfig.VERSION_NAME})"
                        kotlinx.coroutines.delay(5000)
                        if (_estado.value?.startsWith("Já tens") == true) _estado.value = null
                    }
                }
                is Resultado.Erro -> if (manual) _estado.value = "Não foi possível procurar atualizações: ${r.motivo}"
                is Resultado.Nova -> {
                    pendente = r.versao
                    _estado.value = "Nova versão ${r.versao.nome} disponível · toca aqui para atualizar"
                    if (manual) abrir(ctx, r.versao)
                }
            }
        } catch (e: Exception) {
            if (manual) _estado.value = "Falha ao procurar atualizações: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            emCurso.unlock()
        }
    }

    /** Abre o APK no browser: descarrega-o e o Android pergunta se o queres instalar. */
    private fun abrir(ctx: Context, v: Versao) {
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(v.urlApk))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            _estado.value = "Abre o ficheiro descarregado para instalar a versão ${v.nome}"
        }.onFailure { _estado.value = "Não foi possível abrir o browser para descarregar a versão ${v.nome}" }
    }
}
