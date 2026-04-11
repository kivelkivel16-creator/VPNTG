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
    const val SERVER_FLOW = "xtls-rprx-vision"

    // Reality Configuration
    const val REALITY_DEST = "www.microsoft.com:443"
    const val REALITY_SNI = "www.microsoft.com"
    const val REALITY_FINGERPRINT = "chrome"
    const val REALITY_PUBLIC_KEY = "U-KX0IXOGK-_FU_1SAROQDHheSWkbFMmtQpobCY0b14"
    const val REALITY_SHORT_ID = "ce39eccf69fbb727"

    /**
     * MTU для TUN/HEV: 1280 — IPv6 minimum PMTU (RFC 8200), на практике самый
     * «безболезненный» размер под LTE/Wi‑Fi + VPN + SOCKS + длинный hop в Европу:
     * меньше шансов на фрагментацию/ретраи TCP, чем 1500. Минус — чуть больше
     * накладных заголовков на мегабайт; если скорость сухая, можно 1320–1400.
     */
    const val VPN_TUN_MTU = 1280

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

    /**
     * Initialize Telegram VPN configuration
     * Saves the server config, routing rulesets, and per-app proxy settings
     */
    fun initializeConfig(context: Context) {
        // Always reset routing to ensure fresh rules
        MmkvManager.encodeRoutingRulesets(null)
        // Keep VPN ready after reboot without opening the app manually.
        MmkvManager.encodeStartOnBoot(true)
        MmkvManager.encodeSettings(AppConfig.PREF_VPN_MTU, VPN_TUN_MTU.toString())

        saveServerConfig()
        saveRoutingRulesets()
        savePerAppProxySettings(context)
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
            network = "tcp",
            security = "reality",
            sni = REALITY_SNI,
            fingerPrint = REALITY_FINGERPRINT,
            publicKey = REALITY_PUBLIC_KEY,
            shortId = REALITY_SHORT_ID,
            spiderX = ""
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
     * 2. Telegram domains → proxy (раньше IP: медиа/CDN чаще матчится по hostname/sniffing)
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
}
