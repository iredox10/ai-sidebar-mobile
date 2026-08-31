package com.iredox.aisidebar

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iredox.aisidebar.overlay.OverlayService
import com.iredox.aisidebar.api.OpenAiCompatibleClient
import com.iredox.aisidebar.api.ProviderConfig
import com.iredox.aisidebar.api.RemoteChatMessage
import com.iredox.aisidebar.api.StreamingRequest
import com.iredox.aisidebar.data.SecureKeyStore
import com.iredox.aisidebar.data.ChatStore
import com.iredox.aisidebar.data.StoredChatMessage
import com.iredox.aisidebar.data.StoredConversation
import com.iredox.aisidebar.data.ProviderSettings
import com.iredox.aisidebar.data.ProviderSettingsStore
import com.iredox.aisidebar.screen.ScreenReadAccessibilityService
import com.iredox.aisidebar.ui.theme.AISidebarTheme

class MainActivity : ComponentActivity() {
    private var sharedText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedText = extractSharedText(intent)
        setContent { AISidebarTheme { SidebarApp(sharedText) { sharedText = null } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedText = extractSharedText(intent)
    }

    private fun extractSharedText(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() }
}

private enum class Destination(val label: String) { CHAT("Assistant"), HISTORY("Chats"), SETTINGS("Settings") }

private data class ChatMessage(val id: Long, val role: Role, val text: String)
private enum class Role { USER, ASSISTANT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SidebarApp(sharedText: String?, onSharedTextConsumed: () -> Unit) {
    val context = LocalContext.current
    val secureKeyStore = remember { SecureKeyStore(context.applicationContext) }
    val chatStore = remember { ChatStore(context.applicationContext) }
    val providerSettingsStore = remember { ProviderSettingsStore(context.applicationContext) }
    val savedProviderSettings = remember { providerSettingsStore.read() }
    var destination by remember { mutableStateOf(Destination.CHAT) }
    var provider by remember { mutableStateOf(savedProviderSettings.provider) }
    var apiKey by remember { mutableStateOf(secureKeyStore.readApiKey().orEmpty()) }
    var endpoint by remember { mutableStateOf(savedProviderSettings.endpoint) }
    var model by remember { mutableStateOf(savedProviderSettings.model) }
    var activeConversationId by remember { mutableStateOf(chatStore.activeConversationId() ?: System.currentTimeMillis()) }
    var conversationHistory by remember { mutableStateOf(chatStore.loadHistory()) }
    val messages = remember {
        val restored = chatStore.loadMessages().map { ChatMessage(it.id, Role.valueOf(it.role), it.text) }
        mutableStateListOf<ChatMessage>().apply {
            if (restored.isEmpty()) add(ChatMessage(1, Role.ASSISTANT, "Hi — I’m AI Sidebar. Add your provider key in Settings, then I’ll be ready to help with text and (later) screen context."))
            else addAll(restored)
        }
    }
    val persistMessages = {
        val storedMessages = messages.map { StoredChatMessage(it.id, it.role.name, it.text) }
        chatStore.saveMessages(storedMessages)
        chatStore.setActiveConversationId(activeConversationId)
        chatStore.saveConversation(
            StoredConversation(
                id = activeConversationId,
                title = conversationTitle(messages),
                updatedAt = System.currentTimeMillis(),
                messages = storedMessages
            )
        )
        conversationHistory = chatStore.loadHistory()
    }
    val createNewConversation = {
        persistMessages()
        activeConversationId = System.currentTimeMillis()
        messages.clear()
        messages += ChatMessage(1, Role.ASSISTANT, "New conversation. How can I help?")
        persistMessages()
        destination = Destination.CHAT
    }
    val openConversation: (StoredConversation) -> Unit = { conversation ->
        activeConversationId = conversation.id
        messages.clear()
        messages += conversation.messages.mapNotNull { stored ->
            runCatching { ChatMessage(stored.id, Role.valueOf(stored.role), stored.text) }.getOrNull()
        }
        chatStore.saveMessages(conversation.messages)
        chatStore.setActiveConversationId(conversation.id)
        destination = Destination.CHAT
    }
    val deleteConversation: (StoredConversation) -> Unit = { conversation ->
        chatStore.deleteConversation(conversation.id)
        if (conversation.id == activeConversationId) {
            activeConversationId = System.currentTimeMillis()
            messages.clear()
            messages += ChatMessage(1, Role.ASSISTANT, "New conversation. How can I help?")
            persistMessages()
        } else {
            conversationHistory = chatStore.loadHistory()
        }
    }
    val renameConversation: (StoredConversation, String) -> Unit = { conversation, title ->
        chatStore.renameConversation(conversation.id, title)
        conversationHistory = chatStore.loadHistory()
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
            Destination.CHAT -> ChatScreen(Modifier.padding(padding), messages, provider, apiKey, endpoint, model, persistMessages, sharedText, onSharedTextConsumed)
            Destination.HISTORY -> ChatHistory(Modifier.padding(padding), conversationHistory, openConversation, deleteConversation, renameConversation, createNewConversation)
            Destination.SETTINGS -> SettingsScreen(
                Modifier.padding(padding), provider, {
                    provider = it
                    providerSettingsStore.write(ProviderSettings(provider, endpoint, model))
                }, apiKey, {
                    apiKey = it
                    secureKeyStore.writeApiKey(it)
                },
                endpoint, {
                    endpoint = it
                    providerSettingsStore.write(ProviderSettings(provider, endpoint, model))
                }, model, {
                    model = it
                    providerSettingsStore.write(ProviderSettings(provider, endpoint, model))
                }
            )
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
private fun ChatScreen(
    modifier: Modifier,
    messages: MutableList<ChatMessage>,
    provider: String,
    apiKey: String,
    endpoint: String,
    model: String,
    onMessagesChanged: () -> Unit,
    sharedText: String?,
    onSharedTextConsumed: () -> Unit
) {
    var prompt by remember { mutableStateOf("") }
    var activeRequest by remember { mutableStateOf<StreamingRequest?>(null) }
    var screenContextNote by remember { mutableStateOf<String?>(null) }
    var imageDataUrl by remember { mutableStateOf<String?>(null) }
    val client = remember { OpenAiCompatibleClient() }
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not read image")
            require(bytes.size <= 5 * 1024 * 1024) { "Images must be 5 MB or smaller." }
            val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            "data:$mimeType;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        }.onSuccess {
            imageDataUrl = it
            screenContextNote = "Image attached. Choose a vision-capable model before sending."
        }.onFailure { error -> screenContextNote = error.message ?: "Could not attach that image." }
    }
    val textFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not read file")
            require(bytes.size <= 1_000_000) { "Text files must be 1 MB or smaller." }
            bytes.toString(Charsets.UTF_8).trim().takeIf { it.isNotBlank() } ?: error("The selected file is empty.")
        }.onSuccess { text ->
            val contextBlock = "[Attached text file]\n$text"
            prompt = listOf(prompt.trim(), contextBlock).filter { it.isNotBlank() }.joinToString("\n\n")
            screenContextNote = "Text file added to the draft. Review it before sending."
        }.onFailure { error -> screenContextNote = error.message ?: "Could not attach that file." }
    }
    LaunchedEffect(sharedText) {
        sharedText?.let {
            prompt = it
            onSharedTextConsumed()
        }
    }
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
        screenContextNote?.let {
            Text(
                it,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            IconButton(onClick = {
                if (!ScreenReadAccessibilityService.isEnabled()) {
                    screenContextNote = "Enable Accessibility to attach visible screen text."
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } else {
                    val capture = ScreenReadAccessibilityService.captureActiveScreen()
                    if (capture?.visibleText.isNullOrBlank()) {
                        screenContextNote = "No safe visible text was available on this screen."
                    } else {
                        val contextBlock = "[Visible screen context from ${capture?.packageName ?: "current app"}]\n${capture?.visibleText}"
                        prompt = listOf(prompt.trim(), contextBlock).filter { it.isNotBlank() }.joinToString("\n\n")
                        screenContextNote = "Visible screen context added. Review it before sending."
                    }
                }
            }) { Icon(Icons.Default.Visibility, "Attach visible screen context") }
            IconButton(onClick = { imagePicker.launch("image/*") }) {
                Icon(Icons.Default.Image, "Attach image")
            }
            IconButton(onClick = { textFilePicker.launch("text/plain") }) {
                Icon(Icons.Default.AttachFile, "Attach text file")
            }
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
                if (activeRequest != null) {
                    activeRequest?.cancel()
                    activeRequest = null
                    onMessagesChanged()
                } else if (cleanPrompt.isNotEmpty()) {
                    messages += ChatMessage(System.nanoTime(), Role.USER, if (imageDataUrl == null) cleanPrompt else "[Image attached] $cleanPrompt")
                    val responseId = System.nanoTime() + 1
                    messages += ChatMessage(responseId, Role.ASSISTANT, "")
                    onMessagesChanged()
                    prompt = ""
                    if (provider != "OpenAI-compatible" || apiKey.isBlank()) {
                        val messageIndex = messages.indexOfFirst { it.id == responseId }
                        messages[messageIndex] = messages[messageIndex].copy(text = if (apiKey.isBlank()) "Add an API key under Settings to start streaming replies." else "$provider streaming will be added after the OpenAI-compatible path.")
                        onMessagesChanged()
                    } else {
                        val outgoingMessages = messages.filter { it.id != responseId }
                            .map { RemoteChatMessage(if (it.role == Role.USER) "user" else "assistant", it.text) }
                            .toMutableList()
                        if (imageDataUrl != null && outgoingMessages.isNotEmpty()) {
                            outgoingMessages[outgoingMessages.lastIndex] = outgoingMessages.last().copy(content = cleanPrompt, imageDataUrl = imageDataUrl)
                        }
                        activeRequest = client.streamChat(
                            config = ProviderConfig(endpoint = endpoint, apiKey = apiKey, model = model),
                            messages = outgoingMessages,
                            onDelta = { delta ->
                                val messageIndex = messages.indexOfFirst { it.id == responseId }
                                if (messageIndex >= 0) messages[messageIndex] = messages[messageIndex].copy(text = messages[messageIndex].text + delta)
                            },
                            onComplete = { activeRequest = null; onMessagesChanged() },
                            onError = { error ->
                                val messageIndex = messages.indexOfFirst { it.id == responseId }
                                if (messageIndex >= 0) messages[messageIndex] = messages[messageIndex].copy(text = "Connection error: $error")
                                activeRequest = null
                                onMessagesChanged()
                            }
                        )
                        imageDataUrl = null
                    }
                }
            }) { Icon(if (activeRequest == null) Icons.Default.Send else Icons.Default.Close, if (activeRequest == null) "Send" else "Stop response") }
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
private fun ChatHistory(
    modifier: Modifier,
    conversations: List<StoredConversation>,
    onOpenConversation: (StoredConversation) -> Unit,
    onDeleteConversation: (StoredConversation) -> Unit,
    onRenameConversation: (StoredConversation, String) -> Unit,
    onNewChat: () -> Unit
) {
    var pendingDelete by remember { mutableStateOf<StoredConversation?>(null) }
    var editingConversation by remember { mutableStateOf<StoredConversation?>(null) }
    var editedTitle by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize().padding(20.dp)) {
        Text("Your conversations", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        if (conversations.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text("Your saved conversations will appear here.", modifier = Modifier.padding(16.dp))
            }
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(conversations, key = { it.id }) { conversation ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { onOpenConversation(conversation) }) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(conversation.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${conversation.messages.size} messages", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { editingConversation = conversation; editedTitle = conversation.title }) {
                                Icon(Icons.Default.Edit, "Rename conversation")
                            }
                            IconButton(onClick = { pendingDelete = conversation }) {
                                Icon(Icons.Default.Delete, "Delete conversation")
                            }
                        }
                    }
                }
            }
        }
        Button(onClick = onNewChat, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Start a new chat")
        }
    }
    pendingDelete?.let { conversation ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete conversation?") },
            text = { Text("This removes “${conversation.title}” from this device.") },
            confirmButton = {
                TextButton(onClick = { onDeleteConversation(conversation); pendingDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
    editingConversation?.let { conversation ->
        AlertDialog(
            onDismissRequest = { editingConversation = null },
            title = { Text("Rename conversation") },
            text = { OutlinedTextField(value = editedTitle, onValueChange = { editedTitle = it }, label = { Text("Title") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { onRenameConversation(conversation, editedTitle); editingConversation = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingConversation = null }) { Text("Cancel") } }
        )
    }
}

private fun conversationTitle(messages: List<ChatMessage>): String =
    messages.firstOrNull { it.role == Role.USER }?.text?.replace('\n', ' ')?.take(56)?.ifBlank { null }
        ?: "New conversation"

@Composable
private fun SettingsScreen(
    modifier: Modifier,
    provider: String,
    onProviderChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    endpoint: String,
    onEndpointChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit
) {
    val context = LocalContext.current
    var contextResult by remember { mutableStateOf<String?>(null) }
    val overlayGranted = Settings.canDrawOverlays(context)

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Provider", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Streaming integration is the next implementation milestone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("OpenAI-compatible", "Anthropic", "Google").forEach { name ->
                    AssistChip(onClick = { onProviderChange(name) }, label = { Text(name) }, leadingIcon = if (provider == name) ({ Text("✓") }) else null)
                }
            }
        }
        item {
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API key") },
                placeholder = { Text("Encrypted on this device") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                value = endpoint,
                onValueChange = onEndpointChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Chat completions endpoint") },
                supportingText = { Text("OpenRouter, DeepSeek, and compatible private servers work here.") },
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                value = model,
                onValueChange = onModelChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Model") },
                singleLine = true
            )
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
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Screen context", fontWeight = FontWeight.SemiBold)
                    Text(if (ScreenReadAccessibilityService.isEnabled()) "Accessibility service connected. You can test a manual capture." else "Enable the AI Sidebar accessibility service to allow manual visible-text capture.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        if (ScreenReadAccessibilityService.isEnabled()) {
                            val capture = ScreenReadAccessibilityService.captureActiveScreen()
                            contextResult = capture?.let { "Captured ${it.visibleText.length} characters from ${it.packageName ?: "the current screen"}. Nothing was sent anywhere." }
                                ?: "No visible text was available."
                        } else {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    }) {
                        Text(if (ScreenReadAccessibilityService.isEnabled()) "Test visible-text capture" else "Open Accessibility settings")
                    }
                    contextResult?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
