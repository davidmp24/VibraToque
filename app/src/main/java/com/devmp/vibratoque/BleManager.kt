package com.devmp.vibratoque

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

// ═══════════════════════════════════════════════════════════════════════════
//  PROTOCOLO MI BAND 2/3/4/5/6/7 – baseado em engenharia reversa do
//  Gadgetbridge (HuamiService.java) e da comunidade XDA/Freeyourgadget.
//
//  AUTENTICAÇÃO em 3 etapas (obrigatória antes de qualquer comando):
//  1. App envia chave secreta de 16 bytes para UUID_AUTH (serviço FEE1)
//  2. Band responde com número aleatório de 16 bytes
//  3. App criptografa com AES-128 e reenvia → band confirma autenticação
//
//  VIBRAÇÃO: usa UUID_CHUNKED_TRANSFER (0x0016) no serviço FEE0
//  com payload proprietário de alerta.
// ═══════════════════════════════════════════════════════════════════════════

// Chave secreta compartilhada – Mi Band usa "Amazfit Auth Key" pré-definida
// Para dispositivos não vinculados ao app oficial, usamos a chave padrão de fábrica
private val MI_BAND_AUTH_KEY = byteArrayOf(
    0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37,
    0x38, 0x39, 0x40, 0x41, 0x42, 0x43, 0x44, 0x45
)

object MiBandUUIDs {
    // Serviço principal FEE0 (Mi Band 1/2/3/4/5/6/7)
    val SERVICE_MIBAND   = UUID.fromString("0000fee0-0000-1000-8000-00805f9b34fb")
    // Serviço de autenticação FEE1 (Mi Band 2+)
    val SERVICE_MIBAND2  = UUID.fromString("0000fee1-0000-1000-8000-00805f9b34fb")
    // Immediate Alert padrão BLE (fallback)
    val SERVICE_ALERT    = UUID.fromString("00001802-0000-1000-8000-00805f9b34fb")
    val CHAR_ALERT_LEVEL = UUID.fromString("00002a06-0000-1000-8000-00805f9b34fb")

    // Base UUID Mi Band proprietária: 0000XXXX-0000-3512-2118-0009af100700
    private fun miUUID(id: String) = UUID.fromString("0000${id}-0000-3512-2118-0009af100700")

    // Característica de autenticação (no serviço FEE1)
    val CHAR_AUTH        = miUUID("0009")
    // Característica de alerta/notificação (Mi Band 2/3/4/5/6) – serviço FEE0
    val CHAR_ALERT       = miUUID("0003") // config/notification char
    // Chunked transfer 2021 (Mi Band 5/6/7) – write
    val CHAR_CHUNKED_W   = miUUID("0016")
    // Chunked transfer 2021 – read/notify
    val CHAR_CHUNKED_R   = miUUID("0017")

    // Client Characteristic Configuration Descriptor (para habilitar notify)
    val DESC_CCCD        = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Bytes do protocolo de autenticação
    const val AUTH_SEND_KEY: Byte            = 0x01
    const val AUTH_REQUEST_RANDOM: Byte      = 0x02
    const val AUTH_SEND_ENCRYPTED: Byte      = 0x03
    const val AUTH_RESPONSE: Byte            = 0x10
    const val AUTH_SUCCESS: Byte             = 0x01
    const val AUTH_BYTE: Byte                = 0x08.toByte()

    // Payloads de alerta/vibração
    val VIBRATE_BAND     = byteArrayOf(0x03.toByte())            // Mi Band 1
    val ALERT_NONE       = byteArrayOf(0x00.toByte())
}

enum class MiBandIntensity { SUAVE, NORMAL, FORTE }
enum class BleState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, ERROR }
data class BleDevice(val name: String, val address: String)

@SuppressLint("MissingPermission")
object BleManager {

    private const val TAG = "BleManager"
    private var gatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private val handler = Handler(Looper.getMainLooper())

    private var pulseJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow(BleState.DISCONNECTED)
    val state: StateFlow<BleState> = _state

    private val _foundDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    val foundDevices: StateFlow<List<BleDevice>> = _foundDevices

    private val _connectedDevice = MutableStateFlow<BleDevice?>(null)
    val connectedDevice: StateFlow<BleDevice?> = _connectedDevice

    // Mensagem de erro legível para exibir no UI
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    val isConnected get() = _state.value == BleState.CONNECTED

    // Runnable do timeout de conexão
    private var connectTimeoutRunnable: Runnable? = null
    private val CONNECT_TIMEOUT_MS = 12_000L

    // ── Scan BLE ───────────────────────────────────────────────────────────
    fun startScan(context: Context) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) { _state.value = BleState.ERROR; return }
        _foundDevices.value = emptyList()
        _state.value = BleState.SCANNING
        scanner = adapter.bluetoothLeScanner
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                val addr = result.device.address
                val dev = BleDevice(name, addr)
                val cur = _foundDevices.value.toMutableList()
                if (cur.none { it.address == addr }) { cur.add(dev); _foundDevices.value = cur }
            }
        }
        scanner?.startScan(scanCallback)
        handler.postDelayed({
            stopScan()
            if (_state.value == BleState.SCANNING) _state.value = BleState.DISCONNECTED
        }, 15_000L)
    }

    fun stopScan() { scanner?.stopScan(scanCallback); scanCallback = null }

    // ── Conexão GATT ───────────────────────────────────────────────────────
    fun connect(context: Context, device: BleDevice) {
        stopScan(); disconnect()
        _errorMessage.value = null
        _state.value = BleState.CONNECTING
        Log.d(TAG, "Tentando conectar a ${device.name} (${device.address})")

        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        gatt = adapter.getRemoteDevice(device.address).connectGatt(
            context, false, gattCallback, BluetoothDevice.TRANSPORT_LE
        )

        // Timeout: se não conectar em 12s, é possível conflito com Zepp Life
        connectTimeoutRunnable?.let { handler.removeCallbacks(it) }
        connectTimeoutRunnable = Runnable {
            if (_state.value == BleState.CONNECTING) {
                Log.w(TAG, "Timeout de conexão! Possível conflito com Zepp Life/Mi Fitness.")
                gatt?.close()
                gatt = null
                _state.value = BleState.ERROR
                _errorMessage.value = "Não foi possível conectar diretamente.\n" +
                    "Feche o Zepp Life e tente novamente.\n" +
                    "Ou use o modo de Notificação (funciona junto ao Zepp Life)."
            }
        }.also { handler.postDelayed(it, CONNECT_TIMEOUT_MS) }
    }

    fun disconnect() {
        connectTimeoutRunnable?.let { handler.removeCallbacks(it) }
        connectTimeoutRunnable = null
        pulseJob?.cancel()
        gatt?.disconnect(); gatt?.close(); gatt = null
        _state.value = BleState.DISCONNECTED
        _connectedDevice.value = null
        _errorMessage.value = null
    }

    // ── Callback GATT ──────────────────────────────────────────────────────
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")

            // Cancela o timeout se houve resposta
            connectTimeoutRunnable?.let { handler.removeCallbacks(it) }
            connectTimeoutRunnable = null

            // status != 0 significa erro BLE. Código 133 = GATT_ERROR (tipicamente
            // "device busy" quando outro app já está conectado, ex: Zepp Life)
            if (status != BluetoothGatt.GATT_SUCCESS && newState != BluetoothProfile.STATE_CONNECTED) {
                Log.w(TAG, "Falha de conexão GATT status=$status. Possível conflito com outro app.")
                g.close(); gatt = null
                _state.value = BleState.ERROR
                _errorMessage.value = when (status) {
                    133 -> "Conexão bloqueada (erro 133).\nO Zepp Life está usando a pulseira.\nFeche o Zepp Life e tente novamente."
                    8   -> "Conexão recusada pela pulseira (erro 8).\nTente novamente."
                    else -> "Erro de conexão BLE ($status).\nVerifique se outro app usa a pulseira."
                }
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT conectado → descobrindo serviços…")
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT desconectado")
                    _state.value = BleState.DISCONNECTED
                    _connectedDevice.value = null
                    gatt = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { _state.value = BleState.ERROR; return }

            Log.d(TAG, "=== Serviços descobertos ===")
            g.services.forEach { svc ->
                Log.d(TAG, "SVC: ${svc.uuid}")
                svc.characteristics.forEach { c ->
                    Log.d(TAG, "  CHAR: ${c.uuid}  props=0b${c.properties.toString(2)}")
                }
            }

            // Determina qual estratégia usar
            val hasAuth   = g.getService(MiBandUUIDs.SERVICE_MIBAND2)
                ?.getCharacteristic(MiBandUUIDs.CHAR_AUTH) != null
            val hasChunked = g.getService(MiBandUUIDs.SERVICE_MIBAND)
                ?.getCharacteristic(MiBandUUIDs.CHAR_CHUNKED_W) != null
            val hasAlert  = g.getService(MiBandUUIDs.SERVICE_ALERT)
                ?.getCharacteristic(MiBandUUIDs.CHAR_ALERT_LEVEL) != null

            Log.d(TAG, "hasAuth=$hasAuth  hasChunked=$hasChunked  hasAlert=$hasAlert")

            if (hasAuth) {
                // Mi Band 2/3/4/5/6/7: precisa autenticar primeiro
                startAuthentication(g)
            } else if (hasAlert) {
                // Mi Band 1 / dispositivo BLE genérico
                Log.d(TAG, "Sem auth necessária – conectando direto via Immediate Alert")
                _state.value = BleState.CONNECTED
                _connectedDevice.value = BleDevice(g.device.name ?: "Mi Band", g.device.address)
            } else {
                Log.w(TAG, "Dispositivo sem serviço de vibração reconhecido")
                _state.value = BleState.CONNECTED
                _connectedDevice.value = BleDevice(g.device.name ?: "Desconhecido", g.device.address)
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.value ?: return
            Log.d(TAG, "onCharacteristicChanged ${characteristic.uuid}: ${data.toHex()}")
            if (characteristic.uuid == MiBandUUIDs.CHAR_AUTH) {
                handleAuthResponse(g, data)
            }
        }

        // API 33+ usa este callback
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            Log.d(TAG, "onCharacteristicChanged33 ${characteristic.uuid}: ${value.toHex()}")
            if (characteristic.uuid == MiBandUUIDs.CHAR_AUTH) {
                handleAuthResponse(g, value)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            Log.d(TAG, "onWrite ${char.uuid} status=$status")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "onDescriptorWrite ${descriptor.uuid} status=$status")
            if (descriptor.uuid == MiBandUUIDs.DESC_CCCD && status == BluetoothGatt.GATT_SUCCESS) {
                // Depois de habilitar notify, envia passo 1 da auth
                Log.d(TAG, "Notify habilitado → enviando chave de autenticação")
                sendAuthKey(g)
            }
        }
    }

    // ── Autenticação Mi Band 2+ (3 etapas) ────────────────────────────────
    private fun startAuthentication(g: BluetoothGatt) {
        val authChar = g.getService(MiBandUUIDs.SERVICE_MIBAND2)
            ?.getCharacteristic(MiBandUUIDs.CHAR_AUTH) ?: run {
            Log.e(TAG, "CHAR_AUTH não encontrada"); return
        }
        // Passo 0: habilita notify na característica de auth
        g.setCharacteristicNotification(authChar, true)
        val desc = authChar.getDescriptor(MiBandUUIDs.DESC_CCCD)
        if (desc != null) {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(desc)
            }
        } else {
            // Sem descriptor CCCD – tenta direto
            sendAuthKey(g)
        }
    }

    // Passo 1: envia a chave secreta
    private fun sendAuthKey(g: BluetoothGatt) {
        val payload = byteArrayOf(MiBandUUIDs.AUTH_SEND_KEY, MiBandUUIDs.AUTH_BYTE) + MI_BAND_AUTH_KEY
        Log.d(TAG, "AUTH step1 → enviando chave: ${payload.toHex()}")
        writeAuth(g, payload)
    }

    // Passo 3: recebe número aleatório e retorna criptografado
    private fun handleAuthResponse(g: BluetoothGatt, data: ByteArray) {
        if (data.size < 3) return
        val cmd  = data[0]
        val step = data[1]
        val code = data[2]
        Log.d(TAG, "AUTH response: cmd=0x${cmd.toHex()} step=0x${step.toHex()} code=0x${code.toHex()}")

        when {
            // Resposta ao passo 1 → solicita número aleatório (passo 2)
            cmd == MiBandUUIDs.AUTH_RESPONSE && step == MiBandUUIDs.AUTH_SEND_KEY && code == MiBandUUIDs.AUTH_SUCCESS -> {
                Log.d(TAG, "AUTH step1 OK → solicitando número aleatório")
                writeAuth(g, byteArrayOf(MiBandUUIDs.AUTH_REQUEST_RANDOM, MiBandUUIDs.AUTH_BYTE))
            }
            // Resposta ao passo 2 → criptografa e envia (passo 3)
            cmd == MiBandUUIDs.AUTH_RESPONSE && step == MiBandUUIDs.AUTH_REQUEST_RANDOM && code == MiBandUUIDs.AUTH_SUCCESS -> {
                val randomNumber = data.copyOfRange(3, data.size)
                Log.d(TAG, "AUTH step2 OK → random=${randomNumber.toHex()} → cifrando…")
                val encrypted = aesEncrypt(randomNumber, MI_BAND_AUTH_KEY)
                writeAuth(g, byteArrayOf(MiBandUUIDs.AUTH_SEND_ENCRYPTED, MiBandUUIDs.AUTH_BYTE) + encrypted)
            }
            // Resposta ao passo 3 → autenticação completa!
            cmd == MiBandUUIDs.AUTH_RESPONSE && step == MiBandUUIDs.AUTH_SEND_ENCRYPTED && code == MiBandUUIDs.AUTH_SUCCESS -> {
                Log.d(TAG, "✅ AUTH completa! Dispositivo pronto.")
                val devName = gatt?.device?.name ?: "Mi Band"
                val devAddr = gatt?.device?.address ?: ""
                _state.value = BleState.CONNECTED
                _connectedDevice.value = BleDevice(devName, devAddr)
            }
            // Falha de auth – chave errada
            code != MiBandUUIDs.AUTH_SUCCESS -> {
                Log.e(TAG, "❌ AUTH falhou! code=0x${code.toHex()}. Pulseira rejeitou a chave.")
                g.close(); gatt = null
                _state.value = BleState.ERROR
                _errorMessage.value = "A pulseira recusou a conexão direta (erro de Auth).\n\n" +
                    "A Xiaomi bloqueia comandos diretos se o app não tiver a 'Auth Key' secreta gerada pelo Zepp Life.\n\n" +
                    "Por favor, ative o modo 'Notificação' para vibrar."
            }
        }
    }

    private fun writeAuth(g: BluetoothGatt, payload: ByteArray) {
        val authChar = g.getService(MiBandUUIDs.SERVICE_MIBAND2)
            ?.getCharacteristic(MiBandUUIDs.CHAR_AUTH) ?: return
        writeChar(g, authChar, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    // ── Vibração ───────────────────────────────────────────────────────────
    fun vibrar(durationMs: Long, level: Byte = 0x02): Boolean {
        val g = gatt ?: run { Log.w(TAG, "vibrar() chamado sem GATT conectado"); return false }

        Log.d(TAG, "vibrar() durationMs=$durationMs  serviços=${g.services.map { it.uuid.toString().takeLast(8) }}")

        // Estratégia 1: Chunked transfer 0x0016 (Mi Band 5/6/7)
        val chunkedChar = g.getService(MiBandUUIDs.SERVICE_MIBAND)
            ?.getCharacteristic(MiBandUUIDs.CHAR_CHUNKED_W)
        if (chunkedChar != null) {
            Log.d(TAG, "Vibrando via CHAR_CHUNKED_W (0x0016)")
            // Payload de notificação: tipo=0x05 (text alert), subtipo=0x00
            // Formato: [tipo, flags, seq, cmd, ...data]
            // Para vibrar: usa comando de alerta de ligação (0x05, 0x01)
            val payload = buildVibrationPayload(durationMs)
            return writeChar(g, chunkedChar, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }

        // Estratégia 2: Char de configuração 0x0003 (Mi Band 2/3/4)
        val alertChar = g.getService(MiBandUUIDs.SERVICE_MIBAND)
            ?.getCharacteristic(MiBandUUIDs.CHAR_ALERT)
        if (alertChar != null) {
            Log.d(TAG, "Vibrando via CHAR_ALERT 0x0003 (Mi Band 2/3/4)")
            // Formato Mi Band 2/3/4: [0x05, 0x01] = phone call alert → vibra
            val sent = writeChar(g, alertChar, byteArrayOf(0x05, 0x01),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            if (sent) {
                handler.postDelayed({
                    gatt?.getService(MiBandUUIDs.SERVICE_MIBAND)
                        ?.getCharacteristic(MiBandUUIDs.CHAR_ALERT)
                        ?.let { writeChar(g, it, byteArrayOf(0x05, 0x00),
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) }
                }, durationMs)
                return true
            }
        }

        // Estratégia 3: Immediate Alert padrão BLE (fallback universal)
        val immAlert = g.getService(MiBandUUIDs.SERVICE_ALERT)
            ?.getCharacteristic(MiBandUUIDs.CHAR_ALERT_LEVEL)
        if (immAlert != null) {
            Log.d(TAG, "Vibrando via Immediate Alert 0x1802 (fallback)")
            val sent = writeChar(g, immAlert, byteArrayOf(level),
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            if (sent) {
                handler.postDelayed({
                    gatt?.getService(MiBandUUIDs.SERVICE_ALERT)
                        ?.getCharacteristic(MiBandUUIDs.CHAR_ALERT_LEVEL)
                        ?.let { writeChar(g, it, MiBandUUIDs.ALERT_NONE,
                            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) }
                }, durationMs)
                return true
            }
        }

        Log.w(TAG, "Nenhum serviço de vibração encontrado! Serviços: ${g.services.map { it.uuid }}")
        return false
    }

    // Monta payload de vibração para protocolo chunked (Mi Band 5/6/7)
    private fun buildVibrationPayload(durationMs: Long): ByteArray {
        // Phone call alert: faz a pulseira vibrar com padrão de chamada
        // [0x05, 0x01] = alertType=phone_call, on=true
        return byteArrayOf(0x05.toByte(), 0x01.toByte())
    }

    // ── Vibração pulsada (modulação duty cycle) ───────────────────────────
    fun vibrarPulsado(totalDurationMs: Long, intensity: MiBandIntensity): Boolean {
        if (gatt == null) return false
        val (onMs, offMs) = when (intensity) {
            MiBandIntensity.SUAVE  -> Pair(80L, 180L)
            MiBandIntensity.NORMAL -> Pair(totalDurationMs, 0L)
            MiBandIntensity.FORTE  -> Pair(120L, 50L)
        }
        if (intensity == MiBandIntensity.NORMAL) return vibrar(totalDurationMs)
        pulseJob?.cancel()
        pulseJob = scope.launch {
            val end = System.currentTimeMillis() + totalDurationMs
            while (System.currentTimeMillis() < end && isActive) {
                val rem = end - System.currentTimeMillis()
                val on = onMs.coerceAtMost(rem)
                if (on <= 0) break
                vibrar(on)
                delay(on)
                if (offMs > 0 && System.currentTimeMillis() < end) delay(offMs)
            }
        }
        return true
    }

    // ── Helpers ───────────────────────────────────────────────────────────
    @Suppress("DEPRECATION")
    private fun writeChar(
        g: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        value: ByteArray,
        type: Int
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            val r = g.writeCharacteristic(char, value, type)
            Log.d(TAG, "writeChar33 ${char.uuid.toString().takeLast(8)} type=$type result=$r")
            r == BluetoothStatusCodes.SUCCESS
        } else {
            char.value = value
            char.writeType = type
            val r = g.writeCharacteristic(char)
            Log.d(TAG, "writeChar26 ${char.uuid.toString().takeLast(8)} type=$type result=$r")
            r
        }
    }

    private fun aesEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun Byte.toHex() = "%02x".format(this)
}
