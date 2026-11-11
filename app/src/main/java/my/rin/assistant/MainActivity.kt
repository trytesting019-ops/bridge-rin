package my.rin.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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
import java.net.URL

private val Night = Color(0xFF090B18)
private val Panel = Color(0xFF171A2D)
private val Violet = Color(0xFF9B7BFF)
private val Cyan = Color(0xFF69D9F5)
private const val RIN_CHAT_URL = "https://rin-bridge.trytesting019.workers.dev/v1/chat"

data class ChatLine(val text: String, val fromUser: Boolean)

private suspend fun requestRinReply(deviceToken: String, history: List<ChatLine>): String =
    withContext(Dispatchers.IO) {
        val connection = (URL(RIN_CHAT_URL).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 60000
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

class MainActivity : ComponentActivity() {
    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RinApp() }
    }

    fun startVoiceInput(
        onReady: () -> Unit,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            onError("Fungsi pengecaman suara tidak tersedia pada peranti ini.")
            return false
        }

        return try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = onReady()
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit

                    override fun onPartialResults(partialResults: Bundle?) {
                        partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.let(onPartial)
                    }

                    override fun onResults(results: Bundle?) {
                        val spokenText = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        if (!spokenText.isNullOrBlank()) onFinal(spokenText)
                        else onError("Suara tidak dapat dikenal pasti. Cuba sekali lagi.")
                    }

                    override fun onError(error: Int) {
                        val message = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Masalah audio mikrofon."
                            SpeechRecognizer.ERROR_CLIENT -> "Pengecaman suara dihentikan."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Kebenaran mikrofon diperlukan."
                            SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Sambungan rangkaian untuk pengecaman suara gagal."
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Tiada suara dikesan. Cuba bercakap lebih dekat dengan mikrofon."
                            else -> "Pengecaman suara gagal. Cuba sekali lagi."
                        }
                        onError(message)
                    }
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ms-MY")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ms-MY")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizer?.startListening(intent)
            true
        } catch (exception: Exception) {
            onError("Mikrofon tidak dapat dimulakan: ${exception.localizedMessage ?: "ralat tidak diketahui"}")
            false
        }
    }

    fun stopVoiceInput() {
        try { speechRecognizer?.stopListening() } catch (_: Exception) { }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        super.onDestroy()
    }
}

@Composable
fun RinApp() {
    var draft by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    var isThinking by remember { mutableStateOf(false) }
    var voiceStatus by remember { mutableStateOf("AI dalam talian belum diuji pada peranti.") }
    var showSettings by remember { mutableStateOf(false) }
    val activity = androidx.compose.ui.platform.LocalContext.current as MainActivity
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember { context.getSharedPreferences("rin_private", MODE_PRIVATE) }
    var deviceToken by remember { mutableStateOf(preferences.getString("device_token", "") ?: "") }
    val scope = rememberCoroutineScope()
    val messages = remember {
        mutableStateListOf(ChatLine("Hai sayang. Rin sedia membantu.", false))
    }

    val microphonePermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            isListening = activity.startVoiceInput(
                onReady = { voiceStatus = "Rin sedang mendengar..." },
                onPartial = { draft = it },
                onFinal = {
                    draft = it
                    isListening = false
                    voiceStatus = "Suara berjaya ditukar kepada teks."
                },
                onError = {
                    isListening = false
                    voiceStatus = it
                }
            )
            if (isListening) voiceStatus = "Menyediakan mikrofon..."
        } else {
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
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Tetapan AI", tint = Cyan)
                }
            }

            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = Color(0x334D5A88))
            Spacer(Modifier.height(12.dp))

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(messages) { line ->
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

            Row(
                Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(28.dp)).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    if (isListening) {
                        activity.stopVoiceInput()
                        isListening = false
                        voiceStatus = "Mikrofon dihentikan."
                    } else if (ContextCompat.checkSelfPermission(
                            activity, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        isListening = activity.startVoiceInput(
                            onReady = { voiceStatus = "Rin sedang mendengar..." },
                            onPartial = { draft = it },
                            onFinal = {
                                draft = it
                                isListening = false
                                voiceStatus = "Suara berjaya ditukar kepada teks."
                            },
                            onError = {
                                isListening = false
                                voiceStatus = it
                            }
                        )
                        if (isListening) voiceStatus = "Menyediakan mikrofon..."
                    } else {
                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }) {
                    Icon(Icons.Default.Mic,
                        contentDescription = if (isListening) "Hentikan mikrofon" else "Aktifkan mikrofon",
                        tint = if (isListening) Color(0xFFFF8585) else Cyan)
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
                    onClick = {
                        val value = draft.trim()
                        if (value.isNotEmpty()) {
                            if (deviceToken.isBlank()) {
                                voiceStatus = "Masukkan DEVICE_TOKEN melalui Tetapan AI dahulu."
                                showSettings = true
                            } else {
                                val history = messages.toList() + ChatLine(value, true)
                                messages.add(ChatLine(value, true))
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
                                        "Rin belum dapat menjawab. ${error.message ?: "Semak sambungan dan tetapan Worker."}"
                                    }
                                    messages.add(ChatLine(reply, false))
                                    isThinking = false
                                    voiceStatus = if (result.isSuccess) "Rin AI dalam talian telah membalas."
                                        else "Sambungan AI belum berjaya."
                                }
                            }
                        }
                    }
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
            Text("AI melalui Cloudflare Worker • Mikrofon ms-MY",
                color = Color(0xFF9298B8), fontSize = 10.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally))
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
