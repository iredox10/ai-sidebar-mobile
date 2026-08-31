# AI Sidebar Mobile

Personal Android companion app based on the `ai-sidebar-extension` browser extension. It provides a normal chat app first, then adds a floating overlay and explicitly user-enabled screen context.

See [MOBILE_APP_PLAN.md](MOBILE_APP_PLAN.md) for scope, architecture, permissions, privacy decisions, and the delivery roadmap.

## Current milestone

Phase 1 foundation: Compose app shell, local in-memory chat state, provider settings, overlay permission/status UI, and service contracts for the future overlay and screen reader.

## Open in Android Studio

1. Open this directory as a project.
2. Use JDK 17 (or Android Studio's embedded JDK 17/21) and install Android SDK Platform 35 if Android Studio asks.
3. Sync Gradle, then run the `app` configuration on a physical Android phone or emulator.

The overlay is intentionally inactive until the user grants the Android system permission. Screen inspection will also require the separate accessibility opt-in in a later milestone.

> Development note: the machine that scaffolded this project currently exposes only Java 25; Gradle's Kotlin DSL cannot start on that Java release yet. Set Android Studio/Gradle to JDK 17 or 21 before building.
