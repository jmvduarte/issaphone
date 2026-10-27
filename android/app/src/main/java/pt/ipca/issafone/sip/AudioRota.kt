package pt.ipca.issafone.sip

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Encaminhamento do som das chamadas ao nível do Android.
 *
 * A Linphone sozinha não pedia o "foco de áudio" (a música do carro continuava a tocar)
 * e nem sempre via o Bluetooth do carro/auriculares. Aqui, como faz a app de telefone:
 *  - pede o foco de áudio de chamada (a música pára e volta no fim);
 *  - põe o telemóvel em modo de comunicação;
 *  - escolhe o Bluetooth (perfil de mãos-livres) se houver; senão o auscultador.
 */
class AudioRota(context: Context) {

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var foco: AudioFocusRequest? = null
    private var ativa = false

    private val atributos = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** Há um dispositivo Bluetooth de mãos-livres (carro, auriculares) ligado? */
    fun temBluetooth(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.availableCommunicationDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        } else {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        }

    /** Chamada atendida: foco de áudio + Bluetooth se houver. */
    fun iniciar() {
        if (!ativa) {
            ativa = true
            foco = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(atributos)
                .build()
                .also { am.requestAudioFocus(it) }
            am.mode = AudioManager.MODE_IN_COMMUNICATION
        }
        // Depois da Linphone ter escolhido a saída dela (senão podia voltar a pôr o auscultador)
        handler.postDelayed({ if (ativa) usarAltifalante(false) }, 600)
    }

    /**
     * [ligar] = true → altifalante. false → Bluetooth se houver, senão auscultador.
     * Devolve true se ficou no Bluetooth.
     */
    fun usarAltifalante(ligar: Boolean): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val disp = am.availableCommunicationDevices
            val alvo = if (ligar) disp.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            else disp.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                ?: disp.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
                ?: disp.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            if (alvo != null) am.setCommunicationDevice(alvo)
            return alvo?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        } else {
            @Suppress("DEPRECATION")
            if (ligar) {
                am.stopBluetoothSco(); am.isBluetoothScoOn = false; am.isSpeakerphoneOn = true
                return false
            }
            am.isSpeakerphoneOn = false
            if (temBluetooth()) { am.startBluetoothSco(); am.isBluetoothScoOn = true; return true }
            return false
        }
    }

    /** Fim de todas as chamadas: devolve o som (a música volta). */
    fun terminar() {
        if (!ativa) return
        ativa = false
        handler.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
        else {
            @Suppress("DEPRECATION")
            run { am.stopBluetoothSco(); am.isBluetoothScoOn = false; am.isSpeakerphoneOn = false }
        }
        am.mode = AudioManager.MODE_NORMAL
        foco?.let { am.abandonAudioFocusRequest(it) }
        foco = null
    }
}
