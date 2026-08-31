package com.iredox.aisidebar

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iredox.aisidebar.overlay.OverlayService
import com.iredox.aisidebar.ui.theme.AISidebarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AISidebarTheme { SidebarApp() } }
    }
}

private enum class Destination(val label: String) { CHAT("Assistant"), HISTORY("Chats"), SETTINGS("Settings") }

private data class ChatMessage(val id: Long, val role: Role, val text: String)
private enum class Role { USER, ASSISTANT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SidebarApp() {
    var destination by remember { mutableStateOf(Destination.CHAT) }
    val messages = remember {
        mutableStateListOf(
            ChatMessage(1, Role.ASSISTANT, "Hi — I’m AI Sidebar. Add your provider key in Settings, then I’ll be ready to help with text and (later) screen context.")
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(destination.label, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    Box(
                        modifier = Modifier
                            .padding(start = 16.dp)
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) { Text("AI", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) }
                },
                actions = {
                    IconButton(onClick = { destination = Destination.SETTINGS }) {
                        Icon(Icons.Default.Settings, contentDescription = "Open settings")
                    }
                }
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                NavItem(Destination.CHAT, destination == Destination.CHAT, Icons.Default.ChatBubbleOutline) { destination = Destination.CHAT }
                NavItem(Destination.HISTORY, destination == Destination.HISTORY, Icons.Default.ContentCopy) { destination = Destination.HISTORY }
                NavItem(Destination.SETTINGS, destination == Destination.SETTINGS, Icons.Default.Settings) { destination = Destination.SETTINGS }
            }
        }
    ) { padding ->
        when (destination) {
            Destination.CHAT -> ChatScreen(Modifier.padding(padding), messages)
            Destination.HISTORY -> ChatHistory(Modifier.padding(padding)) { destination = Destination.CHAT }
            Destination.SETTINGS -> SettingsScreen(Modifier.padding(padding))
        }
    }
}

@Composable
private fun NavItem(destination: Destination, selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, click: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = click).padding(4.dp)) {
        Icon(icon, destination.label, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(destination.label, style = MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChatScreen(modifier: Modifier, messages: MutableList<ChatMessage>) {
    var prompt by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("New conversation", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Your chats stay on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(messages, key = { it.id }) { MessageCard(it) }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask anything…") },
                maxLines = 4,
                shape = RoundedCornerShape(22.dp)
            )
            Spacer(Modifier.width(10.dp))
            FloatingActionButton(onClick = {
                val cleanPrompt = prompt.trim()
                if (cleanPrompt.isNotEmpty()) {
                    messages += ChatMessage(System.nanoTime(), Role.USER, cleanPrompt)
                    messages += ChatMessage(System.nanoTime() + 1, Role.ASSISTANT, "Connect a provider in Settings to receive a live response. This local Phase 1 build has saved your prompt in the active chat UI.")
                    prompt = ""
                }
            }) { Icon(Icons.Default.Send, "Send") }
        }
    }
}

@Composable
private fun MessageCard(message: ChatMessage) {
    val isUser = message.role == Role.USER
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Card(
            modifier = Modifier.fillMaxWidth(0.87f),
            colors = CardDefaults.cardColors(containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text(message.text, modifier = Modifier.padding(14.dp), color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChatHistory(modifier: Modifier, onNewChat: () -> Unit) {
    Column(modifier.fillMaxSize().padding(20.dp)) {
        Text("Your conversations", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(16.dp)) {
                Text("Phase 1 note", fontWeight = FontWeight.SemiBold)
                Text("Room-backed chat history arrives in Phase 2. The current conversation is retained while the app stays open.")
            }
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = onNewChat, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Start a new chat")
        }
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier) {
    val context = LocalContext.current
    var provider by remember { mutableStateOf("OpenAI-compatible") }
    var apiKey by remember { mutableStateOf("") }
    val overlayGranted = Settings.canDrawOverlays(context)

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Provider", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Streaming integration is the next implementation milestone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("OpenAI-compatible", "Anthropic", "Google").forEach { name ->
                    AssistChip(onClick = { provider = name }, label = { Text(name) }, leadingIcon = if (provider == name) ({ Text("✓") }) else null)
                }
            }
        }
        item {
            OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, modifier = Modifier.fillMaxWidth(), label = { Text("API key") }, placeholder = { Text("Stored securely in Phase 2") }, singleLine = true)
        }
        item { Text("Overlay", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = if (overlayGranted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text(if (overlayGranted) "Permission granted" else "Permission needed", fontWeight = FontWeight.SemiBold)
                    Text(if (overlayGranted) "You can start the floating bubble." else "Android requires a system-level permission before AI Sidebar can float above other apps.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        if (Settings.canDrawOverlays(context)) {
                            context.startForegroundService(Intent(context, OverlayService::class.java))
                        } else {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                        }
                    }) { Text(if (overlayGranted) "Start floating bubble" else "Grant overlay permission") }
                }
            }
        }
        item {
            Text("Privacy", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Screen text is never captured automatically. In a later phase, you will explicitly attach safe visible screen content to a single prompt. Password and sensitive fields will be excluded.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
