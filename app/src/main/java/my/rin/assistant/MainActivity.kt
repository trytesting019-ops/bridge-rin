package my.rin.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaRecorder
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.io.File
import android.util.Base64

private val Night = Color(0xFF090B18)
private val Panel = Color(0xFF171A2D)
private val Violet = Color(0xFF9B7BFF)
private val Cyan = Color(0xFF69D9F5)
private const val RIN_CHAT_URL = "https://rin-bridge.trytesting019.workers.dev/v1/chat"

private fun isExternalWritingInstruction(text: String): Boolean {
    val value = text.lowercase()
    val target = listOf("calendar", "kalendar", "app lain", "apk lain", "aplikasi lain", "aplikasi tersebut")
        .any { value.contains(it) }
    val action = listOf("tulis", "masuk", "masukkan", "simpan", "catat", "letak", "buat", "hantar", "add", "write", "save", "create")
        .any { value.contains(it) }
    return target && action
}

data class ChatLine(val text: String, val fromUser: Boolean, val showInChat: Boolean = true)
data class RinInstalledApp(val label: String, val packageName: String)

private suspend fun requestRinReply(deviceToken: String, history: List<ChatLine>): String =
    withContext(Dispatchers.IO) {
        val connection = (URL(RIN_CHAT_URL).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 65000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $deviceToken")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")

            val contents = JSONArray()
            history.takeLast(16).forEach { line ->
                contents.put(
                    JSONObject()
                        .put("role", if (line.fromUser) "user" else "model")
                        .put("text", line.text)
                )
            }
            val payload = JSONObject().put("messages", contents).toString()
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val response = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }

            if (status !in 200..299) {
                val error = response.optString("message")
                    .ifBlank { response.optString("error", "HTTP $status") }
                throw IllegalStateException(error)
            }

            response.optString("reply").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("AI tidak memulangkan jawapan.")
        } finally {
            connection.disconnect()
        }
    }

private suspend fun requestRinTranscript(deviceToken: String, audioFile: File): String =
    withContext(Dispatchers.IO) {
        val connection = (URL("https://rin-bridge.trytesting019.workers.dev/v1/transcribe").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 40000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $deviceToken")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val audio = Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)
            val payload = JSONObject().put("mimeType", "audio/mp4").put("audio", audio).toString()
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val response = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
            if (status !in 200..299) throw IllegalStateException(response.optString("message").ifBlank { response.optString("error", "HTTP $status") })
            response.optString("transcript").takeIf { it.isNotBlank() } ?: throw IllegalStateException("Transkripsi kosong.")
        } finally { connection.disconnect() }
    }

data class RinSpeechAudio(val bytes: ByteArray, val mimeType: String, val sampleRate: Int = 24000)

private suspend fun requestRinSpeech(deviceToken: String, text: String): RinSpeechAudio =
    withContext(Dispatchers.IO) {
        val connection = (URL("https://rin-bridge.trytesting019.workers.dev/v1/tts").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 45000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $deviceToken")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            val payload = JSONObject().put("text", text.take(3000)).toString()
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val response = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
            if (status !in 200..299) {
                val detail = response.optString("message").ifBlank { response.optString("error", "HTTP $status") }
                throw IllegalStateException("Servis suara gagal: $detail")
            }
            val encoded = response.optString("audio").ifBlank { response.optString("audioBase64") }
            if (encoded.isBlank()) {
                val detail = response.optString("message").ifBlank { response.optString("error") }
                throw IllegalStateException(if (detail.isNotBlank()) detail else "Servis TTS tidak memulangkan data audio (medan audio kosong).")
            }
            val mime = response.optString("mimeType")
                .ifBlank { response.optString("mime_type") }
                .ifBlank { "audio/L16;rate=24000" }
            val rateFromMime = Regex("rate\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(mime)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val sampleRate = response.optInt("sampleRate", rateFromMime ?: 24000).coerceIn(8000, 48000)
            val bytes = try { Base64.decode(encoded, Base64.DEFAULT) }
                catch (e: Exception) { throw IllegalStateException("Data audio TTS bukan Base64 yang sah.", e) }
            if (bytes.isEmpty()) throw IllegalStateException("Data audio TTS kosong.")
            RinSpeechAudio(bytes, mime, sampleRate)
        } finally {
            connection.disconnect()
        }
    }

private suspend fun requestRinSpeechCached(cacheRoot: File, deviceToken: String, text: String): RinSpeechAudio =
    withContext(Dispatchers.IO) {
        val cacheDir = File(cacheRoot, "rin_tts_cache")
        if (!cacheDir.exists() && !cacheDir.mkdirs()) return@withContext requestRinSpeech(deviceToken, text)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(("sulafat:" + text).toByteArray(Charsets.UTF_8))
        val key = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val cached = File(cacheDir, "$key.audio")
        val mimeFile = File(cacheDir, "$key.mime")
        if (cached.isFile && cached.length() > 0L && mimeFile.isFile) {
            val metadata = mimeFile.readText().split("|")
            val mime = metadata.getOrNull(0).orEmpty().ifBlank { "audio/L16;rate=24000" }
            val rate = metadata.getOrNull(1)?.toIntOrNull() ?: 24000
            cached.setLastModified(System.currentTimeMillis())
            return@withContext RinSpeechAudio(cached.readBytes(), mime, rate)
        }
        val audio = requestRinSpeech(deviceToken, text)
        val temporary = File(cacheDir, "$key.tmp")
        temporary.writeBytes(audio.bytes)
        if (temporary.renameTo(cached)) {
            mimeFile.writeText(audio.mimeType + "|" + audio.sampleRate)
        } else temporary.delete()
        cacheDir.listFiles()?.filter { it.isFile && (it.name.endsWith(".audio") || it.name.endsWith(".mime")) }
            ?.groupBy { it.name.substringBeforeLast('.') }
            ?.entries?.sortedByDescending { entry -> File(cacheDir, entry.key + ".audio").lastModified() }
            ?.drop(30)?.forEach { entry ->
                File(cacheDir, entry.key + ".audio").delete()
                File(cacheDir, entry.key + ".mime").delete()
            }
        audio
    }

private fun pcm16BigEndianToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
    val usableLength = pcm.size - (pcm.size % 2)
    val output = java.io.ByteArrayOutputStream(44 + usableLength)
    fun writeAscii(value: String) { output.write(value.toByteArray(Charsets.US_ASCII)) }
    fun writeInt(value: Int) {
        output.write(value and 0xff); output.write((value shr 8) and 0xff)
        output.write((value shr 16) and 0xff); output.write((value shr 24) and 0xff)
    }
    fun writeShort(value: Int) { output.write(value and 0xff); output.write((value shr 8) and 0xff) }
    writeAscii("RIFF"); writeInt(36 + usableLength); writeAscii("WAVE")
    writeAscii("fmt "); writeInt(16); writeShort(1); writeShort(1)
    writeInt(sampleRate); writeInt(sampleRate * 2); writeShort(2); writeShort(16)
    writeAscii("data"); writeInt(usableLength)
    var i = 0
    while (i < usableLength) {
        output.write(pcm[i + 1].toInt() and 0xff)
        output.write(pcm[i].toInt() and 0xff)
        i += 2
    }
    return output.toByteArray()
}

class MainActivity : ComponentActivity() {
    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RinApp() }
    }

    @Volatile private var voiceTrack: AudioTrack? = null

    fun startVoiceForegroundService(): Boolean {
        return try {
            val intent = Intent(this, RinVoiceForegroundService::class.java)
            ContextCompat.startForegroundService(this, intent)
            true
        } catch (exception: Exception) {
            false
        }
    }

    fun stopVoiceForegroundService() {
        stopService(Intent(this, RinVoiceForegroundService::class.java))
    }

    fun startLiveSpeech(
        onReady: () -> Unit,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            onError("Perkhidmatan pengecaman suara Android tidak tersedia. Pasang atau aktifkan Speech Services dan bahasa Melayu.")
            return false
        }
        return try {
            speechRecognizer?.let { old -> runCatching { old.cancel(); old.destroy() } }
            val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { onReady() }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) {
                    val words = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    if (words.isNotBlank()) onPartial(words)
                }
                override fun onResults(results: Bundle?) {
                    if (speechRecognizer !== recognizer) return
                    val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim().orEmpty()
                    speechRecognizer = null
                    runCatching { recognizer.destroy() }
                    if (words.isNotBlank()) onFinal(words) else onError("SILENCE")
                }
                override fun onError(error: Int) {
                    if (speechRecognizer !== recognizer) return
                    speechRecognizer = null
                    runCatching { recognizer.destroy() }
                    if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                        error == SpeechRecognizer.ERROR_CLIENT) {
                        onError("SILENCE")
                    } else if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
                        onError("Bahasa Melayu tidak disokong oleh pakej pengecaman luar talian. Rin kini menggunakan pengecaman dalam talian; semak internet dan kemas kini Speech Services by Google.")
                    } else {
                        onError("Pengecaman suara gagal (kod $error). Semak Speech Services dan kebenaran mikrofon.")
                    }
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ms-MY")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ms-MY")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            }
            recognizer.startListening(intent)
            true
        } catch (exception: Exception) {
            speechRecognizer?.let { runCatching { it.destroy() } }
            speechRecognizer = null
            onError("Mikrofon tidak dapat dimulakan: ${exception.localizedMessage ?: "ralat tidak diketahui"}")
            false
        }
    }

    fun stopLiveSpeech() {
        speechRecognizer?.let { recognizer ->
            speechRecognizer = null
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
        }
    }

    override fun onDestroy() {
        stopLiveSpeech()
        super.onDestroy()
    }

    fun playRinVoice(audio: RinSpeechAudio, onStart: () -> Unit, onComplete: () -> Unit, onError: (String) -> Unit) {
        try {
            val mime = audio.mimeType
            if (!mime.startsWith("audio/l16", ignoreCase = true)) {
                throw IllegalStateException("Format bukan PCM 16-bit: $mime")
            }
            val rate = audio.sampleRate.coerceIn(8000, 48000)
            val channels = Regex("channels=(\\d+)", RegexOption.IGNORE_CASE).find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val pcm = audio.bytes
            if (channels != 1 || pcm.isEmpty() || pcm.size % 2 != 0) {
                throw IllegalStateException("Data PCM tidak sah; audio mono PCM16 diperlukan.")
            }
            val minBuffer = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) throw IllegalStateException("Sample rate PCM tidak disokong: $rate Hz")
            voiceTrack?.let { previous ->
                runCatching {
                    if (previous.playState == AudioTrack.PLAYSTATE_PLAYING) previous.pause()
                    previous.flush()
                    previous.stop()
                    previous.release()
                }
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(maxOf(minBuffer, pcm.size))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            voiceTrack = track
            Thread({
                try {
                    val written = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                    if (written != pcm.size) throw IllegalStateException("Data PCM tidak lengkap: $written daripada ${pcm.size} bait")
                    track.play()
                    runOnUiThread { onStart() }
                    val frames = pcm.size / 2
                    while (track.playState == AudioTrack.PLAYSTATE_PLAYING &&
                        track.playbackHeadPosition.toLong() < frames.toLong()) {
                        Thread.sleep(20)
                    }
                    runOnUiThread {
                        if (voiceTrack === track) voiceTrack = null
                        onComplete()
                    }
                } catch (exception: Exception) {
                    runOnUiThread {
                        if (voiceTrack === track) voiceTrack = null
                        onError("Suara Sulafat gagal dimainkan: ${exception.localizedMessage ?: "ralat PCM"}")
                    }
                } finally {
                    runCatching { if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop() }
                    runCatching { track.release() }
                }
            }, "RinSulafatPlayback").start()
        } catch (exception: Exception) {
            onError("Suara Sulafat gagal dimainkan: ${exception.localizedMessage ?: "ralat audio"}")
        }
    }

    fun getInstalledLaunchableApps(): List<RinInstalledApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { info ->
                val label = info.loadLabel(packageManager)?.toString()?.trim().orEmpty()
                val packageName = info.activityInfo?.packageName.orEmpty()
                if (label.isBlank() || packageName.isBlank()) null
                else RinInstalledApp(label, packageName)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun launchInstalledApp(packageName: String): Boolean {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        return true
    }

    fun openAppFromCommand(command: String): String? {
        // Remove the wake phrase before interpreting the actual user command.
        var normalized = command.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        normalized = normalized
            .replace(Regex("^(hai|hi|hey|hello)\\s+rin\\b\\s*"), "")
            .replace(Regex("^rin\\b\\s*"), "")
            // Accept natural polite Malay/English phrasing from speech transcription.
            .replace(Regex("^(tolong|sila|boleh tak|boleh ke|boleh|cuba|please|can you|could you)\\s+"), "")
            .trim()

        // Only launch an app when the user explicitly asks to open/run one.
        // Ordinary conversation such as "ya betul" or "call Zailani" must
        // continue to the assistant instead of being treated as app names.
        val openWords = listOf("buka", "bukakan", "bukalah", "bukakanlah", "launch", "open", "jalankan")
        var requested = normalized
        val openWord = openWords.firstOrNull { requested == it || requested.startsWith("$it ") }
            ?: return null
        if (requested == openWord) return null
        requested = requested.removePrefix("$openWord ").trim()
        requested = requested.removePrefix("aplikasi ").removePrefix("app ").trim()
            .removeSuffix(" sekarang").removeSuffix(" ya").trim()
        if (requested.isBlank()) return null

        val aliases = mapOf(
            "wa" to "whatsapp", "fb" to "facebook", "ig" to "instagram",
            "yt" to "youtube", "google map" to "maps", "google maps" to "maps",
            "galeri" to "gallery", "gambar" to "gallery", "kamera" to "camera",
            "tetapan" to "settings", "kalendar" to "calendar", "mesej" to "messages",
            "telefon" to "phone", "panggilan" to "phone", "fail" to "files",
            "pengurus fail" to "files"
        )
        val target = aliases[requested] ?: requested
        val matching = getInstalledLaunchableApps().map { app ->
            val label = app.label.lowercase()
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ").trim()
            val score = when {
                label == target -> 0
                label.startsWith("$target ") -> 1
                label.contains(target) -> 2
                app.packageName.substringAfterLast('.').replace("_", " ").contains(target.replace(" ", "")) -> 3
                else -> 100
            }
            app to score
        }.filter { it.second < 100 }.sortedBy { it.second }.firstOrNull()?.first

        if (matching != null && launchInstalledApp(matching.packageName)) return matching.label
        return "NOT_INSTALLED:$requested"
    }

}

@Composable
private fun RinAvatar(isListening: Boolean, isThinking: Boolean, isSpeaking: Boolean) {
    val transition = rememberInfiniteTransition()
    val pulse by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = if (isSpeaking) 1.075f else if (isListening) 1.045f else 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isSpeaking) 360 else 1350),
            repeatMode = RepeatMode.Reverse
        )
    )
    val glow by transition.animateFloat(
        initialValue = 0.30f,
        targetValue = if (isSpeaking) 0.95f else if (isListening) 0.75f else 0.48f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isSpeaking) 300 else 1200),
            repeatMode = RepeatMode.Reverse
        )
    )
    val state = when {
        isSpeaking -> "RIN SEDANG BERCAKAP"
        isThinking -> "RIN SEDANG BERFIKIR"
        isListening -> "RIN SEDANG MENDENGAR"
        else -> "RIN SEDIA"
    }
    val accent = if (isSpeaking) Cyan else Violet
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(158.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }
                .background(Brush.radialGradient(listOf(Cyan.copy(alpha = glow * 0.30f), Violet.copy(alpha = glow * 0.12f), Color.Transparent)), CircleShape)
                .border(2.dp, accent.copy(alpha = glow), CircleShape).padding(7.dp)
                .background(Panel, CircleShape).padding(5.dp).clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Image(painter = painterResource(id = R.drawable.rin_launcher), contentDescription = "Avatar Rin",
                modifier = Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.height(8.dp))
        Text(state, color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        if (isSpeaking) {
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(5) { index ->
                    val bar by transition.animateFloat(
                        initialValue = 5f, targetValue = (10 + ((index * 7) % 15)).toFloat(),
                        animationSpec = infiniteRepeatable(animation = tween(230 + index * 65), repeatMode = RepeatMode.Reverse)
                    )
                    Box(Modifier.width(4.dp).height(bar.dp).background(Cyan, RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}

@Composable
fun RinApp() {
    var draft by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    var voiceConversationEnabled by remember { mutableStateOf(false) }
    var isThinking by remember { mutableStateOf(false) }
    var isSpeaking by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf("AI dalam talian belum diuji pada peranti.") }
    var showSettings by remember { mutableStateOf(false) }
    var showAppsDialog by remember { mutableStateOf(false) }
    var appSearch by remember { mutableStateOf("") }
    var installedApps by remember { mutableStateOf(emptyList<RinInstalledApp>()) }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var attachedImage by remember { mutableStateOf<Bitmap?>(null) }
    val activity = androidx.compose.ui.platform.LocalContext.current as MainActivity
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember { context.getSharedPreferences("rin_private", android.content.Context.MODE_PRIVATE) }
    var deviceToken by remember { mutableStateOf(preferences.getString("device_token", "") ?: "") }
    val scope = rememberCoroutineScope()
    val appRefreshReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                installedApps = activity.getInstalledLaunchableApps()
            }
        }
    }
    LaunchedEffect(Unit) {
        installedApps = activity.getInstalledLaunchableApps()
    }
    DisposableEffect(context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, appRefreshReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(appRefreshReceiver) } }
    }
    val filteredInstalledApps = installedApps.filter {
        appSearch.isBlank() || it.label.contains(appSearch, ignoreCase = true) ||
            it.packageName.contains(appSearch, ignoreCase = true)
    }
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            attachedImage = runCatching { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
            voiceStatus = if (attachedImage != null) "Gambar dipilih. Sokongan hantar gambar kepada AI sedang disiapkan." else "Gambar tidak dapat dibuka."
        }
    }
    val cameraPicker = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            attachedImage = bitmap
            voiceStatus = "Gambar kamera dipilih. Sokongan hantar gambar kepada AI sedang disiapkan."
        }
    }
    val messages = remember {
        mutableStateListOf(ChatLine("Hai. Rin sedia membantu.", false))
    }

    var startListeningNow: (() -> Unit)? = null

    val sendMessage: (String, Boolean) -> Unit = { rawValue, fromVoice ->
        val value = rawValue.trim()
        if (value.isNotEmpty()) {
            val launchedApp = activity.openAppFromCommand(value)
            if (launchedApp != null) {
                if (launchedApp.startsWith("NOT_INSTALLED:")) {
                    val appName = launchedApp.substringAfter(":")
                    voiceStatus = "Aplikasi $appName tidak ditemui atau belum dipasang."
                    messages.add(ChatLine(voiceStatus, false))
                } else {
                    voiceStatus = "Membuka $launchedApp..."
                    messages.add(ChatLine("Baik, Rin sedang membuka $launchedApp.", false))
                }
                draft = ""
                if (voiceConversationEnabled) scope.launch { kotlinx.coroutines.delay(700); startListeningNow?.invoke() }
            } else if (deviceToken.isBlank()) {
                voiceStatus = "Masukkan DEVICE_TOKEN melalui Tetapan AI dahulu."
                showSettings = true
            } else {
                val history = messages.toList() + ChatLine(value, true, !fromVoice)
                messages.add(ChatLine(value, true, !fromVoice))
                messages.add(ChatLine("Rin sedang berfikir...", false))
                draft = ""
                isThinking = true
                voiceStatus = "Menghubungi Rin AI..."
                scope.launch {
                    val result = runCatching { requestRinReply(deviceToken, history) }
                    if (messages.isNotEmpty() && messages.last().text == "Rin sedang berfikir...") {
                        messages.removeAt(messages.lastIndex)
                    }
                    val reply = result.getOrElse { error ->
                        val detail = if (error is SocketTimeoutException) "Sambungan mengambil masa terlalu lama. Cuba hantar semula." else (error.message ?: "Semak sambungan dan tetapan Worker.")
                        "Rin belum dapat menjawab. $detail"
                    }
                    messages.add(ChatLine(reply, false, !fromVoice || isExternalWritingInstruction(value)))
                    isThinking = false
                    isSpeaking = false
                    if (!result.isSuccess) {
                        voiceStatus = "Sambungan AI belum berjaya."
                        if (fromVoice && voiceConversationEnabled) startListeningNow?.invoke()
                    } else if (!fromVoice) {
                        // Text/chat mode must remain silent. Only a reply to an explicitly
                        // voice-originated request may generate and play Sulafat audio.
                        voiceStatus = "Jawapan teks dipaparkan. Aktifkan mod suara untuk mendengar Rin."
                    } else if (isExternalWritingInstruction(value)) {
                        voiceStatus = "Jawapan dipaparkan sebagai teks untuk arahan aplikasi lain."
                        if (voiceConversationEnabled) startListeningNow?.invoke()
                    } else {
                        val speech = runCatching { requestRinSpeechCached(context.cacheDir, deviceToken, reply) }
                        speech.onSuccess { audio ->
                            activity.playRinVoice(audio, onStart = { isSpeaking = true; voiceStatus = "Rin sedang bercakap..." }, onComplete = { isSpeaking = false; voiceStatus = "Rin selesai bercakap."; if (voiceConversationEnabled) startListeningNow?.invoke() }, onError = { isSpeaking = false; voiceStatus = it; if (voiceConversationEnabled) startListeningNow?.invoke() })
                            voiceStatus = "Rin sedang bercakap..."
                        }.onFailure { voiceStatus = "Jawapan teks tersedia, tetapi suara gagal: ${it.message ?: "ralat TTS"}"; if (voiceConversationEnabled) startListeningNow?.invoke() }
                    }
                }
            }
        }
    }

    startListeningNow = {
        if (voiceConversationEnabled && !isThinking && !isSpeaking && !isListening) {
            isListening = activity.startLiveSpeech(
                onReady = { isListening = true; voiceStatus = "Rin sedang mendengar. Bercakap seperti biasa..." },
                onPartial = { draft = it; isListening = true },
                onFinal = { phrase ->
                    isListening = false
                    draft = ""
                    voiceStatus = "Arahan dikenal pasti. Rin sedang menjawab..."
                    sendMessage(phrase, true)
                },
                onError = { problem ->
                    isListening = false
                    if (problem == "SILENCE" && voiceConversationEnabled) {
                        voiceStatus = "Rin masih mendengar..."
                        scope.launch { kotlinx.coroutines.delay(450); startListeningNow?.invoke() }
                    } else {
                        voiceConversationEnabled = false
                        voiceStatus = problem
                    }
                }
            )
            if (isListening) voiceStatus = "Menyediakan pengecaman suara langsung..."
        }
    }

    val microphonePermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (activity.startVoiceForegroundService()) {
                voiceConversationEnabled = true
                startListeningNow?.invoke()
                voiceStatus = "Mod suara latar belakang diaktifkan."
            } else {
                voiceConversationEnabled = false
                voiceStatus = "Rin tidak dapat memulakan servis suara latar belakang."
            }
        } else {
            voiceConversationEnabled = false
            isListening = false
            voiceStatus = "Kebenaran mikrofon tidak diberikan."
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        background = Night, surface = Panel, primary = Violet,
        secondary = Cyan, onBackground = Color.White, onSurface = Color.White
    )) {
        Column(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0xFF17132D), Night, Color(0xFF080D19)))
            ).padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).background(
                        Brush.linearGradient(listOf(Violet, Cyan)), CircleShape
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("R", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Rin", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Pembantu peribadi", fontSize = 12.sp, color = Cyan)
                }
                Spacer(Modifier.weight(1f))
                Surface(color = Color(0x3329D7B5), shape = RoundedCornerShape(20.dp)) {
                    Text("● ${if (deviceToken.isNotBlank()) "AI sedia" else "Tetapan"}",
                        Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        color = Color(0xFF70E4C4), fontSize = 12.sp)
                }
                TextButton(onClick = {
                    installedApps = activity.getInstalledLaunchableApps()
                    showAppsDialog = true
                }) {
                    Icon(Icons.Default.Apps, contentDescription = "Senarai aplikasi", tint = Cyan)
                    Spacer(Modifier.width(4.dp))
                    Text("Apps ${installedApps.size}", color = Cyan, fontSize = 12.sp)
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Tetapan AI", tint = Cyan)
                }
            }

            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = Color(0x334D5A88))
            Spacer(Modifier.height(12.dp))

            RinAvatar(isListening = isListening, isThinking = isThinking, isSpeaking = isSpeaking)

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(messages.filter { it.showInChat }) { line ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start
                    ) {
                        Surface(
                            color = if (line.fromUser) Color(0xFF493B78) else Panel,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.widthIn(max = 300.dp)
                        ) {
                            SelectionContainer {
                                Text(line.text, Modifier.padding(14.dp), color = Color.White, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }

            if (attachedImage != null) {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(bitmap = attachedImage!!.asImageBitmap(), contentDescription = "Gambar lampiran", modifier = Modifier.size(76.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Gambar dilampirkan", color = Color.White, fontSize = 13.sp)
                        Text("Pemprosesan imej AI belum aktif", color = Color(0xFFFFD27A), fontSize = 11.sp)
                    }
                    IconButton(onClick = { attachedImage = null }) { Icon(Icons.Default.Close, contentDescription = "Buang gambar", tint = Color.LightGray) }
                }
            }

            Row(
                Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(28.dp)).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    IconButton(onClick = { showAttachmentMenu = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Tambah gambar", tint = Cyan)
                    }
                    DropdownMenu(expanded = showAttachmentMenu, onDismissRequest = { showAttachmentMenu = false }) {
                        DropdownMenuItem(text = { Text("Buka kamera") }, leadingIcon = { Icon(Icons.Default.CameraAlt, null) }, onClick = { showAttachmentMenu = false; cameraPicker.launch(null) })
                        DropdownMenuItem(text = { Text("Pilih gambar") }, leadingIcon = { Icon(Icons.Default.Photo, null) }, onClick = { showAttachmentMenu = false; galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                    }
                }
                IconButton(onClick = {
                    if (voiceConversationEnabled) {
                        voiceConversationEnabled = false
                        isListening = false
                        activity.stopLiveSpeech()
                        activity.stopVoiceForegroundService()
                        voiceStatus = "Mod suara dimatikan."
                    } else if (ContextCompat.checkSelfPermission(
                            activity, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        if (activity.startVoiceForegroundService()) {
                            voiceConversationEnabled = true
                            startListeningNow?.invoke()
                            voiceStatus = "Mod suara latar belakang diaktifkan."
                        } else {
                            voiceStatus = "Rin tidak dapat memulakan servis suara latar belakang."
                        }
                    } else {
                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }) {
                    Icon(Icons.Default.Mic,
                        contentDescription = if (voiceConversationEnabled) "Matikan perbualan suara" else "Aktifkan perbualan suara",
                        tint = if (voiceConversationEnabled) Color(0xFFFF8585) else Cyan)
                }
                TextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Tulis mesej...", color = Color.LightGray) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    maxLines = 4
                )
                IconButton(
                    enabled = !isThinking,
                    onClick = { sendMessage(draft, false) }
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Hantar", tint = Violet)
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(
                voiceStatus,
                color = if (isListening || isThinking) Cyan else Color(0xFF9298B8),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(3.dp))
            Text("Gemini AI • Suara Bahasa Melayu Rin",
                color = Color(0xFF9298B8), fontSize = 10.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        if (showAppsDialog) {
            AlertDialog(
                onDismissRequest = { showAppsDialog = false },
                title = { Text("Aplikasi dalam telefon (${installedApps.size})") },
                text = {
                    Column(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = appSearch,
                            onValueChange = { appSearch = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Cari aplikasi") },
                            singleLine = true
                        )
                        LazyColumn(Modifier.heightIn(max = 350.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            items(filteredInstalledApps, key = { it.packageName }) { app ->
                                TextButton(
                                    onClick = {
                                        val opened = activity.launchInstalledApp(app.packageName)
                                        voiceStatus = if (opened) "Membuka ${app.label}..." else "Aplikasi ${app.label} tidak dapat dibuka."
                                        showAppsDialog = false
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(app.label, modifier = Modifier.weight(1f), color = Color.White)
                                    Text("BUKA", color = Cyan, fontSize = 11.sp)
                                }
                            }
                        }
                        if (filteredInstalledApps.isEmpty()) {
                            Text("Tiada aplikasi sepadan.", color = Color.LightGray)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        installedApps = activity.getInstalledLaunchableApps()
                    }) { Text("Imbas semula") }
                },
                dismissButton = {
                    TextButton(onClick = { showAppsDialog = false; appSearch = "" }) { Text("Tutup") }
                }
            )
        }

        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text("Tetapan sambungan Rin AI") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Masukkan DEVICE_TOKEN yang sama seperti rahsia DEVICE_TOKEN dalam Cloudflare Worker. Jangan masukkan GEMINI_API_KEY di sini.")
                        OutlinedTextField(
                            value = deviceToken,
                            onValueChange = { deviceToken = it.trim() },
                            label = { Text("DEVICE_TOKEN") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        preferences.edit().putString("device_token", deviceToken.trim()).apply()
                        deviceToken = deviceToken.trim()
                        showSettings = false
                        voiceStatus = "Token disimpan pada storan peribadi aplikasi."
                    }) { Text("Simpan") }
                },
                dismissButton = {
                    TextButton(onClick = { showSettings = false }) { Text("Tutup") }
                }
            )
        }
    }
}

/**
 * Keeps Rin's microphone conversation process alive while another app is in the foreground.
 * The service is started only from the visible app after the user enables voice mode.
 */
class RinVoiceForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (exception: SecurityException) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rin AI - Perbualan suara",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Menunjukkan apabila Rin sedang mengekalkan perbualan suara di latar belakang."
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Rin AI sedang aktif")
            .setContentText("Perbualan suara berjalan di latar belakang")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "rin_voice_background"
        private const val NOTIFICATION_ID = 4701
    }
}
