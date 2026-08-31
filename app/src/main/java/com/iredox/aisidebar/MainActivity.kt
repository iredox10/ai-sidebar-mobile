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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iredox.aisidebar.overlay.OverlayService
import com.iredox.aisidebar.api.AnthropicClient
import com.iredox.aisidebar.api.GoogleClient
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

private fun keyForProvider(provider: String): String = when (provider.lowercase()) {
    "openai" -> SecureKeyStore.KEY_OPENAI
    "anthropic" -> SecureKeyStore.KEY_ANTHROPIC
    "google" -> SecureKeyStore.KEY_GOOGLE
    "deepseek" -> SecureKeyStore.KEY_DEEPSEEK
    "openrouter" -> SecureKeyStore.KEY_OPENROUTER
    "custom" -> SecureKeyStore.KEY_CUSTOM
    else -> SecureKeyStore.KEY_OPENAI
}

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
    var apiKey by remember { mutableStateOf(secureKeyStore.readKey(keyForProvider(savedProviderSettings.provider)).orEmpty()) }
    var endpoint by remember { mutableStateOf(savedProviderSettings.endpoint) }
    var model by remember { mutableStateOf(savedProviderSettings.model) }
    var systemPrompt by remember { mutableStateOf(savedProviderSettings.systemPrompt) }
    var temperature by remember { mutableStateOf(savedProviderSettings.temperature) }
    var customBaseUrl by remember { mutableStateOf(savedProviderSettings.customBaseUrl) }
    var customName by remember { mutableStateOf(savedProviderSettings.customName) }
    var customModels by remember { mutableStateOf(savedProviderSettings.customModels) }
    var tavilyKey by remember { mutableStateOf(secureKeyStore.readKey(SecureKeyStore.KEY_TAVILY).orEmpty()) }
    var agenticTools by remember { mutableStateOf(savedProviderSettings.agenticTools) }
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
        val existing = chatStore.loadHistory().firstOrNull { it.id == activeConversationId }
        chatStore.saveConversation(
            StoredConversation(
                id = activeConversationId,
                title = conversationTitle(messages),
                updatedAt = System.currentTimeMillis(),
                messages = storedMessages,
                pinned = existing?.pinned ?: false,
                provider = provider,
                model = model,
                url = existing?.url ?: "",
                domain = existing?.domain ?: "",
                pageTitle = existing?.pageTitle ?: ""
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
            Destination.CHAT -> ChatScreen(
                Modifier.padding(padding), messages, provider, apiKey, endpoint, model,
                systemPrompt, temperature, customBaseUrl, tavilyKey, agenticTools,
                persistMessages, sharedText, onSharedTextConsumed
            )
            Destination.HISTORY -> ChatHistory(
                Modifier.padding(padding), conversationHistory,
                openConversation, deleteConversation, renameConversation,
                onTogglePin = { c -> chatStore.togglePin(c.id); conversationHistory = chatStore.loadHistory() },
                createNewConversation
            )
            Destination.SETTINGS -> SettingsScreen(
                Modifier.padding(padding),
                provider, {
                    provider = it
                    apiKey = secureKeyStore.readKey(keyForProvider(it)).orEmpty()
                    val current = providerSettingsStore.read()
                    providerSettingsStore.write(current.copy(provider = it))
                },
                apiKey, {
                    apiKey = it
                    secureKeyStore.writeKey(keyForProvider(provider), it)
                },
                endpoint, {
                    endpoint = it
                    val current = providerSettingsStore.read()
                    providerSettingsStore.write(current.copy(endpoint = it))
                },
                model, {
                    model = it
                    val current = providerSettingsStore.read()
                    providerSettingsStore.write(current.copy(model = it))
                },
                systemPrompt, {
                    systemPrompt = it
                    val current = providerSettingsStore.read()
                    providerSettingsStore.write(current.copy(systemPrompt = it))
                },
                temperature, {
                    temperature = it
                    val current = providerSettingsStore.read()
                    providerSettingsStore.write(current.copy(temperature = it))
                },
                customBaseUrl, {
                    customBaseUrl = it
                    val current = providerSettingsStore.read()
                    providerSettingsStore.write(current.copy(customBaseUrl = it))
                },
                customName, {
                    customName = it
                    val current = providerSettingsStore.read()
                    providerSettingsStore.write(current.copy(customName = it))
                },
                customModels, {
                    customModels = it
                    val current = providerSettingsStore.read()
                    providerSettingsStore.write(current.copy(customModels = it))
                },
                agenticTools, {
                    agenticTools = it
                    val current = providerSettingsStore.read()
                    providerSettingsStore.write(current.copy(agenticTools = it))
                },
                tavilyKey, {
                    tavilyKey = it
                    secureKeyStore.writeKey(SecureKeyStore.KEY_TAVILY, it)
                },
                chatStore
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
    systemPrompt: String,
    temperature: Float,
    customBaseUrl: String,
    tavilyKey: String,
    agenticTools: Boolean,
    onMessagesChanged: () -> Unit,
    sharedText: String?,
    onSharedTextConsumed: () -> Unit
) {
    var prompt by remember { mutableStateOf("") }
    var activeRequest by remember { mutableStateOf<StreamingRequest?>(null) }
    var screenContextNote by remember { mutableStateOf<String?>(null) }
    var imageDataUrls by remember { mutableStateOf(listOf<String>()) }
    var quotedText by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val openAiClient = remember { OpenAiCompatibleClient() }
    val anthropicClient = remember { AnthropicClient() }
    val googleClient = remember { GoogleClient() }
    val webSearchClient = remember { WebSearchClient() }
    val usageStore = remember { com.iredox.aisidebar.data.UsageStore(context.applicationContext) }
    var attachmentMenuOpen by remember { mutableStateOf(false) }
    val presetStore = remember { com.iredox.aisidebar.data.PresetStore(context.applicationContext) }
    var presets by remember { mutableStateOf(presetStore.load()) }
    LaunchedEffect(attachmentMenuOpen) { if (attachmentMenuOpen) presets = presetStore.load() }
    fun isVisionModel(m: String): Boolean {
        val vision = listOf("gpt-4o", "vision", "claude", "gemini", "gemma", "nemotron-nano-12b-vl", "omni")
        return vision.any { m.lowercase().contains(it) }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (imageDataUrls.size >= 3) { screenContextNote = "Up to 3 images per message."; return@rememberLauncherForActivityResult }
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not read image")
            require(bytes.size <= 5 * 1024 * 1024) { "Images must be 5 MB or smaller." }
            val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            "data:$mimeType;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        }.onSuccess {
            imageDataUrls = imageDataUrls + it
            screenContextNote = if (!isVisionModel(model)) "Image attached but \"${model}\" may not support vision — switch to GPT-4o, Claude or Gemini." else "Image attached (${imageDataUrls.size})."
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
        if (imageDataUrls.size >= 3) { screenContextNote = "Up to 3 images per message."; return@rememberLauncherForActivityResult }
        runCatching { renderFirstPdfPage(context, uri) }
            .onSuccess {
                imageDataUrls = imageDataUrls + it
                screenContextNote = if (!isVisionModel(model)) "PDF page attached as image — use a vision model." else "PDF page attached (${imageDataUrls.size} images)."
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
    fun resolveConfig(): ProviderConfig {
        val resolvedEndpoint = when (provider.lowercase()) {
            "openai" -> "https://api.openai.com/v1/chat/completions"
            "deepseek" -> "https://api.deepseek.com/chat/completions"
            "openrouter" -> "https://openrouter.ai/api/v1/chat/completions"
            "custom" -> if (customBaseUrl.isNotBlank()) customBaseUrl.trimEnd('/') + "/chat/completions" else endpoint
            else -> endpoint
        }
        return ProviderConfig(
            endpoint = resolvedEndpoint,
            apiKey = apiKey,
            model = model,
            systemPrompt = systemPrompt,
            temperature = temperature.toDouble()
        )
    }
    fun streamForProvider(
        config: ProviderConfig,
        msgs: List<RemoteChatMessage>,
        onDelta: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ): StreamingRequest {
        val p = provider.lowercase()
        val m = model
        val usageCallback: (Long?, Long?) -> Unit = { pt, ct -> usageStore.record(p, m, pt, ct) }
        return when (p) {
            "anthropic" -> anthropicClient.streamChat(config, msgs, onDelta, onComplete, onError, usageCallback)
            "google" -> googleClient.streamChat(config, msgs, onDelta, onComplete, onError, usageCallback)
            else -> openAiClient.streamChat(config, msgs, onDelta, onComplete, onError, usageCallback)
        }
    }
    fun regenerate(responseIndex: Int) {
        if (activeRequest != null || apiKey.isBlank()) return
        val response = messages.getOrNull(responseIndex) ?: return
        if (response.role != Role.ASSISTANT) return
        messages[responseIndex] = response.copy(text = "")
        onMessagesChanged()
        activeRequest = streamForProvider(
            config = resolveConfig(),
            msgs = messages.take(responseIndex).map { RemoteChatMessage(if (it.role == Role.USER) "user" else "assistant", it.text) },
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
                    onEdit = if (message.role == Role.USER && index == messages.lastIndex - 1) ({ editLastPrompt(index) }) else null,
                    onQuote = { quotedText = message.text.take(400) }
                )
            }
        }
        if (imageDataUrls.isNotEmpty()) {
            Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${imageDataUrls.size} image(s) attached", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { imageDataUrls = emptyList(); screenContextNote = "Images removed." }) { Text("Clear") }
                }
            }
        }
        quotedText?.let { q ->
            Card(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(q.take(120) + if (q.length > 120) "…" else "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { quotedText = null }) { Icon(Icons.Default.Close, "Remove quote") }
                }
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
            Box {
                IconButton(onClick = { attachmentMenuOpen = true }) { Icon(Icons.Default.Add, "Add context or attachment") }
                DropdownMenu(expanded = attachmentMenuOpen, onDismissRequest = { attachmentMenuOpen = false }) {
                    DropdownMenuItem(text = { Text("Add visible screen text") }, leadingIcon = { Icon(Icons.Default.Visibility, null) }, onClick = {
                        attachmentMenuOpen = false
                        if (!ScreenReadAccessibilityService.isEnabled()) {
                            screenContextNote = "Enable Accessibility to attach visible screen text."
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        } else {
                            val capture = ScreenReadAccessibilityService.captureActiveScreen()
                            if (capture?.visibleText.isNullOrBlank()) screenContextNote = "No safe visible text was available on this screen."
                            else {
                                val contextBlock = "[Visible screen context from ${capture?.packageName ?: "current app"}]\n${capture?.visibleText}"
                                prompt = listOf(prompt.trim(), contextBlock).filter { it.isNotBlank() }.joinToString("\n\n")
                                screenContextNote = "Visible screen context added. Review it before sending."
                            }
                        }
                    })
                    DropdownMenuItem(text = { Text("Attach image") }, leadingIcon = { Icon(Icons.Default.Image, null) }, onClick = { attachmentMenuOpen = false; imagePicker.launch("image/*") })
                    DropdownMenuItem(text = { Text("Attach text file") }, leadingIcon = { Icon(Icons.Default.AttachFile, null) }, onClick = { attachmentMenuOpen = false; textFilePicker.launch("text/plain") })
                    DropdownMenuItem(text = { Text("Attach first PDF page") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { attachmentMenuOpen = false; pdfPicker.launch("application/pdf") })
                    DropdownMenuItem(text = { Text("Speak prompt") }, leadingIcon = { Icon(Icons.Default.Mic, null) }, onClick = {
                        attachmentMenuOpen = false
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoiceInput()
                        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    })
                    DropdownMenuItem(text = { Text("Search the web") }, leadingIcon = { Icon(Icons.Default.Search, null) }, onClick = {
                        attachmentMenuOpen = false
                        val query = prompt.trim()
                        if (query.isBlank()) screenContextNote = "Write a search query first."
                        else {
                            screenContextNote = "Searching the web…"
                            webSearchClient.search(query, tavilyKey.takeIf { it.isNotBlank() }, { results ->
                                if (results.isEmpty()) screenContextNote = "No web results were found."
                                else {
                                    val contextBlock = buildString {
                                        append("[Web search results for: $query]\n")
                                        results.forEachIndexed { index, result -> append("${index + 1}. ${result.title}\n${result.snippet}\n${result.url}\n") }
                                    }.trim()
                                    prompt = "$query\n\n$contextBlock"
                                    screenContextNote = "Web results added to the draft. Review before sending."
                                }
                            }, { error -> screenContextNote = "Web search error: $error" })
                        }
                    })
                    if (presets.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        presets.forEach { preset ->
                            DropdownMenuItem(text = { Text(preset.name) }, onClick = {
                                attachmentMenuOpen = false
                                val applied = com.iredox.aisidebar.data.applyPresetPrompt(preset, prompt)
                                prompt = applied
                                screenContextNote = "Preset \"${preset.name}\" applied. Review before sending."
                            })
                        }
                    }
                }
            }
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask anything…") },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(22.dp)
            )
            Spacer(Modifier.width(10.dp))
            FloatingActionButton(onClick = {
                val cleanPrompt = prompt.trim()
                if (activeRequest != null) {
                    activeRequest?.cancel(); activeRequest = null; onMessagesChanged()
                } else if (cleanPrompt.isNotEmpty()) {
                    val quoted = quotedText?.let { "> ${it.replace("\n", " ")}\n\n" } ?: ""
                    val combined = quoted + cleanPrompt
                    val display = if (imageDataUrls.isNotEmpty()) "[${imageDataUrls.size} image(s)] $combined" else combined
                    messages += ChatMessage(System.nanoTime(), Role.USER, display)
                    val responseId = System.nanoTime() + 1
                    messages += ChatMessage(responseId, Role.ASSISTANT, "")
                    onMessagesChanged()
                    val toSend = combined
                    val imgs = imageDataUrls.toList()
                    prompt = ""; imageDataUrls = emptyList(); quotedText = null
                    if (apiKey.isBlank()) {
                        val messageIndex = messages.indexOfFirst { it.id == responseId }
                        messages[messageIndex] = messages[messageIndex].copy(text = "Add an API key for $provider under Settings to start streaming replies.")
                        onMessagesChanged()
                    } else {
                        val baseOutgoing = messages.filter { it.id != responseId }
                            .map { RemoteChatMessage(if (it.role == Role.USER) "user" else "assistant", it.text) }
                            .toMutableList()
                        if (imgs.isNotEmpty() && baseOutgoing.isNotEmpty()) {
                            baseOutgoing[baseOutgoing.lastIndex] = baseOutgoing.last().copy(content = toSend, imageDataUrls = imgs)
                        } else if (baseOutgoing.isNotEmpty()) {
                            baseOutgoing[baseOutgoing.lastIndex] = baseOutgoing.last().copy(content = toSend)
                        }
                        val isAgenticProvider = provider.lowercase() in setOf("openai", "deepseek", "openrouter", "custom")
                        if (agenticTools && isAgenticProvider) {
                            // agentic tool loop up to 5 rounds
                            var loopMessages = baseOutgoing.toMutableList()
                            var round = 0
                            fun doRound() {
                                if (round >= 5) {
                                    activeRequest = null; onMessagesChanged(); return
                                }
                                val cfg = resolveConfig()
                                activeRequest = openAiClient.streamChat(
                                    config = cfg,
                                    messages = loopMessages,
                                    onDelta = { delta ->
                                        val idx = messages.indexOfFirst { it.id == responseId }
                                        if (idx >= 0) messages[idx] = messages[idx].copy(text = messages[idx].text + delta)
                                    },
                                    onComplete = { activeRequest = null; onMessagesChanged() },
                                    onError = { error ->
                                        val idx = messages.indexOfFirst { it.id == responseId }
                                        if (idx >= 0) messages[idx] = messages[idx].copy(text = "Connection error: $error")
                                        activeRequest = null; onMessagesChanged()
                                    },
                                    onUsage = { pt, ct -> usageStore.record(provider.lowercase(), model, pt, ct) },
                                    tools = com.iredox.aisidebar.api.toolDefinitionsForOpenAI(),
                                    onToolCalls = { calls ->
                                        screenContextNote = "Running: ${calls.joinToString { it.name }}"
                                        // execute tools on background thread
                                        Thread {
                                            val results = calls.map { call -> call to com.iredox.aisidebar.api.runToolSync(context, call, tavilyKey.takeIf { it.isNotBlank() }) }
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                if (activeRequest == null) return@post
                                                // append assistant tool_calls and tool results to loopMessages
                                                loopMessages.add(com.iredox.aisidebar.api.RemoteChatMessage(role = "assistant", content = "", toolCalls = calls))
                                                results.forEach { (call, res) ->
                                                    loopMessages.add(com.iredox.aisidebar.api.RemoteChatMessage(role = "tool", content = res, toolCallId = call.id, toolName = call.name))
                                                }
                                                round++
                                                doRound()
                                            }
                                        }.start()
                                    }
                                )
                            }
                            doRound()
                        } else {
                            activeRequest = streamForProvider(
                                config = resolveConfig(),
                                msgs = baseOutgoing,
                                onDelta = { delta ->
                                    val messageIndex = messages.indexOfFirst { it.id == responseId }
                                    if (messageIndex >= 0) messages[messageIndex] = messages[messageIndex].copy(text = messages[messageIndex].text + delta)
                                },
                                onComplete = { activeRequest = null; onMessagesChanged() },
                                onError = { error ->
                                    val messageIndex = messages.indexOfFirst { it.id == responseId }
                                    if (messageIndex >= 0) messages[messageIndex] = messages[messageIndex].copy(text = "Connection error: $error")
                                    activeRequest = null; onMessagesChanged()
                                }
                            )
                        }
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
    onEdit: (() -> Unit)? = null,
    onQuote: (() -> Unit)? = null
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
                if (!isUser && message.id != 1L && message.text.isNotBlank()) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("AI Sidebar response", message.text))
                    }, contentPadding = PaddingValues(top = 6.dp)) { Text("Copy") }
                    onRegenerate?.let { regenerate ->
                        TextButton(onClick = regenerate, contentPadding = PaddingValues(start = 10.dp, top = 6.dp)) { Text("Regenerate") }
                    }
                    TextButton(onClick = {
                        textToSpeech.language = Locale.getDefault()
                        textToSpeech.speak(message.text, TextToSpeech.QUEUE_FLUSH, null, "reply-${message.id}")
                    }, contentPadding = PaddingValues(start = 10.dp, top = 6.dp)) { Text("Read aloud") }
                    onQuote?.let { q -> TextButton(onClick = q, contentPadding = PaddingValues(start = 10.dp, top = 6.dp)) { Text("Quote") } }
                }
                if (isUser) {
                    Row {
                        onEdit?.let { edit -> TextButton(onClick = edit, contentPadding = PaddingValues(top = 6.dp)) { Text("Edit") } }
                        onQuote?.let { q -> TextButton(onClick = q, contentPadding = PaddingValues(start = 10.dp, top = 6.dp)) { Text("Quote") } }
                    }
                } else if (message.text.isNotBlank()) {
                    onQuote?.let { q -> if (isUser.not() && message.id == 1L) TextButton(onClick = q, contentPadding = PaddingValues(top = 6.dp)) { Text("Quote") } }
                }
            }
        }
    }
}

@Composable
private fun MarkdownMessageText(markdown: String, color: Color) {
    var inCodeBlock = false
    var codeBlockLang = ""
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val lines = markdown.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("```") -> {
                    if (!inCodeBlock) {
                        codeBlockLang = line.removePrefix("```").trim()
                        inCodeBlock = true
                    } else {
                        inCodeBlock = false; codeBlockLang = ""
                    }
                }
                inCodeBlock -> {
                    Text(
                        line,
                        color = color,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.42f)).padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                line.trim().matches(Regex("^\\|?.*\\|.*\\|?.*")) && i + 1 < lines.size && lines[i + 1].trim().matches(Regex("^\\|?[\\s:-]+\\|[\\s|:-]+.*")) -> {
                    // simple markdown table header + separator -> render header and rows as monospace rows
                    val headers = line.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                    if (headers.isNotEmpty()) {
                        Text(headers.joinToString(" | "), color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(6.dp))
                    }
                    i++ // skip separator
                    // render following table rows while they contain |
                    while (i + 1 < lines.size && lines[i + 1].contains("|")) {
                        i++; val row = lines[i].split("|").map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" | ")
                        if (row.isNotBlank()) Text(row, color = color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp))
                    }
                }
                line.startsWith("### ") -> Text(line.removePrefix("### ").trim(), color = color, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                line.startsWith("## ") -> Text(line.removePrefix("## ").trim(), color = color, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                line.startsWith("# ") -> Text(line.removePrefix("# ").trim(), color = color, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                line.startsWith("> ") -> Text(line.removePrefix("> ").trim(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp).background(MaterialTheme.colorScheme.surfaceVariant).padding(6.dp))
                line.startsWith("- ") || line.startsWith("* ") || line.matches(Regex("^\\d+\\.\\s.*")) -> {
                    val content = if (line.startsWith("- ") || line.startsWith("* ")) line.drop(2) else line.replaceFirst(Regex("^\\d+\\.\\s"), "")
                    InlineMarkdownText("• $content", color, uriHandler)
                }
                line.trim() == "---" || line.trim() == "***" -> HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                line.trim().startsWith("https://") || line.trim().startsWith("http://") -> {
                    val trimmed = line.trim()
                    androidx.compose.foundation.text.ClickableText(
                        text = androidx.compose.ui.text.AnnotatedString(trimmed),
                        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.primary),
                        onClick = { uriHandler.openUri(trimmed) }
                    )
                }
                line.isNotBlank() -> InlineMarkdownText(line, color, uriHandler)
                else -> Spacer(Modifier.height(4.dp))
            }
            i++
        }
    }
}

@Composable
private fun InlineMarkdownText(raw: String, color: Color, uriHandler: androidx.compose.ui.platform.UriHandler) {
    // Parse inline: **bold**, *italic*, `code`, [label](url)
    val annotated = androidx.compose.ui.text.buildAnnotatedString {
        var idx = 0
        val linkPattern = Regex("\\[([^\\]]+)\\]\\((https?://[^)]+)\\)")
        // first extract links, then handle bold/italic/code inside
        val tokens = mutableListOf<Pair<String, String?>>() // text, url?
        var last = 0
        linkPattern.findAll(raw).forEach { m ->
            if (m.range.first > last) tokens.add(raw.substring(last, m.range.first) to null)
            tokens.add(m.groupValues[1] to m.groupValues[2])
            last = m.range.last + 1
        }
        if (last < raw.length) tokens.add(raw.substring(last) to null)
        tokens.forEach { (segment, url) ->
            if (url != null) {
                pushStringAnnotation(tag = "URL", annotation = url)
                withStyle(style = androidx.compose.ui.text.SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)) { append(segment) }
                pop()
            } else {
                // handle **bold**, *italic*, `code` in segment
                var s = segment
                // code `...`
                val parts = s.split("`")
                parts.forEachIndexed { pi, part ->
                    if (pi % 2 == 1) {
                        withStyle(style = androidx.compose.ui.text.SpanStyle(fontFamily = FontFamily.Monospace, background = MaterialTheme.colorScheme.surfaceVariant, fontSize = MaterialTheme.typography.bodySmall.fontSize)) { append(part) }
                    } else {
                        // bold **...**
                        var p = part
                        val boldRegex = Regex("\\*\\*(.+?)\\*\\*")
                        var bLast = 0
                        val boldMatches = boldRegex.findAll(p).toList()
                        if (boldMatches.isEmpty()) {
                            // italic *...* or _..._
                            val italicRegex = Regex("(\\*|_)([^*_]+?)\\1")
                            var iLast = 0
                            var italics = ""
                            // we will just append with italic handling
                            val italicMatches = italicRegex.findAll(p).toList()
                            if (italicMatches.isEmpty()) {
                                append(p)
                            } else {
                                italicMatches.forEach { im ->
                                    if (im.range.first > iLast) append(p.substring(iLast, im.range.first))
                                    withStyle(style = androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(im.groupValues[2]) }
                                    iLast = im.range.last + 1
                                }
                                if (iLast < p.length) append(p.substring(iLast))
                            }
                        } else {
                            var cur = 0
                            boldMatches.forEach { bm ->
                                if (bm.range.first > cur) {
                                    // handle italic inside before bold
                                    val before = p.substring(cur, bm.range.first)
                                    val italicRegex = Regex("(\\*|_)([^*_]+?)\\1")
                                    var iLast = 0
                                    val imatches = italicRegex.findAll(before).toList()
                                    if (imatches.isEmpty()) append(before) else {
                                        imatches.forEach { im ->
                                            if (im.range.first > iLast) append(before.substring(iLast, im.range.first))
                                            withStyle(style = androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(im.groupValues[2]) }
                                            iLast = im.range.last + 1
                                        }
                                        if (iLast < before.length) append(before.substring(iLast))
                                    }
                                }
                                withStyle(style = androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) { append(bm.groupValues[1]) }
                                cur = bm.range.last + 1
                            }
                            if (cur < p.length) {
                                val after = p.substring(cur)
                                val italicRegex = Regex("(\\*|_)([^*_]+?)\\1")
                                var iLast = 0
                                val imatches = italicRegex.findAll(after).toList()
                                if (imatches.isEmpty()) append(after) else {
                                    imatches.forEach { im ->
                                        if (im.range.first > iLast) append(after.substring(iLast, im.range.first))
                                        withStyle(style = androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(im.groupValues[2]) }
                                        iLast = im.range.last + 1
                                    }
                                    if (iLast < after.length) append(after.substring(iLast))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    androidx.compose.foundation.text.ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium.copy(color = color),
        onClick = { offset ->
            annotated.getStringAnnotations(tag = "URL", start = offset, end = offset).firstOrNull()?.let { uriHandler.openUri(it.item) }
        }
    )
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
    onTogglePin: (StoredConversation) -> Unit,
    onNewChat: () -> Unit
) {
    var pendingDelete by remember { mutableStateOf<StoredConversation?>(null) }
    var editingConversation by remember { mutableStateOf<StoredConversation?>(null) }
    var editedTitle by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }

    fun getPreviewText(c: StoredConversation): String {
        val msgs = c.messages
        for (i in msgs.size - 1 downTo 0) {
            val t = msgs[i].text.replace('\n', ' ').trim()
            if (t.isNotEmpty()) return if (t.length > 80) t.take(80) + "..." else t
        }
        return ""
    }
    fun formatTime(ts: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - ts
        return when {
            diff < 60_000 -> "now"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            diff < 86_400_000 -> "${diff / 3_600_000}h ago"
            diff < 604_800_000 -> "${diff / 86_400_000}d ago"
            else -> java.text.SimpleDateFormat("MMM d", Locale.getDefault()).format(java.util.Date(ts))
        }
    }
    val query = searchQuery.lowercase().trim()
    val filtered = if (query.isEmpty()) conversations else conversations.filter { c ->
        c.title.lowercase().contains(query) || c.messages.any { it.text.lowercase().contains(query) }
    }
    val pinned = filtered.filter { it.pinned }
    val unpinned = filtered.filter { !it.pinned }
    fun groupByDate(list: List<StoredConversation>): LinkedHashMap<String, List<StoredConversation>> {
        val map = LinkedHashMap<String, MutableList<StoredConversation>>()
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance()
        list.forEach { c ->
            cal.timeInMillis = now
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0); cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
            val today = cal.timeInMillis
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1); val yesterday = cal.timeInMillis
            cal.add(java.util.Calendar.DAY_OF_YEAR, -6); val week = cal.timeInMillis
            val label = when {
                c.updatedAt >= today -> "Today"
                c.updatedAt >= yesterday -> "Yesterday"
                c.updatedAt >= week -> "Last 7 days"
                else -> "Older"
            }
            map.getOrPut(label) { mutableListOf() }.add(c)
        }
        val order = listOf("Today", "Yesterday", "Last 7 days", "Older")
        val sorted = LinkedHashMap<String, List<StoredConversation>>()
        order.forEach { if (map.containsKey(it)) sorted[it] = map[it]!! }
        map.keys.filter { it !in order }.forEach { sorted[it] = map[it]!! }
        return sorted
    }

    @Composable
    fun HistoryItem(c: StoredConversation) {
        val timeStr = formatTime(c.updatedAt)
        val preview = getPreviewText(c)
        Card(modifier = Modifier.fillMaxWidth().clickable { onOpenConversation(c) }) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (c.pinned) Text("★ ", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                        Text(c.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                    if (preview.isNotBlank()) Text(preview, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(timeStr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (c.provider.isNotBlank()) Text(c.provider, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        if (c.domain.isNotBlank()) Text(c.domain, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${c.messages.size} msgs", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = { onTogglePin(c) }) { Text(if (c.pinned) "★" else "☆") }
                IconButton(onClick = { editingConversation = c; editedTitle = c.title }) { Icon(Icons.Default.Edit, "Rename") }
                IconButton(onClick = { pendingDelete = c }) { Icon(Icons.Default.Delete, "Delete") }
            }
        }
    }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("Your conversations", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Search conversations...") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
        Spacer(Modifier.height(6.dp))
        Text("${filtered.size} conversations", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        if (filtered.isEmpty()) {
            if (query.isNotEmpty()) {
                Text("No matching conversations", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
            } else {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Text("Your saved conversations will appear here.", modifier = Modifier.padding(16.dp))
                }
            }
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (pinned.isNotEmpty()) {
                    item { Text("Pinned", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                    items(pinned, key = { it.id }) { HistoryItem(it) }
                }
                val groups = groupByDate(unpinned)
                groups.forEach { (label, list) ->
                    item { Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(list, key = { it.id }) { HistoryItem(it) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onNewChat, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Start a new chat")
        }
    }
    pendingDelete?.let { conversation ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete conversation?") },
            text = { Text("This removes “${conversation.title}” from this device.") },
            confirmButton = { TextButton(onClick = { onDeleteConversation(conversation); pendingDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
    editingConversation?.let { conversation ->
        AlertDialog(
            onDismissRequest = { editingConversation = null },
            title = { Text("Rename conversation") },
            text = { OutlinedTextField(value = editedTitle, onValueChange = { editedTitle = it }, label = { Text("Title") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { onRenameConversation(conversation, editedTitle); editingConversation = null }) { Text("Save") } },
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
    onModelChange: (String) -> Unit,
    systemPrompt: String,
    onSystemPromptChange: (String) -> Unit,
    temperature: Float,
    onTemperatureChange: (Float) -> Unit,
    customBaseUrl: String,
    onCustomBaseUrlChange: (String) -> Unit,
    customName: String,
    onCustomNameChange: (String) -> Unit,
    customModels: String,
    onCustomModelsChange: (String) -> Unit,
    agenticTools: Boolean,
    onAgenticToolsChange: (Boolean) -> Unit,
    tavilyKey: String,
    onTavilyKeyChange: (String) -> Unit,
    chatStore: ChatStore
) {
    val context = LocalContext.current
    val providerClient = remember { OpenAiCompatibleClient() }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var modelStatus by remember { mutableStateOf<String?>(null) }
    var availableModels by remember(provider) { mutableStateOf(defaultModelsFor(provider)) }
    val profileStore = remember { com.iredox.aisidebar.data.ProfileStore(context.applicationContext) }
    var profilesState by remember { mutableStateOf(profileStore.load()) }
    var profileMenuOpen by remember { mutableStateOf(false) }
    var profileRenameOpen by remember { mutableStateOf(false) }
    var profileRenameText by remember { mutableStateOf("") }
    val usageStore = remember { com.iredox.aisidebar.data.UsageStore(context.applicationContext) }
    var usageTick by remember { mutableStateOf(0) }
    val presetStore = remember { com.iredox.aisidebar.data.PresetStore(context.applicationContext) }
    var presets by remember { mutableStateOf(presetStore.load()) }
    var presetName by remember { mutableStateOf("") }
    var presetPrompt by remember { mutableStateOf("") }
    val chatExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(chatStore.exportHistory()) }
                ?: error("Could not write the export.")
        }.onSuccess { exportStatus = "Conversation backup saved." }
            .onFailure { error -> exportStatus = error.message ?: "Could not export conversations." }
    }
    val chatImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val backup = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Could not read the selected backup.")
            chatStore.importHistory(backup)
        }.onSuccess { count -> exportStatus = "Imported $count conversation${if (count == 1) "" else "s"}." }
            .onFailure { error -> exportStatus = error.message ?: "Could not import conversations." }
    }
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
            Text("Profiles", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Bundle provider, model and prompt. Keys are shared globally.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(modifier = Modifier.weight(1f)) {
                            Button(onClick = { profileMenuOpen = true }, modifier = Modifier.fillMaxWidth()) { Text(profilesState.second, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            DropdownMenu(expanded = profileMenuOpen, onDismissRequest = { profileMenuOpen = false }) {
                                profilesState.first.keys.forEach { name ->
                                    DropdownMenuItem(text = { Text(name + if (name == profilesState.second) " (active)" else "") }, onClick = {
                                        profileMenuOpen = false
                                        profileStore.activate(name)
                                        profilesState = profileStore.load()
                                        val s = profilesState.first[name] ?: return@DropdownMenuItem
                                        onProviderChange(s.provider)
                                        onModelChange(s.model)
                                        onSystemPromptChange(s.systemPrompt)
                                        onTemperatureChange(s.temperature)
                                        onCustomBaseUrlChange(s.customBaseUrl)
                                        onCustomNameChange(s.customName)
                                        onCustomModelsChange(s.customModels)
                                        onEndpointChange(s.endpoint)
                                    })
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val base = "Profile"
                            var n = profilesState.first.size + 1
                            var name = "$base $n"
                            while (profilesState.first.containsKey(name)) { n++; name = "$base $n" }
                            val current = com.iredox.aisidebar.data.ProviderSettings(provider, endpoint, model, systemPrompt, temperature, customName, customBaseUrl, customModels)
                            profileStore.create(name, current)
                            profilesState = profileStore.load()
                        }) { Text("New") }
                        Button(onClick = {
                            val src = profilesState.second
                            var name = "$src copy"
                            var n = 2
                            while (profilesState.first.containsKey(name)) { name = "$src copy $n"; n++ }
                            profileStore.duplicate(src, name)
                            profilesState = profileStore.load()
                        }) { Text("Duplicate") }
                        Button(onClick = {
                            if (profilesState.first.size <= 1) return@Button
                            profileStore.delete(profilesState.second)
                            profilesState = profileStore.load()
                            val s = profilesState.first[profilesState.second] ?: return@Button
                            onProviderChange(s.provider); onModelChange(s.model); onSystemPromptChange(s.systemPrompt); onTemperatureChange(s.temperature)
                            onCustomBaseUrlChange(s.customBaseUrl); onCustomNameChange(s.customName); onCustomModelsChange(s.customModels); onEndpointChange(s.endpoint)
                        }) { Text("Delete") }
                        Button(onClick = { profileRenameText = profilesState.second; profileRenameOpen = true }) { Text("Rename") }
                    }
                    if (profileRenameOpen) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = profileRenameText, onValueChange = { profileRenameText = it }, label = { Text("New name") }, modifier = Modifier.weight(1f), singleLine = true)
                            Button(onClick = {
                                val newName = profileRenameText.trim()
                                if (newName.isNotBlank() && !profilesState.first.containsKey(newName)) {
                                    profileStore.rename(profilesState.second, newName)
                                    profilesState = profileStore.load()
                                }
                                profileRenameOpen = false
                            }) { Text("OK") }
                            TextButton(onClick = { profileRenameOpen = false }) { Text("Cancel") }
                        }
                    }
                }
            }
        }
        item {
            Text("Provider", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Choose a provider — keys are stored encrypted on device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("openai" to "OpenAI", "anthropic" to "Anthropic", "google" to "Google").forEach { (id, label) ->
                        AssistChip(onClick = { onProviderChange(id) }, label = { Text(label) }, leadingIcon = if (provider.lowercase() == id) ({ Text("✓") }) else null)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("deepseek" to "DeepSeek", "openrouter" to "OpenRouter", "custom" to "Custom").forEach { (id, label) ->
                        AssistChip(onClick = { onProviderChange(id) }, label = { Text(label) }, leadingIcon = if (provider.lowercase() == id) ({ Text("✓") }) else null)
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("${provider.lowercase().replaceFirstChar { it.uppercase() }} API key") },
                placeholder = { Text(if (provider == "custom") "Optional for local servers" else "Encrypted on this device") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                value = tavilyKey,
                onValueChange = onTavilyKeyChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Tavily API key (web search)") },
                placeholder = { Text("tvly-...  (optional, falls back to DuckDuckGo)") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
        }
        if (provider.lowercase() == "custom") {
            item {
                OutlinedTextField(
                    value = customName,
                    onValueChange = onCustomNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Display Name") },
                    placeholder = { Text("e.g. Ollama, LM Studio") },
                    singleLine = true
                )
            }
            item {
                OutlinedTextField(
                    value = customBaseUrl,
                    onValueChange = onCustomBaseUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Base URL (ends with /v1)") },
                    placeholder = { Text("https://api.example.com/v1") },
                    singleLine = true
                )
            }
            item {
                OutlinedTextField(
                    value = customModels,
                    onValueChange = onCustomModelsChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Models (comma-separated)") },
                    placeholder = { Text("llama3.3:70b, qwen2.5-coder:32b") },
                    singleLine = true
                )
            }
        } else if (provider.lowercase() == "openai" || provider.lowercase() == "openrouter") {
            item {
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = onEndpointChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Chat completions endpoint") },
                    supportingText = { Text("Override only if using a proxy. Leave default for official API.") },
                    singleLine = true
                )
            }
        }
        item {
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = onSystemPromptChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("System Prompt") },
                placeholder = { Text("You are a helpful assistant...") },
                minLines = 2,
                maxLines = 4
            )
        }
        item {
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Temperature", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(String.format(Locale.US, "%.1f", temperature), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Slider(value = temperature, onValueChange = onTemperatureChange, valueRange = 0f..2f, steps = 19)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Checkbox(checked = agenticTools, onCheckedChange = onAgenticToolsChange)
                Text("Agent mode — let the AI search, fetch pages and check date (may use extra requests)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
        }
        item {
            Text("Model", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Box {
                Button(onClick = { modelMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(model, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = modelMenuOpen, onDismissRequest = { modelMenuOpen = false }) {
                    availableModels.take(30).forEach { modelId ->
                        DropdownMenuItem(text = { Text(modelId) }, onClick = { onModelChange(modelId); modelMenuOpen = false })
                    }
                }
            }
            Button(
                onClick = {
                    if (apiKey.isBlank()) modelStatus = "Add an API key first."
                    else {
                        val cfgEndpoint = if (provider.lowercase() == "custom" && customBaseUrl.isNotBlank()) customBaseUrl.trimEnd('/') + "/chat/completions" else endpoint
                        modelStatus = "Loading models from your endpoint…"
                        providerClient.listModels(
                            ProviderConfig(endpoint = cfgEndpoint, apiKey = apiKey, model = model),
                            onSuccess = { loaded ->
                                availableModels = loaded.ifEmpty { defaultModelsFor(provider) }
                                modelStatus = if (loaded.isEmpty()) "No models returned; showing defaults." else "Loaded ${loaded.size} models."
                                modelMenuOpen = loaded.isNotEmpty()
                            },
                            onError = { error -> modelStatus = "Could not load models: $error" }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Load models from endpoint") }
            modelStatus?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        item {
            Text("Prompt Presets", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Quick prompts for the composer. Use {selection} for current draft and {page} for screen text.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = presetName, onValueChange = { presetName = it }, label = { Text("Name") }, placeholder = { Text("e.g. Explain") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = presetPrompt, onValueChange = { presetPrompt = it }, label = { Text("Prompt") }, placeholder = { Text("Explain this: {selection}") }, modifier = Modifier.fillMaxWidth(), minLines = 1)
                Button(onClick = {
                    if (presetName.trim().isNotBlank() && presetPrompt.trim().isNotBlank()) {
                        val newList = presets + com.iredox.aisidebar.data.PromptPreset(presetName.trim(), presetPrompt.trim())
                        presetStore.save(newList); presets = newList; presetName = ""; presetPrompt = ""
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Add Preset") }
                if (presets.isEmpty()) {
                    Text("No presets yet.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    presets.forEachIndexed { idx, p ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(p.prompt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                TextButton(onClick = {
                                    val newList = presets.toMutableList().apply { removeAt(idx) }
                                    presetStore.save(newList); presets = newList
                                }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
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
            Text("Backup", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { chatExporter.launch("ai-sidebar-mobile-chats.json") }) { Text("Export") }
                Button(onClick = { chatImporter.launch(arrayOf("application/json")) }) { Text("Import") }
            }
            exportStatus?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
            Text("Backups contain chats only. API keys are never exported or imported.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Text("Usage & Cost", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            // trigger recomposition after clear
            usageTick.let {
                val agg1 = usageStore.aggregate(1)
                val agg7 = usageStore.aggregate(7)
                val agg30 = usageStore.aggregate(30)
                val aggAll = usageStore.aggregate(9999)
                fun money(c: Double) = if (c > 0.01) "$${String.format(Locale.US, "%.2f", c)}" else if (c > 0) "$${String.format(Locale.US,"%.4f", c)}" else "$0.00"
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Today" to agg1, "7 days" to agg7, "All" to aggAll).forEach { (label, agg) ->
                            Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(Modifier.padding(8.dp)) {
                                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(money(agg.totals.cost), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text("${agg.totals.count} msgs", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    val perModel = aggAll.perModel.entries.sortedByDescending { it.value.cost }
                    if (perModel.isEmpty()) {
                        Text("No usage recorded yet. Send messages to see cost estimates.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            perModel.forEach { (key, e) ->
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(key, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(money(e.cost), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                                Text("in ${e.`in`} / out ${e.out} — ${e.count} msgs", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { usageStore.clear(); usageTick++ }) { Text("Clear usage") }
                        Text("${usageStore.dayCount()} days kept", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
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

private fun defaultModelsFor(provider: String): List<String> = when (provider.lowercase()) {
    "anthropic" -> listOf("claude-sonnet-4-20250514", "claude-3-5-sonnet-20241022", "claude-3-5-haiku-20241022", "claude-opus-4-20250514")
    "google" -> listOf("gemini-3-flash-preview", "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro", "gemini-3.1-flash-lite")
    "deepseek" -> listOf("deepseek-v4-flash", "deepseek-v4-pro")
    "openrouter" -> listOf("openrouter/free", "openai/gpt-oss-20b:free", "google/gemma-4-31b-it:free", "nvidia/nemotron-3-nano-30b-a3b:free")
    "custom" -> listOf("llama3.3:70b", "qwen2.5-coder:32b")
    else -> listOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "o3-mini")
}
