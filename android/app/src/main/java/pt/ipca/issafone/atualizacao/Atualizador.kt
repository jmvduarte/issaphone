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

    /** Consulta o GitHub. Devolve a versão nova, ou null se já estiver atualizado. */
    suspend fun procurar(): Versao? = withContext(Dispatchers.IO) {
        val con = (URL(api).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "IssaFone/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (con.responseCode != 200) return@withContext null
            val json = JSONObject(con.inputStream.bufferedReader().use { it.readText() })
            val codigo = json.getString("tag_name").removePrefix("android-").toIntOrNull()
                ?: return@withContext null
            if (codigo <= BuildConfig.VERSION_CODE) return@withContext null

            val assets = json.getJSONArray("assets")
            val url = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name") == NOME_APK }
                ?.getString("browser_download_url")
                ?: return@withContext null
            Versao(codigo, "1.0.$codigo", url)
        } finally {
            con.disconnect()
        }
    }

    /**
     * Procura, descarrega e instala. Seguro para chamar várias vezes:
     * se já estiver uma atualização em curso, não faz nada.
     */
    suspend fun verificarEInstalar(ctx: Context) {
        if (!emCurso.tryLock()) return
        try {
            val nova = runCatching { procurar() }.getOrNull() ?: return
            _estado.value = "A descarregar a versão ${nova.nome}…"
            val apk = descarregar(ctx, nova)
            _estado.value = "A instalar a versão ${nova.nome}…"
            instalar(ctx, apk, nova)
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
