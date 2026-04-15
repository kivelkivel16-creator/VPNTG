package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.dto.RulesetItem
import com.v2ray.ang.enums.EConfigType

/**
 * Telegram VPN Configuration Manager
 * Handles the hardcoded VLESS server and routing for Telegram only
 */
object TelegramVpnConfig {

    const val SERVER_GUID = "telegram-vpn-server"

    // VLESS Server Configuration
    const val SERVER_ADDRESS = "163.5.180.89"
    const val SERVER_PORT = 8443
    const val SERVER_UUID = "22faa5c3-3d86-4a31-8b0c-873f1e3ebc21"
    const val SERVER_FLOW = ""

    // Reality Configuration
    const val REALITY_DEST = "www.microsoft.com:443"
    const val REALITY_SNI = "www.microsoft.com"
    const val REALITY_FINGERPRINT = "chrome"
    const val REALITY_PUBLIC_KEY = "U-KX0IXOGK-_FU_1SAROQDHheSWkbFMmtQpobCY0b14"
    const val REALITY_SHORT_ID = "ce39eccf69fbb727"
    const val XHTTP_PATH = "/api/v1/updates"
    const val XHTTP_MODE = "stream-up"
    const val XHTTP_EXTRA = """{"xPaddingBytes":"100-500"}"""
    const val VPN_TUN_MTU = 1400

    // Known Telegram package names (official + common forks)
    val TELEGRAM_PACKAGES = setOf(
        "org.telegram.messenger",          // Official Telegram
        "org.telegram.messenger.web",      // Telegram Web
        "org.thunderdog.challegram",       // Telegram X
        "com.nekogramx.app",               // NekoX
        "nekox.messenger",                 // NekoX alt
        "com.neko.nekogram",               // Nekogram
        "org.telegram.plus",               // Plus Messenger
        "com.exteragram.messenger",        // Exteragram
        "it.owlgram.android",              // OWL Gram
        "com.nicegram.app"                 // Nicegram
    )

    const val PREF_ROUTING_MODE = "pref_routing_mode"

    enum class RoutingMode { TELEGRAM_ONLY, SMART_RUSSIA, ALL_TRAFFIC }

    fun getSavedRoutingMode(): RoutingMode {
        val name = MmkvManager.decodeSettingsString(PREF_ROUTING_MODE) ?: return RoutingMode.TELEGRAM_ONLY
        return runCatching { RoutingMode.valueOf(name) }.getOrDefault(RoutingMode.TELEGRAM_ONLY)
    }

    /**
     * Initialize Telegram VPN configuration
     */
    fun initializeConfig(context: Context) {
        MmkvManager.encodeRoutingRulesets(null)
        MmkvManager.encodeStartOnBoot(true)
        MmkvManager.encodeSettings(AppConfig.PREF_VPN_MTU, VPN_TUN_MTU.toString())
        saveServerConfig()
        applyRoutingMode(context, getSavedRoutingMode())
    }

    /**
     * Switch routing mode at runtime.
     */
    fun applyRoutingMode(context: Context, mode: RoutingMode) {
        MmkvManager.encodeSettings(PREF_ROUTING_MODE, mode.name)
        MmkvManager.encodeRoutingRulesets(null)
        when (mode) {
            RoutingMode.TELEGRAM_ONLY -> {
                saveRoutingRulesets()
                savePerAppProxySettings(context)
            }
            RoutingMode.SMART_RUSSIA -> {
                saveSmartRussiaRulesets()
                disablePerAppProxy()
            }
            RoutingMode.ALL_TRAFFIC -> {
                saveGlobalRoutingRulesets()
                disablePerAppProxy()
            }
        }
    }

    /**
     * Configure per-app proxy: only route Telegram apps through VPN.
     * Uses proxy mode (allowlist) — only listed apps go through VPN.
     */
    private fun savePerAppProxySettings(context: Context) {
        val pm = context.packageManager
        // Filter to only installed packages
        val installedTelegramApps = TELEGRAM_PACKAGES.filter { pkg ->
            try { pm.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
        }.toMutableSet()

        // Enable per-app proxy in proxy (allowlist) mode
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, true)
        MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, false) // proxy mode = allowlist
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, installedTelegramApps)
    }

    /**
     * Create ProfileItem for VLESS + Reality configuration
     */
    private fun createVlessProfile(): ProfileItem {
        return ProfileItem(
            configType = EConfigType.VLESS,
            remarks = "Telegram VPN",
            server = SERVER_ADDRESS,
            serverPort = SERVER_PORT.toString(),
            password = SERVER_UUID,
            method = "none",
            flow = SERVER_FLOW,
            network = "xhttp",
            security = "reality",
            sni = REALITY_SNI,
            fingerPrint = REALITY_FINGERPRINT,
            publicKey = REALITY_PUBLIC_KEY,
            shortId = REALITY_SHORT_ID,
            spiderX = "",
            path = XHTTP_PATH,
            xhttpMode = XHTTP_MODE,
            xhttpExtra = XHTTP_EXTRA
        )
    }

    /**
     * Save server configuration to MmkvManager
     */
    private fun saveServerConfig() {
        val profile = createVlessProfile()
        MmkvManager.encodeServerConfig(SERVER_GUID, profile)
    }

    /**
     * Get Telegram routing ruleset for domains
     * Routes only Telegram traffic through VPN, all other traffic direct
     */
    fun getTelegramDomainRuleset(): RulesetItem {
        return RulesetItem(
            remarks = "Telegram Domains",
            outboundTag = "proxy",
            enabled = true,
            domain = listOf(
                // Core + clients
                "domain:telegram.org",
                "domain:tdesktop.com",
                "domain:telegra.ph",
                "domain:graph.org",
                "domain:t.me",
                "domain:telegram.me",
                "domain:telegram.dog",
                "domain:stel.com",
                "domain:telesco.pe",
                "domain:tg.dev",
                // API / web / desktop updates (часто отдельно от «чата»)
                "domain:api.telegram.org",
                "domain:core.telegram.org",
                "domain:web.telegram.org",
                "domain:desktop.telegram.org",
                "domain:macos.telegram.org",
                "domain:updates.tdesktop.com",
                // CDN / медиа (фото, видео, файлы)
                "domain:cdn.telegram.org",
                "domain:cdn1.telegram.org",
                "domain:cdn2.telegram.org",
                "domain:cdn3.telegram.org",
                "domain:cdn4.telegram.org",
                "domain:cdn5.telegram.org",
                "domain:pluto.telegram.org",
                "domain:venus.telegram.org",
                "domain:aurora.telegram.org",
                "domain:vesta.telegram.org"
            )
        )
    }

    /**
     * Get Telegram routing ruleset for IP ranges
     */
    fun getTelegramIpRuleset(): RulesetItem {
        return RulesetItem(
            remarks = "Telegram IPs",
            outboundTag = "proxy",
            enabled = true,
            ip = listOf(
                // https://core.telegram.org/resources/cidr (основные DC / CDN IPv4)
                "149.154.160.0/20",
                "149.154.164.0/22",
                "149.154.172.0/22",
                "91.108.4.0/22",
                "91.108.8.0/22",
                "91.108.12.0/22",
                "91.108.16.0/22",
                "91.108.20.0/22",
                "91.108.56.0/22",
                "91.105.0.0/22",
                "91.105.4.0/22",
                "95.161.0.0/16",
                "185.76.151.0/24"
            )
        )
    }

    /**
     * Save routing rulesets to MmkvManager.
     * Order matters: rules are evaluated top to bottom.
     * 1. Private IPs → direct (LAN, loopback)
     * 2. Telegram domains → proxy
     * 3. Telegram IPs → proxy
     * 4. Everything else → direct (so only Telegram goes through VPN)
     */
    private fun saveRoutingRulesets() {
        val newRulesets = mutableListOf(
            RulesetItem(
                remarks = "Private IPs Direct",
                outboundTag = AppConfig.TAG_DIRECT,
                enabled = true,
                ip = listOf("geoip:private")
            ),
            getTelegramDomainRuleset(),
            getTelegramIpRuleset()
        )
        MmkvManager.encodeRoutingRulesets(newRulesets)
        MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY, "AsIs")
    }

    /**
     * Global mode: route all traffic through VPN, only bypass LAN.
     */
    private fun saveGlobalRoutingRulesets() {
        val newRulesets = mutableListOf(
            RulesetItem(
                remarks = "Private IPs Direct",
                outboundTag = AppConfig.TAG_DIRECT,
                enabled = true,
                ip = listOf("geoip:private")
            ),
            RulesetItem(
                remarks = "All Traffic Proxy",
                outboundTag = AppConfig.TAG_PROXY,
                enabled = true,
                port = "0-65535"
            )
        )
        MmkvManager.encodeRoutingRulesets(newRulesets)
        MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY, "AsIs")
    }

    /**
     * Smart Russia mode: bypass RU domains/IPs + Black Russia, proxy everything else.
     *
     * blackrussia.com and all subdomains resolve to AWS CloudFront IPs (13.223.25.84 / 54.243.117.197).
     * These are NOT in geoip:ru, so we add them explicitly.
     * blackhub.games resolves to 87.251.65.8 — Russian IP, covered by geoip:ru.
     * brgame.ru — DNS non-existent, covered by domain rule as fallback.
     *
     * domainStrategy = IPOnDemand: resolves ALL domains to IP immediately before matching,
     * so even if the game client connects by IP directly, the rule still fires.
     */
    private fun saveSmartRussiaRulesets() {
        val newRulesets = mutableListOf(
            RulesetItem(remarks = "Private IPs Direct", outboundTag = AppConfig.TAG_DIRECT, enabled = true,
                ip = listOf("geoip:private")),
            RulesetItem(remarks = "Black Russia Domains Direct", outboundTag = AppConfig.TAG_DIRECT, enabled = true,
                domain = listOf(
                    "domain:blackrussia.com",
                    "domain:blackhub.games",
                    "domain:brgame.ru",
                    "domain:launcher.brgame"
                )),
            // blackrussia.com resolves to AWS CloudFront — not in geoip:ru
            RulesetItem(remarks = "Black Russia IPs Direct (AWS CloudFront)", outboundTag = AppConfig.TAG_DIRECT, enabled = true,
                ip = listOf("13.223.25.84", "54.243.117.197", "13.224.0.0/14", "13.32.0.0/15")),
            RulesetItem(remarks = "Russia domains Direct", outboundTag = AppConfig.TAG_DIRECT, enabled = true,
                domain = listOf("geosite:category-ru")),
            RulesetItem(remarks = "Russia IPs Direct", outboundTag = AppConfig.TAG_DIRECT, enabled = true,
                ip = listOf("geoip:ru")),
            RulesetItem(remarks = "All other Proxy", outboundTag = AppConfig.TAG_PROXY, enabled = true,
                port = "0-65535")
        )
        MmkvManager.encodeRoutingRulesets(newRulesets)
        // IPOnDemand: resolve ALL domains to IP before matching — catches game clients
        // that connect directly by IP without going through DNS
        MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY, "IPOnDemand")
    }

    /**
     * Disable per-app proxy so all apps go through VPN.
     */
    private fun disablePerAppProxy() {
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, false)
    }
}
