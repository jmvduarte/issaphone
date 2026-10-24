package pt.ipca.issafone.atualizacao

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pt.ipca.issafone.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualizações automáticas a partir das Releases do GitHub.
 *
 * Cada build do GitHub Actions publica a Release "android-N", com N = versionCode.
 * A app consulta a Release mais recente; se N for maior que a versão instalada,
 * descarrega o APK e instala-o com o PackageInstaller do Android.
 *
 * No Android 12+, depois de a app se ter atualizado a si própria uma vez, as
 * atualizações seguintes podem ser instaladas sem perguntar nada ao utilizador.
 */
object Atualizador {

    private const val NOME_APK = "IssaFone-debug.apk"
    private val api = "https://api.github.com/repos/${BuildConfig.REPO_ATUALIZACOES}/releases/latest"

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
                setRequestProperty("User-Agent", "IssaFone/${BuildConfig.VERSION_NAME}")
            }
            try {
                when (con.responseCode) {
                    200 -> {}
                    403, 429 -> return@withContext Resultado.Erro("o GitHub limitou os pedidos; tenta daqui a uns minutos")
                    else -> return@withContext Resultado.Erro("o GitHub respondeu ${con.responseCode}")
                }
                val json = JSONObject(con.inputStream.bufferedReader().use { it.readText() })
                val codigo = json.getString("tag_name").removePrefix("android-").toIntOrNull()
                    ?: return@withContext Resultado.Erro("versão desconhecida (${json.optString("tag_name")})")
                if (codigo <= BuildConfig.VERSION_CODE) return@withContext Resultado.EmDia

                val assets = json.getJSONArray("assets")
                val url = (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name") == NOME_APK }
                    ?.getString("browser_download_url")
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

    /**
     * Procura, descarrega e instala. Seguro para chamar várias vezes: se já estiver
     * uma atualização em curso, não faz nada. Com [manual] = true mostra sempre o
     * resultado (inclusive "já tens a versão mais recente").
     */
    suspend fun verificarEInstalar(ctx: Context, manual: Boolean = false) {
        if (!emCurso.tryLock()) return
        ultimaProcura = System.currentTimeMillis()
        try {
            if (manual) _estado.value = "A procurar atualizações…"
            when (val r = procurar()) {
                is Resultado.EmDia -> {
                    if (manual) {
                        _estado.value = "Já tens a versão mais recente (${BuildConfig.VERSION_NAME})"
                        kotlinx.coroutines.delay(5000)
                        if (_estado.value?.startsWith("Já tens") == true) _estado.value = null
                    }
                }
                is Resultado.Erro -> _estado.value = "Não foi possível procurar atualizações: ${r.motivo}"
                is Resultado.Nova -> {
                    val nova = r.versao
                    _estado.value = "A descarregar a versão ${nova.nome}…"
                    val apk = descarregar(ctx, nova)
                    _estado.value = "A instalar a versão ${nova.nome}…"
                    instalar(ctx, apk, nova)
                }
            }
        } catch (e: Exception) {
            _estado.value = "Falha na atualização: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            emCurso.unlock()
        }
    }

    private suspend fun descarregar(ctx: Context, v: Versao): File = withContext(Dispatchers.IO) {
        val destino = File(ctx.cacheDir, "atualizacao.apk")
        val con = (URL(v.urlApk).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            if (con.responseCode != 200) error("HTTP ${con.responseCode} ao descarregar")
            con.inputStream.use { entrada -> destino.outputStream().use { entrada.copyTo(it) } }
        } finally {
            con.disconnect()
        }
        destino
    }

    private suspend fun instalar(ctx: Context, apk: File, v: Versao) = withContext(Dispatchers.IO) {
        val instalador = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
        }
        val id = instalador.createSession(params)
        instalador.openSession(id).use { sessao ->
            sessao.openWrite("IssaFone.apk", 0, apk.length()).use { saida ->
                apk.inputStream().use { it.copyTo(saida) }
                sessao.fsync(saida)
            }
            // FLAG_MUTABLE: o sistema acrescenta o resultado ao Intent
            val resultado = PendingIntent.getBroadcast(
                ctx, id,
                Intent(ctx, ResultadoInstalacaoReceiver::class.java)
                    .putExtra(ResultadoInstalacaoReceiver.EXTRA_VERSAO, v.nome),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            sessao.commit(resultado.intentSender)
        }
    }
}
