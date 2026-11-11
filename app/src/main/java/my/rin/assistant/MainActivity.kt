package my.rin.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Night = Color(0xFF090B18)
private val Panel = Color(0xFF171A2D)
private val Violet = Color(0xFF9B7BFF)
private val Cyan = Color(0xFF69D9F5)

data class ChatLine(val text: String, val fromUser: Boolean)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RinApp() }
    }
}

@Composable
fun RinApp() {
    var draft by remember { mutableStateOf("") }
    val messages = remember {
        mutableStateListOf(ChatLine("Hai sayang. Rin sedia membantu.", false))
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
                    Text("● Sedia", Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        color = Color(0xFF70E4C4), fontSize = 12.sp)
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
                IconButton(onClick = { /* Voice input will be added in the next phase. */ }) {
                    Icon(Icons.Default.Mic, contentDescription = "Mikrofon", tint = Cyan)
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
                IconButton(onClick = {
                    val value = draft.trim()
                    if (value.isNotEmpty()) {
                        messages.add(ChatLine(value, true))
                        messages.add(ChatLine("Rin sudah terima mesej sayang. Sambungan AI akan diaktifkan dalam fasa seterusnya.", false))
                        draft = ""
                    }
                }) {
                    Icon(Icons.Default.Send, contentDescription = "Hantar", tint = Violet)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Versi asas • Chat tempatan demo", color = Color(0xFF9298B8), fontSize = 10.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}
