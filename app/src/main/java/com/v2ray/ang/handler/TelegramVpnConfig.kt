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
    // Android: port 8443, network=xhttp, flow="" — xhttp несовместим с XTLS Vision, flow должен быть пустым
    // iOS:     port 443,  network=tcp,   flow=xtls-rprx-vision — TCP+Reality, полный XTLS
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
    /**
     * MTU для TUN/HEV: 1280 — IPv6 minimum PMTU (RFC 8200), на практике самый
     * «безболезненный» размер под LTE/Wi‑Fi + VPN + SOCKS + длинный hop в Европу:
     * меньше шансов на фрагментацию/ретраи TCP, чем 1500.
     */
    const val VPN_TUN_MTU = 1280

    /**
     * Apps that actively detect VPN via NetworkCapabilities.TRANSPORT_VPN.
     * Source: Meduza/RBC investigation, April 2026 — 22 of Russia's top 30 apps.
     * These are excluded from the VPN interface entirely in SMART_RUSSIA mode
     * so they see no VPN and stop showing "disable VPN" warnings.
     * Traffic still goes direct because their domains/IPs are in the direct ruleset.
     */
    val VPN_SENSITIVE_PACKAGES = setOf(
        // ── Banks ────────────────────────────────────────────────────────────────
        "ru.sberbankmobile",                    // Сбербанк Онлайн
        "ru.sberbank.sbbol",                    // СберБизнес
        "com.idamob.tinkoff.android",           // Тинькофф / Т-Банк
        "ru.vtb24.mobilebanking.android",       // ВТБ Онлайн
        "ru.alfabank.mobile.android",           // Альфа-Банк
        "ru.raiffeisennews",                    // Райффайзен
        "ru.gazprombank.android",               // Газпромбанк
        "ru.rosbank.android",                   // Росбанк
        "ru.openbank.app",                      // Открытие
        "ru.sovcombank.sovcombank",             // Совкомбанк
        "ru.pochtabank.app",                    // Почта Банк
        "ru.mkb.android",                       // МКБ
        "ru.uralsib.mobile",                    // Уралсиб
        "ru.psbank.mobile",                     // ПСБ
        "ru.rshb.mobilebank",                   // РСХБ
        "ru.homecredit.mobilebank",             // Хоум Кредит
        "ru.rencredit.mobile",                  // Ренессанс Кредит
        // ── Marketplaces ─────────────────────────────────────────────────────────
        "ru.ozon.app.android",                  // Ozon
        "com.wildberries.ru",                   // Wildberries
        "ru.sbermegamarket.app",                // МегаМаркет (SberMegaMarket)
        "ru.samokat.app",                       // Самокат
        "ru.avito.android",                     // Авито
        "ru.vkusvill.android",                  // ВкусВилл
        // ── Yandex ───────────────────────────────────────────────────────────────
        "com.yandex.browser",                   // Яндекс Браузер
        "ru.yandex.yandexmaps",                 // Яндекс Карты
        "com.yandex.launcher",                  // Яндекс Лончер
        "ru.yandex.market",                     // Яндекс Маркет
        "ru.yandex.taxi",                       // Яндекс Такси
        "ru.yandex.music",                      // Яндекс Музыка
        "com.yandex.mail",                      // Яндекс Почта
        "ru.yandex.disk",                       // Яндекс Диск
        // ── VK ───────────────────────────────────────────────────────────────────
        "com.vkontakte.android",                // ВКонтакте
        "ru.ok.android",                        // Одноклассники
        "ru.mail.mailapp",                      // Почта Mail.ru / Max
        "com.vk.im",                            // VK Мессенджер / Max
        // ── Other ────────────────────────────────────────────────────────────────
        "ru.dublgis.dgismobile",                // 2ГИС
        "ru.kinopoisk.android",                 // Кинопоиск
        // ── Gosuslugi & Government ───────────────────────────────────────────────
        "ru.gosuslugi.mobile",                  // Госуслуги (основное)
        "ru.gosuslugi.android",                 // Госуслуги (альтернативное)
        "ru.gosuslugi",                         // Госуслуги (короткое)
        "ru.gosuslugi.culture",                 // Госуслуги Культура
        "ru.gosuslugi.goskey",                  // Госключ
        "ru.nalog.lkfl",                        // Налоги ФЛ
        "ru.nalog.lkul",                        // Налоги ЮЛ
        "ru.nalog.lkip",                        // Налоги ИП
        "ru.mos.app",                           // Mos.ru
        "ru.pfr.pfronline",                     // СФР (ПФР)
        "ru.sfr.sfronline",                     // СФР (новое)
        "ru.epgu.mobile",                       // ЕПГУ
        "ru.esia.android",                      // ЕСИА
        "ru.digital.gov",                       // Цифровое правительство
        "ru.mintsifry.mobile",                  // Минцифры
        "ru.rostel"                             // Ростелеком (ЕСИА, Госуслуги)
    )

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
                // Sniffing not needed in per-app proxy mode — we already know it's Telegram.
                // Disabling it removes per-connection overhead and speeds up initial connection.
                MmkvManager.encodeSettings(AppConfig.PREF_SNIFFING_ENABLED, false)
                saveRoutingRulesets()
                savePerAppProxySettings(context)
            }
            RoutingMode.SMART_RUSSIA -> {
                // Sniffing required to detect domains for routing decisions
                MmkvManager.encodeSettings(AppConfig.PREF_SNIFFING_ENABLED, true)
                saveSmartRussiaRulesets()
                saveBypassAppSettings(context)
            }
            RoutingMode.ALL_TRAFFIC -> {
                MmkvManager.encodeSettings(AppConfig.PREF_SNIFFING_ENABLED, true)
                saveGlobalRoutingRulesets()
                disablePerAppProxy()
            }
        }
    }

    /**
     * Bypass mode for SMART_RUSSIA: exclude VPN-sensitive apps (banks, gosuslugi)
     * from the VPN interface entirely. They won't see TRANSPORT_VPN at all.
     * Their traffic still goes direct via routing rules.
     */
    private fun saveBypassAppSettings(context: Context) {
        val pm = context.packageManager
        val installedSensitiveApps = VPN_SENSITIVE_PACKAGES.filter { pkg ->
            try { pm.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
        }.toMutableSet()

        if (installedSensitiveApps.isEmpty()) {
            disablePerAppProxy()
            return
        }

        // Bypass mode: listed apps are excluded from VPN interface
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, true)
        MmkvManager.encodeSettings(AppConfig.PREF_BYPASS_APPS, true) // bypass = disallow list
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, installedSensitiveApps)
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
     * Telegram domains/IPs are placed first — matched immediately without DNS resolution,
     * so Telegram always goes through proxy fast regardless of sniffing.
     *
     * Black Russia uses CloudFront CDN — IPs are dynamic, so we rely on domain matching.
     * With IPIfNonMatch, xray resolves the domain to IP only when no domain rule matched,
     * so domain:blackrussia.com catches it before IP lookup.
     *
     * FCM/push notifications: Google domains must go through proxy (not in geoip:ru).
     * We explicitly proxy FCM so notifications work in smart mode.
     *
     * domainStrategy = IPIfNonMatch: resolves domain to IP only when no domain rule matched.
     */
    /**
     * Smart Russia mode: proxy everything, bypass RU domains/IPs + Black Russia.
     * Default = proxy, only RU services go direct.
     *
     * Explicit direct rules for RU banks, Gosuslugi, marketplaces — placed BEFORE
     * the generic geosite:category-ru rule so they are always matched even if
     * geosite data is outdated or missing.
     */
    private fun saveSmartRussiaRulesets() {
        val newRulesets = mutableListOf(
            RulesetItem(remarks = "Private IPs Direct", outboundTag = AppConfig.TAG_DIRECT, enabled = true,
                ip = listOf("geoip:private")),

            // ── RU Banks ──────────────────────────────────────────────────────────
            RulesetItem(remarks = "RU Banks Direct", outboundTag = AppConfig.TAG_DIRECT, enabled = true,
                domain = listOf(
                    // Sberbank
                    "domain:sberbank.ru", "domain:sber.ru", "domain:sberbankpremium.ru",
                    "domain:online.sberbank.ru",
                    // Tinkoff / T-Bank
                    "domain:tinkoff.ru", "domain:tbank.ru",
                    // VTB
                    "domain:vtb.ru", "domain:vtb24.ru",
                    // Alfa-Bank
                    "domain:alfabank.ru", "domain:alfa-bank.ru",
                    // Raiffeisen
                    "domain:raiffeisen.ru", "domain:raiffeisenbank.ru",
                    // Gazprombank
                    "domain:gazprombank.ru",
                    // Rosbank
                    "domain:rosbank.ru",
                    // Otkritie
                    "domain:open.ru", "domain:otkritie.ru",
                    // Sovcombank
                    "domain:sovcombank.ru",
                    // Pochta Bank
                    "domain:pochtabank.ru",
                    // MKB
                    "domain:mkb.ru",
                    // Uralsib
                    "domain:uralsib.ru",
                    // Promsvyazbank
                    "domain:psbank.ru",
                    // RSHB
                    "domain:rshb.ru",
                    // Absolut Bank
                    "domain:absolutbank.ru",
                    // Home Credit
                    "domain:homecredit.ru",
                    // Renaissance Credit
                    "domain:rencredit.ru",
                    // SBP / Faster Payments System
                    "domain:sbp.nspk.ru", "domain:nspk.ru",
                    // Mir card
                    "domain:mironline.ru", "domain:mirpay.ru",
                    // ЦБ РФ
                    "domain:cbr.ru"
                )),

            // ── Gosuslugi & Government ────────────────────────────────────────────
            RulesetItem(remarks = "Gosuslugi Direct", outboundTag = AppConfig.TAG_DIRECT, enabled = true,
                domain = listOf(
                    "domain:gosuslugi.ru",
                    "domain:esia.gosuslugi.ru",
                    "domain:lk.gosuslugi.ru",
                    "domain:beta.gosuslugi.ru",
                    "domain:pos.gosuslugi.ru",
                    "domain:nalog.ru",
                    "domain:lkfl.nalog.ru",
                    "domain:lkul.nalog.ru",
                    "domain:lkip.nalog.ru",
                    "domain:pfr.gov.ru",
                    "domain:sfr.gov.ru",          // СФР (бывший ПФР)
                    "domain:mos.ru",
                    "domain:pgu.mos.ru",
                    "domain:mos.ru",
                    "domain:goskey.ru",
                    "domain:epgu.gosuslugi.ru",
                    "domain:digital.gov.ru",
                    "domain:minsvyaz.ru",
                    "domain:rosreestr.gov.ru",
                    "domain:rosreestr.ru",
                    "domain:fss.ru",
                    "domain:fns.ru",
                    "domain:customs.gov.ru",
                    "domain:mvd.ru",
                    "domain:gibdd.ru",
                    "domain:mos-reg.ru",
                    "domain:gosuslugi66.ru"
                )),

            // ── Marketplaces ──────────────────────────────────────────────────────
            RulesetItem(remarks = "RU Marketplaces Direct", outboundTag = AppConfig.TAG_DIRECT, enabled = true,
                domain = listOf(
                    // Ozon
                    "domain:ozon.ru", "domain:ozon.travel", "domain:ozonbank.ru",
                    "domain:static.ozone.ru",
                    // Wildberries
                    "domain:wildberries.ru", "domain:wb.ru", "domain:wbstatic.net",
                    "domain:wbx-content.com",
                    // Yandex Market / Lavka / Eats
                    "domain:market.yandex.ru", "domain:beru.ru",
                    "domain:lavka.yandex.ru", "domain:eda.yandex.ru",
                    // AliExpress Russia
                    "domain:aliexpress.ru",
                    // Avito
                    "domain:avito.ru",
                    // CDEK
                    "domain:cdek.ru",
                    // Boxberry
                    "domain:boxberry.ru",
                    // Lamoda
                    "domain:lamoda.ru",
                    // Leroy Merlin RU
                    "domain:leroymerlin.ru",
                    // DNS shop
                    "domain:dns-shop.ru",
                    // Citilink
                    "domain:citilink.ru",
                    // Eldorado
                    "domain:eldorado.ru",
                    // M.Video
                    "domain:mvideo.ru",
                    // Perekrestok / X5
                    "domain:perekrestok.ru", "domain:x5.ru",
                    // Magnit
                    "domain:magnit.ru",
                    // Sbermarket / Samokat
                    "domain:sbermarket.ru", "domain:samokat.ru",
                    // Detsky Mir
                    "domain:detmir.ru",
                    // Sportmaster
                    "domain:sportmaster.ru"
                )),

            // ── Black Russia ──────────────────────────────────────────────────────
            RulesetItem(remarks = "Black Russia Domains Direct", outboundTag = AppConfig.TAG_DIRECT, enabled = true,
                domain = listOf(
                    "domain:blackrussia.com",
                    "domain:blackhub.games",
                    "domain:brgame.ru",
                    "domain:launcher.brgame"
                )),
            RulesetItem(remarks = "Black Russia IPs Direct", outboundTag = AppConfig.TAG_DIRECT, enabled = true,
                ip = listOf("54.243.117.197", "13.223.25.84", "87.251.65.8")),

            // ── Generic Russia (geosite / geoip) ──────────────────────────────────
            RulesetItem(remarks = "Russia domains Direct", outboundTag = AppConfig.TAG_DIRECT, enabled = true,
                domain = listOf("geosite:category-ru")),
            RulesetItem(remarks = "Russia IPs Direct", outboundTag = AppConfig.TAG_DIRECT, enabled = true,
                ip = listOf("geoip:ru")),

            // ── Everything else → proxy ───────────────────────────────────────────
            RulesetItem(remarks = "All other Proxy", outboundTag = AppConfig.TAG_PROXY, enabled = true,
                port = "0-65535")
        )
        MmkvManager.encodeRoutingRulesets(newRulesets)
        MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_DOMAIN_STRATEGY, "IPOnDemand")
    }

    /**
     * Disable per-app proxy so all apps go through VPN.
     */
    private fun disablePerAppProxy() {
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY, false)
    }
}
