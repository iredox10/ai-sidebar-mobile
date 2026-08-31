package com.iredox.aisidebar

import android.Manifest
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.RecognizerIntent
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontFamily
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
import com.iredox.aisidebar.tools.WebSearchClient
import com.iredox.aisidebar.ui.theme.AISidebarTheme
import java.io.ByteArrayOutputStream
import java.util.Locale

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
                    IconButton(onClick = createNewConversation) {
                        Icon(Icons.Default.Add, contentDescription = "Start new chat")
                    }
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
    val webSearchClient = remember { WebSearchClient() }
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
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { renderFirstPdfPage(context, uri) }
            .onSuccess {
                imageDataUrl = it
                screenContextNote = "First PDF page attached as an image. Choose a vision-capable model before sending."
            }
            .onFailure { error -> screenContextNote = error.message ?: "Could not attach that PDF." }
    }
    val voiceInputLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val transcript = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!transcript.isNullOrBlank()) {
            prompt = listOf(prompt.trim(), transcript).filter { it.isNotBlank() }.joinToString(" ")
        } else {
            screenContextNote = "No speech was recognized."
        }
    }
    val startVoiceInput = {
        voiceInputLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your question")
        })
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoiceInput() else screenContextNote = "Microphone permission is needed for voice input."
    }
    LaunchedEffect(sharedText) {
        sharedText?.let {
            prompt = it
            onSharedTextConsumed()
        }
    }
    fun regenerate(responseIndex: Int) {
        if (activeRequest != null || provider != "OpenAI-compatible" || apiKey.isBlank()) return
        val response = messages.getOrNull(responseIndex) ?: return
        if (response.role != Role.ASSISTANT) return
        messages[responseIndex] = response.copy(text = "")
        onMessagesChanged()
        activeRequest = client.streamChat(
            config = ProviderConfig(endpoint = endpoint, apiKey = apiKey, model = model),
            messages = messages.take(responseIndex).map { RemoteChatMessage(if (it.role == Role.USER) "user" else "assistant", it.text) },
            onDelta = { delta ->
                messages[responseIndex] = messages[responseIndex].copy(text = messages[responseIndex].text + delta)
            },
            onComplete = { activeRequest = null; onMessagesChanged() },
            onError = { error ->
                messages[responseIndex] = messages[responseIndex].copy(text = "Connection error: $error")
                activeRequest = null
                onMessagesChanged()
            }
        )
    }
    fun editLastPrompt(userIndex: Int) {
        if (activeRequest != null || userIndex != messages.lastIndex - 1) return
        val userMessage = messages.getOrNull(userIndex)?.takeIf { it.role == Role.USER } ?: return
        prompt = userMessage.text.removePrefix("[Image attached] ")
        messages.removeAt(messages.lastIndex)
        messages.removeAt(userIndex)
        onMessagesChanged()
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
            itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                MessageCard(
                    message = message,
                    onRegenerate = if (message.role == Role.ASSISTANT && index == messages.lastIndex) ({ regenerate(index) }) else null,
                    onEdit = if (message.role == Role.USER && index == messages.lastIndex - 1) ({ editLastPrompt(index) }) else null
                )
            }
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
            IconButton(onClick = { pdfPicker.launch("application/pdf") }) {
                Icon(Icons.Default.ContentCopy, "Attach first PDF page")
            }
            IconButton(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startVoiceInput()
                } else {
                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }) { Icon(Icons.Default.Mic, "Speak prompt") }
            IconButton(onClick = {
                val query = prompt.trim()
                if (query.isBlank()) {
                    screenContextNote = "Write a search query first."
                } else {
                    screenContextNote = "Searching the web…"
                    webSearchClient.search(
                        query = query,
                        onSuccess = { results ->
                            if (results.isEmpty()) {
                                screenContextNote = "No web results were found."
                            } else {
                                val contextBlock = buildString {
                                    append("[Web search results for: $query]\n")
                                    results.forEachIndexed { index, result ->
                                        append("${index + 1}. ${result.title}\n${result.snippet}\n${result.url}\n")
                                    }
                                }.trim()
                                prompt = "$query\n\n$contextBlock"
                                screenContextNote = "Web results added to the draft. Review before sending."
                            }
                        },
                        onError = { error -> screenContextNote = "Web search error: $error" }
                    )
                }
            }) { Icon(Icons.Default.Search, "Search the web and attach results") }
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
private fun MessageCard(
    message: ChatMessage,
    onRegenerate: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null
) {
    val isUser = message.role == Role.USER
    val context = LocalContext.current
    val textToSpeech = remember { TextToSpeech(context.applicationContext) { } }
    DisposableEffect(textToSpeech) {
        onDispose { textToSpeech.shutdown() }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Card(
            modifier = Modifier.fillMaxWidth(0.87f),
            colors = CardDefaults.cardColors(containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                if (isUser) {
                    Text(message.text, color = MaterialTheme.colorScheme.onPrimaryContainer)
                } else {
                    MarkdownMessageText(message.text, MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!isUser && message.text.isNotBlank()) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("AI Sidebar response", message.text))
                    }, contentPadding = PaddingValues(top = 6.dp)) {
                        Text("Copy")
                    }
                    onRegenerate?.let { regenerate ->
                        TextButton(onClick = regenerate, contentPadding = PaddingValues(start = 10.dp, top = 6.dp)) {
                            Text("Regenerate")
                        }
                    }
                    TextButton(onClick = {
                        textToSpeech.language = Locale.getDefault()
                        textToSpeech.speak(message.text, TextToSpeech.QUEUE_FLUSH, null, "reply-${message.id}")
                    }, contentPadding = PaddingValues(start = 10.dp, top = 6.dp)) {
                        Text("Read aloud")
                    }
                }
                if (isUser) {
                    onEdit?.let { edit ->
                        TextButton(onClick = edit, contentPadding = PaddingValues(top = 6.dp)) { Text("Edit") }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownMessageText(markdown: String, color: Color) {
    var inCodeBlock = false
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        markdown.lines().forEach { line ->
            when {
                line.startsWith("```") -> inCodeBlock = !inCodeBlock
                inCodeBlock -> Text(
                    line,
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.42f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                line.startsWith("### ") -> Text(line.removePrefix("### "), color = color, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                line.startsWith("## ") -> Text(line.removePrefix("## "), color = color, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                line.startsWith("# ") -> Text(line.removePrefix("# "), color = color, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                line.startsWith("- ") || line.startsWith("* ") -> Text("• ${line.drop(2)}", color = color)
                line.isNotBlank() -> Text(line, color = color)
            }
        }
    }
}

private fun renderFirstPdfPage(context: Context, uri: Uri): String {
    val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Could not open PDF")
    descriptor.use { file ->
        PdfRenderer(file).use { renderer ->
            require(renderer.pageCount > 0) { "The PDF has no pages." }
            renderer.openPage(0).use { page ->
                val scale = minOf(1f, 1440f / maxOf(page.width, page.height).toFloat())
                val bitmap = Bitmap.createBitmap(
                    (page.width * scale).toInt().coerceAtLeast(1),
                    (page.height * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val encoded = ByteArrayOutputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
                    output.toByteArray()
                }
                bitmap.recycle()
                require(encoded.size <= 5 * 1024 * 1024) { "The rendered PDF page is too large." }
                return "data:image/jpeg;base64,${Base64.encodeToString(encoded, Base64.NO_WRAP)}"
            }
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
    val startOverlay = {
        context.startForegroundService(Intent(context, OverlayService::class.java))
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startOverlay()
    }
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
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                startOverlay()
                            }
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
