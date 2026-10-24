package pt.ipca.issafone.sip

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Uma linha do registo de chamadas. tipo: "feita", "recebida", "perdida" ou "recusada". */
data class Chamada(
    val numero: String,
    val nome: String,
    val tipo: String,
    val video: Boolean,
    val hora: Long,          // início, em milissegundos
    val duracao: Int         // segundos em conversa (0 se não foi atendida)
)

/**
 * Registo de chamadas guardado no telemóvel (um ficheiro por conta, últimas 200).
 */
class RegistoChamadas(context: Context) {

    private val appCtx = context.applicationContext
    private val prefs = appCtx.getSharedPreferences("issafone", Context.MODE_PRIVATE)
    private var ficheiro: File? = null
    private var conta = ""

    private val _lista = MutableStateFlow<List<Chamada>>(emptyList())
    val lista: StateFlow<List<Chamada>> = _lista

    /** Número de chamadas perdidas desde a última vez que se abriu o registo. */
    private val _perdidas = MutableStateFlow(0)
    val perdidas: StateFlow<Int> = _perdidas

    fun usarConta(c: String) {
        if (c == conta) return
        conta = c
        val f = File(appCtx.filesDir, "chamadas-" + c.replace(Regex("[^A-Za-z0-9@._-]"), "_") + ".json")
        ficheiro = f
        _lista.value = runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Chamada(o.getString("n"), o.optString("nome"), o.getString("t"), o.optBoolean("v"),
                    o.getLong("h"), o.optInt("d"))
            }
        }.getOrDefault(emptyList())
        contarPerdidas()
    }

    fun juntar(c: Chamada) {
        _lista.value = (listOf(c) + _lista.value).take(200)
        gravar()
        contarPerdidas()
    }

    fun limpar() {
        _lista.value = emptyList()
        gravar()
        contarPerdidas()
    }

    fun marcarVistas() {
        prefs.edit().putLong("chamadasVistas-$conta", System.currentTimeMillis()).apply()
        contarPerdidas()
    }

    private fun contarPerdidas() {
        val vistas = prefs.getLong("chamadasVistas-$conta", 0)
        _perdidas.value = _lista.value.count { it.tipo == "perdida" && it.hora > vistas }
    }

    private fun gravar() {
        val f = ficheiro ?: return
        val arr = JSONArray()
        _lista.value.forEach { c ->
            arr.put(JSONObject().apply {
                put("n", c.numero); put("nome", c.nome); put("t", c.tipo)
                put("v", c.video); put("h", c.hora); put("d", c.duracao)
            })
        }
        runCatching {
            val tmp = File(f.path + ".tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(f)
        }
    }
}
