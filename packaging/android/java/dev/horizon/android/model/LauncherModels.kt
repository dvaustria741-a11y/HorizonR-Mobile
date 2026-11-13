package dev.horizon.android.model

enum class LauncherNavTab(val title: String) {
    HOME("Dashboard"),
    VERSIONS("Game & Assets"),
    STORE("Store"),
    FILES("Files & Mods"),
    SERVERS("Servers"),
    ACCOUNTS("Account & Skins"),
    LOGS("System Logs"),
    SETTINGS("Settings")
}

enum class StoreCategory(
    val label: String,
    val headerTitle: String,
    val kindFilter: String,
    val targetFolder: String,
    val badgeLabel: String
) {
    ALL("All", "Horizon Mods — All", "all", "horizon-mods", "ALL"),
    MOD("Mods", "Horizon Mods — Mods", "mod", "horizon-mods", "MOD"),
    NATIVE_MOD("Native Mods", "Horizon Mods — Native Mods", "native-mod", "horizon-native-mods", "NATIVE MOD"),
    MODPACK("Modpacks", "Horizon Mods — Modpacks", "modpack", "horizon-packs", "MODPACK"),
    RESOURCE_PACK("Resource Packs", "Horizon Mods — Resource Packs", "resourcepack", "resourcepacks", "RESOURCE PACK"),
    SHADER_PACK("Shader Packs", "Horizon Mods — Shader Packs", "shader", "shaderpacks", "SHADER PACK")
}

enum class StoreSource(val label: String, val badge: String) {
    HORIZON("Horizon", "HORIZON"),
    MODRINTH("Modrinth", "MODRINTH"),
    CURSEFORGE("CurseForge", "CURSEFORGE"),
    ZENKAI("Zenkai", "ZENKAI")
}

enum class StoreSortOption(val label: String) {
    MOST_DOWNLOADED("Most downloaded"),
    BEST_RATED("Best rated"),
    RECENTLY_UPDATED("Recently updated"),
    NAME_AZ("Name (A–Z)")
}

enum class StoreStatusFilter(val label: String) {
    ALL("All Projects"),
    INSTALLED("Installed Only"),
    UPDATES("Updates Available"),
    OFFICIAL_SIGNED("Official & Signed")
}

enum class StoreInstallState {
    NOT_INSTALLED,
    INSTALLED,
    UPDATE_AVAILABLE
}

data class StoreVersionRelease(
    val id: String,
    val name: String,
    val versionNumber: String,
    val mcVersions: List<String>,
    val kindLabel: String = "MOD",
    val releaseType: String = "release",
    val downloadsCount: Long = 0L,
    val datePublished: String = "Recently",
    val fileName: String = "",
    val downloadUrl: String = "",
    val sizeBytes: Long = 0L,
    val sha256: String = "",
    val signed: Boolean = true,
    val sha1: String = ""
) {
    val primaryMcVersion: String
        get() = mcVersions.firstOrNull() ?: "26.3"

    val formattedDownloads: String
        get() = when {
            downloadsCount >= 1_000_000L -> String.format(java.util.Locale.US, "%.1fM", downloadsCount / 1_000_000.0)
            downloadsCount >= 1_000L -> String.format(java.util.Locale.US, "%.1fk", downloadsCount / 1_000.0)
            downloadsCount > 0L -> downloadsCount.toString()
            else -> "1.2k"
        }
}

data class StoreCatalogItem(
    val id: String,
    val slug: String = "",
    val title: String,
    val summary: String,
    val author: String,
    val version: String,
    val supportedMcVersions: List<String>,
    val downloadUrl: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String = "",
    val category: StoreCategory,
    val kindBadge: String = "MOD",
    val pageUrl: String = "",
    val sourceCodeUrl: String = "",
    val iconUrl: String = "",
    val downloadsCount: Long = 1200L,
    val ratingScore: Float = 4.8f,
    val ratingCount: Int = 12,
    val isSignedOrOfficial: Boolean = true,
    val side: String = "client",
    val license: String = "MIT",
    val displayTags: List<String> = emptyList(),
    val requiredModIds: List<String> = emptyList(),
    val allVersions: List<StoreVersionRelease> = emptyList(),
    val sha1: String = "",
    val source: StoreSource = StoreSource.HORIZON,
    val sourceProjectId: String = ""
) {
    val isCompatibleWith26_3: Boolean
        get() = supportedMcVersions.any {
            it.contains("26.3") || it.equals("all", ignoreCase = true)
        }

    val versionBadge: String
        get() = if (supportedMcVersions.isEmpty()) "26.3" else supportedMcVersions.first()

    val formattedDownloads: String
        get() = when {
            downloadsCount >= 1_000_000L -> String.format(java.util.Locale.US, "%.1fM downloads", downloadsCount / 1_000_000.0)
            downloadsCount >= 1_000L -> String.format(java.util.Locale.US, "%.1fk downloads", downloadsCount / 1_000.0)
            else -> "$downloadsCount downloads"
        }

    val targetInstallFolder: String
        get() = when (category) {
            StoreCategory.RESOURCE_PACK -> "resourcepacks"
            StoreCategory.SHADER_PACK -> "shaderpacks"
            StoreCategory.MODPACK -> "horizon-packs"
            else -> "horizon-mods"
        }
}

data class StoreSearchPageResult(
    val items: List<StoreCatalogItem>,
    val currentPage: Int,
    val totalPages: Int,
    val totalHits: Int,
    val message: String = ""
)

data class WorldSaveDetailInfo(
    val folderName: String,
    val displayName: String,
    val gameMode: String,
    val difficulty: String,
    val playTimeFormatted: String,
    val worldSeed: String,
    val lastPlayedFormatted: String
)

data class ResourcePackDetailInfo(
    val title: String,
    val description: String,
    val packType: String,
    val fileName: String,
    val fileSizeFormatted: String,
    val formatVersion: Int
)

data class GithubReleaseUpdateInfo(
    val latestVersion: String,
    val releaseTitle: String,
    val downloadUrl: String,
    val htmlUrl: String
)

data class LauncherReleaseEntry(
    val version: String,
    val versionTuple: List<Int>,
    val title: String,
    val downloadUrl: String,
    val htmlUrl: String
)

enum class AccountType(val badge: String) {
    OFFLINE("Offline"),
    MICROSOFT("Microsoft"),
    ELYBY("Ely.by")
}

/**
 * Matches `crates/horizon-client/src/android/catalog.rs` `RendererMode` (`#[serde(rename_all = "kebab-case")]`):
 * - VulkanCompatibility -> "vulkan-compatibility"
 * - Vulkan -> "vulkan"
 * - OpenGl -> "open-gl"
 */
enum class HorizonRendererMode(val serdeKey: String, val label: String) {
    VULKAN_COMPAT("vulkan-compatibility", "Vulkan Compatibility"),
    VULKAN("vulkan", "Vulkan (High Performance, Default)"),
    OPENGL("open-gl", "OpenGL ES (Fallback)")
}

enum class HorizonInputHost(val serdeKey: String, val label: String) {
    AUTO("auto", "Auto Detect"),
    TOUCH("touch", "Touch Screen"),
    KEYBOARD_MOUSE("keyboard-mouse", "Keyboard & Mouse"),
    CONTROLLER("controller", "Gamepad / Controller")
}

enum class HorizonOrientation(val serdeKey: String, val label: String) {
    LANDSCAPE("landscape", "Landscape"),
    REVERSE_LANDSCAPE("reverse-landscape", "Reverse Landscape (Flipped)"),
    PORTRAIT("portrait", "Portrait"),
    REVERSE_PORTRAIT("reverse-portrait", "Reverse Portrait"),
    AUTO("auto", "Auto Sensor")
}

enum class HorizonContentOrigin(val serdeKey: String, val label: String) {
    HORIZON("horizon", "Horizon (playhorizon.dev)"),
    MODRINTH("modrinth", "Modrinth")
}

/**
 * Matches `crates/horizon-client/src/android/display.rs` `Aspect` (`#[serde(rename_all = "kebab-case")]`) in v0.7.5:
 * - Native -> "native"
 * - Wide -> "wide" (16:9)
 * - Classic -> "classic" (4:3)
 * - Square -> "square" (1:1)
 */
enum class HorizonScreenAspect(val serdeKey: String, val label: String) {
    NATIVE("native", "Native Fullscreen (Device Ratio)"),
    WIDE("wide", "Wide 16:9 Presentation"),
    CLASSIC("classic", "Classic 4:3 Presentation"),
    SQUARE("square", "Square 1:1 Presentation")
}

/**
 * Matches `horizon-controller.txt` `menuNavigation` in Horizon Android v0.7.5:
 * - Focus ("focus"): console-style D-pad & stick focus highlight
 * - Pointer ("pointer"): virtual cursor moved by left stick
 */
enum class HorizonControllerNavMode(val serdeKey: String, val label: String) {
    FOCUS("focus", "Focus Highlight (Console D-Pad / Stick)"),
    POINTER("pointer", "Virtual Pointer Cursor (Stick Cursor)")
}

data class ModPackageDetailInfo(
    val modId: String,
    val displayName: String,
    val version: String,
    val description: String,
    val author: String,
    val apiLine: String,
    val side: String,
    val capabilities: List<String>,
    val hasWasmBinary: Boolean,
    val fileName: String,
    val fileSizeFormatted: String
)

enum class HorizonBackgroundMode(val serdeKey: String, val label: String) {
    WALLPAPER("wallpaper", "Static HD Minecraft Wallpaper (16:9)"),
    YOUTUBE_TRAILER("youtube-trailer", "Auto-Play Minecraft YouTube Trailer")
}

data class MinecraftTrailerVideo(
    val videoId: String,
    val title: String,
    val publishedDate: String,
    val watchUrl: String = "https://www.youtube.com/watch?v=$videoId",
    val directMp4Url: String = ""
)

data class SavedCustomSkinItem(
    val id: String,
    val fileName: String,
    val displayName: String,
    val filePath: String,
    val slimModel: Boolean,
    val sourceLabel: String = "Custom Skin",
    val addedAt: Long = System.currentTimeMillis()
)

enum class LauncherThemePalette(val label: String, val primaryHex: Long, val secondaryHex: Long, val surfaceHex: Long) {
    MATERIAL_YOU("Volt (Default)", 0xFF79E59C, 0xFF79E59C, 0xFF0D0F16),
    EMBERMIRE("Embermire Dark", 0xFFFF7043, 0xFFFFB74D, 0xFF12151E),
    HORIZON_RUST("Horizon Rust", 0xFFFF5722, 0xFF26A69A, 0xFF0E1218),
    AMOLED_EMERALD("Emerald AMOLED", 0xFF66BB6A, 0xFFA5D6A7, 0xFF090C10)
}

data class LauncherAccount(
    val id: String,
    val username: String,
    val uuid: String,
    val accountType: AccountType,
    val isActive: Boolean = false,
    val skinPath: String = "",
    val slimSkinModel: Boolean = false,
    val skinSourceLabel: String = "Default (Classic Steve)",
    val capePath: String = ""
)

data class SkinDetectionResult(
    val success: Boolean,
    val skinFile: java.io.File?,
    val slimModel: Boolean,
    val sourceLabel: String,
    val message: String,
    val savedFileName: String = ""
)


enum class HorizonAiAgentProvider(val serdeKey: String, val label: String) {
    GEMINI_API("gemini", "Google AI Studio (Gemini API)"),
    LOCAL_SOCKET("local", "Local AI / Control Socket (horizon-control.json)")
}

data class GeminiModelOption(
    val modelId: String,
    val displayName: String,
    val badge: String,
    val description: String
)

val DEFAULT_GEMINI_MODELS = listOf(
    // === Gemini 4 Series (Flagship & Next-Gen Autonomous Models) ===
    GeminiModelOption(
        modelId = "gemini-4-argon",
        displayName = "Gemini 4 Argon (Flagship Autonomous Agent)",
        badge = "GEMINI 4 • ARGON",
        description = "4th-generation flagship model with real-time tactical reasoning, autonomous control execution, and deep understanding of the Minecraft 26.3 world"
    ),
    GeminiModelOption(
        modelId = "gemini-4.0-ultra",
        displayName = "Gemini 4.0 Ultra (Deep World & Redstone Reasoning)",
        badge = "GEMINI 4 • ULTRA",
        description = "Top reasoning capacity for large build architecture, complex redstone logic, and survival strategy"
    ),
    GeminiModelOption(
        modelId = "gemini-4.0-pro",
        displayName = "Gemini 4.0 Pro (Advanced Multi-Step Agent)",
        badge = "GEMINI 4 • PRO",
        description = "High-precision multi-step planning for exploration, crafting, and in-game command automation"
    ),
    GeminiModelOption(
        modelId = "gemini-4.0-flash",
        displayName = "Gemini 4.0 Flash (High-Speed Next-Gen Agent)",
        badge = "GEMINI 4 • FLASH",
        description = "Lightning-fast 4th-generation responses for direct control actions and real-time chat assistance"
    ),
    GeminiModelOption(
        modelId = "gemini-4.0-flash-lite",
        displayName = "Gemini 4.0 Flash-Lite (Ultra-Low Latency)",
        badge = "GEMINI 4 • LITE",
        description = "Ultra-light 4th-generation variant with the lowest latency and low token usage"
    ),

    // === Gemini 3.8 & 3.5 Series ===
    GeminiModelOption(
        modelId = "gemini-3.8-pro",
        displayName = "Gemini 3.8 Pro (Tactical & Survival Specialist)",
        badge = "GEMINI 3.8 • PRO",
        description = "Deep reasoning from the 3.8 series for world navigation, mob combat, and inventory management"
    ),
    GeminiModelOption(
        modelId = "gemini-3.8-flash",
        displayName = "Gemini 3.8 Flash (Fast Multimodal Agent)",
        badge = "GEMINI 3.8 • FLASH",
        description = "High-speed 3.8 series for command execution and instant survival guidance"
    ),
    GeminiModelOption(
        modelId = "gemini-3.8-flash-lite",
        displayName = "Gemini 3.8 Flash-Lite (Instant Lightweight)",
        badge = "GEMINI 3.8 • LITE",
        description = "Lightweight 3.8 series model for quick Q&A in Minecraft chat (@ai)"
    ),
    GeminiModelOption(
        modelId = "gemini-3.5-pro",
        displayName = "Gemini 3.5 Pro (Advanced Coding & Commands)",
        badge = "GEMINI 3.5 • PRO",
        description = "Excels at designing command blocks, structure coordinates, and progression strategy"
    ),
    GeminiModelOption(
        modelId = "gemini-3.5-flash",
        displayName = "Gemini 3.5 Flash (Balanced In-Game Agent)",
        badge = "GEMINI 3.5 • FLASH",
        description = "3.5 series balance of speed and accuracy to accompany your play sessions"
    ),
    GeminiModelOption(
        modelId = "gemini-3.5-flash-lite",
        displayName = "Gemini 3.5 Flash-Lite (Ultra-Fast Chat & Actions)",
        badge = "GEMINI 3.5 • LITE",
        description = "Fast and efficient 3.5 Flash-Lite model for chat replies and light actions with no delay"
    ),
    GeminiModelOption(
        modelId = "gemini-3.0-pro",
        displayName = "Gemini 3.0 Pro",
        badge = "GEMINI 3.0 • PRO",
        description = "Stable 3.0 series reasoning model for Minecraft world planning"
    ),
    GeminiModelOption(
        modelId = "gemini-3.0-flash",
        displayName = "Gemini 3.0 Flash",
        badge = "GEMINI 3.0 • FLASH",
        description = "Responsive 3.0 series model for the in-game assistant"
    ),

    // === Gemini 2.5 & 2.0 Series (Google AI Studio Direct) ===
    GeminiModelOption(
        modelId = "gemini-2.5-pro",
        displayName = "Gemini 2.5 Pro (Deep Thinking & Strategy)",
        badge = "GEMINI 2.5 • PRO",
        description = "Google AI Studio Pro model for in-depth analysis and complex action planning"
    ),
    GeminiModelOption(
        modelId = "gemini-2.5-flash",
        displayName = "Gemini 2.5 Flash (Fast Multimodal Agent)",
        badge = "GEMINI 2.5 • FLASH",
        description = "High-speed Google AI Studio Flash model for real-time agent control"
    ),
    GeminiModelOption(
        modelId = "gemini-2.5-flash-lite",
        displayName = "Gemini 2.5 Flash-Lite (High-Throughput Lite)",
        badge = "GEMINI 2.5 • LITE",
        description = "Quota-friendly Flash-Lite model for high-frequency chat replies"
    ),
    GeminiModelOption(
        modelId = "gemini-2.0-flash",
        displayName = "Gemini 2.0 Flash",
        badge = "GEMINI 2.0 • FLASH",
        description = "Model multimodal stabil seri 2.0 di Google AI Studio"
    ),
    GeminiModelOption(
        modelId = "gemini-2.0-flash-lite",
        displayName = "Gemini 2.0 Flash-Lite",
        badge = "GEMINI 2.0 • LITE",
        description = "Lightweight 2.0 series variant for fast responses"
    ),
    GeminiModelOption(
        modelId = "gemini-2.0-pro-exp",
        displayName = "Gemini 2.0 Pro Experimental",
        badge = "GEMINI 2.0 • EXP",
        description = "Experimental Pro model with advanced reasoning capabilities"
    ),

    // === Gemini 1.5 & Gemma 3 Series ===
    GeminiModelOption(
        modelId = "gemini-1.5-pro",
        displayName = "Gemini 1.5 Pro (2M Context Window)",
        badge = "GEMINI 1.5 • PRO",
        description = "Context memory of up to 2 million tokens for long survival sessions"
    ),
    GeminiModelOption(
        modelId = "gemini-1.5-flash",
        displayName = "Gemini 1.5 Flash",
        badge = "GEMINI 1.5 • FLASH",
        description = "Model cepat serbaguna seri 1.5"
    ),
    GeminiModelOption(
        modelId = "gemma-3-27b-it",
        displayName = "Gemma 3 27B Instruct (Google AI Studio)",
        badge = "GEMMA 3 • 27B",
        description = "Model instruksi terbuka Google Gemma 3 27B melalui endpoint Google AI Studio"
    )
)

data class HorizonConfigState(
    val profile: Int = 0,
    val renderer: HorizonRendererMode = HorizonRendererMode.VULKAN,
    val renderScalePercent: Int = 75,
    val orientation: HorizonOrientation = HorizonOrientation.LANDSCAPE,
    val screenAspect: HorizonScreenAspect = HorizonScreenAspect.NATIVE,
    val screenImmersive: Boolean = true,
    val screenAvoidCutout: Boolean = false,
    val displayRefreshHz: Int = 0,
    val performanceHintsAdpf: Boolean = true,
    val gpuStageProfiling: Boolean = false,
    val inputHost: HorizonInputHost = HorizonInputHost.AUTO,
    val keypadControls: Boolean = false,
    val sprintToggle: Boolean = false,
    val inventoryTouchControls: Boolean = true,
    val gyroAimingEnabled: Boolean = false,
    val gyroSensitivityPercent: Int = 100,
    val controllerVibration: Boolean = true,
    val controllerNavMode: HorizonControllerNavMode = HorizonControllerNavMode.FOCUS,
    val controllerSensitivityPercent: Int = 100,
    val controllerDeadzonePercent: Int = 15,
    val extraFullbright: Boolean = false,
    val extraDynamicLights: Boolean = false,
    val extraAutoReconnect: Boolean = true,
    val extraMouseTweaks: Boolean = true,
    val extraInventorySort: Boolean = true,
    val recordPerformance: Boolean = false,
    val modSource: HorizonContentOrigin = HorizonContentOrigin.HORIZON,
    val shaderSource: HorizonContentOrigin = HorizonContentOrigin.HORIZON,
    val defaultServer: String = "",
    val proxy: String = "",
    val mcRenderDistance: Int = 8,
    val mcSimulationDistance: Int = 8,
    val mcMaxFps: Int = 120,
    val mcGuiScale: Int = 0,
    val mcFov: Int = 70,
    val mcVsync: Boolean = true,
    val mcFullscreen: Boolean = true,
    val themePalette: LauncherThemePalette = LauncherThemePalette.MATERIAL_YOU,
    val useDynamicColor: Boolean = true,
    val enableSystemLog: Boolean = true,
    val disableAudioSafeMode: Boolean = false,
    val discordRichPresence: Boolean = true,
    val publicOnDeviceAssetDownload: Boolean = true,
    val backgroundMode: HorizonBackgroundMode = HorizonBackgroundMode.WALLPAPER,
    val useYoutubeTrailerWallpaper: Boolean = false,
    val muteTrailerVideo: Boolean = false,
    val ramAllocationMb: Int = 0,
    val aiAgentEnabled: Boolean = false,
    val aiAgentProvider: HorizonAiAgentProvider = HorizonAiAgentProvider.GEMINI_API,
    val geminiModel: String = "gemini-4-argon",
    val aiAgentAutoAssistChat: Boolean = true,
    val aiAgentAutonomousActions: Boolean = true,
    val aiAgentTaskPrompt: String = "Help the player in Minecraft 26.3, answer chat/instructions, and run control actions when asked.",
    val superPerfMode: Boolean = false,
    val batteryPerfMode: Boolean = false,
    val maxFpsMode: Boolean = false,
    val fastChunkUpload: Boolean = true,
    val trustShaderPacks: Boolean = false,
    val autoShaderSwitches: Boolean = true,
    val frameGeneration: Boolean = false,
    val frameGenRenderScale: Int = 100,
    val fpsBoostPack: Boolean = true,
    val aggressiveFpsTricks: Boolean = false
)

val MINECRAFT_SPLASH_QUOTES = listOf(
    "Never dig straight down!",
    "Punching trees since day one.",
    "Creeper? Aww man...",
    "100% Native Rust — Zero JVM lag!",
    "Watch out for Wardens in the Deep Dark.",
    "Always carry a water bucket in the Overworld!",
    "Diamonds are best found deep in Deepslate.",
    "Redstone engineers build computers inside blocks.",
    "Don't look an Enderman in the eyes!",
    "A stack of 64 torches keeps the phantoms away.",
    "Villager trading halls: 1 Emerald for Mending!",
    "Sleeping in the Nether is an explosive idea.",
    "Enchanting Table speaks Standard Galactic Alphabet.",
    "Elytra + Firework Rockets = Infinite Skies.",
    "Beware of Trapped Chests in Desert Temples!",
    "Craft a Shield before your first skeleton duel.",
    "Silk Touch saves the bee nest with all its bees.",
    "Fortune III turns one diamond ore into four!",
    "Netherite floats in lava — your items are safe.",
    "Copper Golems and Trial Chambers await!",
    "Build a portal with 10 Obsidian blocks.",
    "Golden Carrots give the best saturation in the game.",
    "Crouch near Sculk Sensors to walk silently.",
    "Boats on Blue Ice travel faster than minecarts!",
    "Feed a dolphin raw cod to find sunken treasure.",
    "Name a sheep 'jeb_' for a rainbow wool coat!",
    "Name any mob 'Dinnerbone' to flip it upside down.",
    "Cats scare Creepers away from your front door.",
    "Snow Golems leave a trail of snow in cool biomes.",
    "Axolotls grant Regeneration when you help them fight.",
    "Totem of Undying saves you from fatal falls.",
    "Soul Speed III makes Soul Sand valleys a breeze.",
    "Bartering Gold Ingots with Piglins for Ender Pearls.",
    "Conduits give Water Breathing and Night Vision underwater.",
    "Every world seed holds 60 million blocks of adventure.",
    "Minecraft 26.3 running natively in Rust on Android!"
)
