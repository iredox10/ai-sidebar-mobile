# AI Sidebar Mobile — product and build plan

## Purpose

Build a personal-use Android application that brings the core value of the existing AI Sidebar Chrome extension to a phone: chat with an AI, provide the current screen as context when the user asks, and make the chat available from a movable floating control above other apps.

The target is **Android, native Kotlin, Jetpack Compose**. This is deliberately Android-first: iOS does not offer a general-purpose, always-on-top overlay or equivalent cross-app accessibility tree access.

## What the existing extension does

The source extension is a multi-provider AI assistant with a browser side panel and an in-page mini chat. It supports chat history, streaming replies, OpenAI-compatible, Anthropic and Google providers, web search, page/selection/screenshot/PDF context, prompt presets, provider settings, usage tracking, voice input/output, and optional agent tools such as web search, URL fetching, opening tabs, bookmarks and downloads.

## Mobile equivalent

| Extension capability | Android implementation |
| --- | --- |
| Browser side panel | Main Compose app: chats, conversation, settings, usage |
| In-page floating chat | `TYPE_APPLICATION_OVERLAY` bubble and expandable Compose panel |
| Read page / selection | Accessibility node text from the foreground app, only after user turns it on |
| Screenshot context | User-triggered capture, then optional OCR/vision attachment |
| Browser commands | Safe Android alternatives: open URL via intent, download via MediaStore, app-owned bookmarks |
| Context-menu actions | Share target and `ACTION_PROCESS_TEXT` actions for selected text |
| Chrome storage | Room for chats/usage, DataStore for preferences, Keystore-backed encrypted secrets |

## User experience

1. The user opens the app, chooses a provider, enters an API key, and starts a chat.
2. They can share selected text or a URL to AI Sidebar from any Android app.
3. After granting “display over other apps,” they enable a small draggable bubble. Tapping it opens a compact chat panel without leaving the current app.
4. In the panel, “Add screen context” retrieves visible, non-sensitive accessibility text from the current foreground app. “Capture screenshot” is always a separate explicit action.
5. The assistant receives a bounded context card, gives a streamed response, and stores the conversation locally.

No automatic background screen capture is permitted. Password fields and content marked sensitive are excluded from context. The app must visibly indicate when screen context was included.

## Architecture

```text
Compose app / Overlay panel
        │
        ├── ChatViewModel ── repositories ── Room + DataStore + encrypted keys
        │
        ├── ProviderClient ── OkHttp SSE ── OpenAI-compatible / Anthropic / Google
        │
        ├── OverlayService ── WindowManager bubble + Compose panel
        │
        └── ScreenReadAccessibilityService ── visible node tree / optional screenshot
```

Key modules/packages:

- `ui`: chat, settings and permission screens.
- `data`: repositories, Room entities/DAOs and secure preference storage.
- `api`: provider clients, SSE parser, model configuration and tool loop.
- `overlay`: foreground service, bubble, overlay panel and its lifecycle.
- `screen`: accessibility tree extraction, sensitive-content filtering and screenshot/OCR flow.
- `tools`: web search, URL fetch, date, open URL, save bookmark and download file.

## Phase plan

### Phase 1 — usable chat foundation (started)

- Kotlin/Compose project, Material 3 theme and navigation shell.
- Local chat state and a polished conversation composer.
- Provider, model and API-key settings screen.
- Overlay permission status and service contract.
- Manifest declarations for overlay, notifications and foreground service.

### Phase 2 — persistence and AI streaming

- Room database for chats, messages, pins and usage.
- DataStore settings and Android Keystore-encrypted API keys.
- OpenAI-compatible streaming client with cancellation, errors and token usage.
- First real provider path: OpenAI / OpenRouter / DeepSeek-compatible endpoints.

The scaffold now persists a local multi-conversation history, including titles, renaming, timestamps, opening older chats, and deletion with confirmation. Indexing, pins and usage records move to Room in the next persistence increment.

The initial OpenAI-compatible SSE client and its cancellable chat integration are now in progress. API keys are stored using a device-bound Android Keystore AES-GCM key, while provider, endpoint, and model are persisted separately as non-secret settings. Multi-profile support follows.

### Phase 3 — providers and assistant features

- Anthropic and Google SSE adapters.
- Model profiles and connection test action.
- Markdown, copy/regenerate/edit-resend, TTS/STT and share input.
- Attach images, PDFs and text files, with bounded context size.

The first image attachment path is implemented: the system picker creates a data-URL attachment capped at 5 MB and sends it in the standard OpenAI vision content format. PDF and text-file extraction follow.

### Phase 4 — floating overlay

- Foreground `OverlayService` with persistent notification.
- Draggable edge-snapping bubble and compact Compose panel.
- Expand, collapse, open full app and stop controls.
- Quick Settings tile and battery-optimization guidance.

The overlay scaffold now includes a focusable compact chat card. It can stream through the saved OpenAI-compatible profile, explicitly append visible screen text to its draft, and load/save the current shared local conversation. Shared Room history is next.

### Phase 5 — screen-aware assistant

- Accessibility-service onboarding with plain-language disclosure.
- Extract the foreground app's visible text and bounds, remove password/sensitive nodes, cap length.
- Explicit “Add screen context” attachment with an app-name and character-count label.
- User-triggered screenshot flow and optional OCR/vision attachment.
- Long-press element selector where Android exposes node bounds.

The scaffold now includes accessibility onboarding plus an explicit visible-text capture action in the composer. It adds a clearly labelled context block for the user to review before sending; it does not capture text automatically.

### Phase 6 — tools and parity

- Web search and URL fetch with sources.
- Open URL, save in-app bookmark, and download through MediaStore.
- Prompt presets, auto-search decision, provider usage/cost dashboards and import/export.

Text sharing and `ACTION_PROCESS_TEXT` are now wired to prefill the assistant composer. Image, file, and URL sharing are subsequent additions.

## Android permissions and policy

`INTERNET`, `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS` and an accessibility service are expected. Screen capture uses a separate MediaProjection consent flow when needed.

This is intended for personal sideloading. General-purpose accessibility-driven automation and cross-app capture create Play policy restrictions, so a public Play Store release would need a much narrower and policy-reviewed feature set.

## Acceptance checks by milestone

- Phase 1: app installs, settings persist for the session, chats can be created and overlay permission state is accurate.
- Phase 2: user can send and cancel a streamed response; chat remains after restarting the app.
- Phase 4: bubble opens from another app, moves, collapses and stops cleanly.
- Phase 5: the user can explicitly attach safe visible text from another app and see exactly what was included.

## Decisions still needed from the owner

1. Final application id and display name (the scaffold uses `com.iredox.aisidebar` / “AI Sidebar”).
2. Primary AI provider and model for the first connected build.
3. Whether screenshots should be part of the first screen-context release or follow after text-only context works.
4. Visual branding and icon before packaging a signed APK.
