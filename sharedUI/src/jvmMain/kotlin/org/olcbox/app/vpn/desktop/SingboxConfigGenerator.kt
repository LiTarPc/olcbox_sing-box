package org.olcbox.app.vpn.desktop

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

object SingboxConfigGenerator {

    fun generate(
        socksPort: Int = 10808,
        bypassRuEnabled: Boolean = true,
        extraBypassDomains: List<String> = emptyList(),
        extraBypassCidrs: List<String> = emptyList(),
        logLevel: String = "warning",
        logPath: String = "olcbox-singbox.log",
    ): String {
        val config = buildJsonObject {

            // --- LOG ---
            put("log", buildJsonObject {
                put("level", logLevel)
                put("output", logPath)
                put("timestamp", true)
            })

            // --- DNS ---
            put("dns", buildJsonObject {
                put("servers", buildJsonArray {
                    // Удалённый DNS через туннель (для всего нероссийского)
                    add(buildJsonObject {
                        put("tag", "dns-remote")
                        put("type", "udp")
                        put("server", "1.1.1.1")
                        put("detour", "proxy-out")
                    })
                    // Прямой DNS для .ru (Яндекс DNS)
                    add(buildJsonObject {
                        put("tag", "dns-direct")
                        put("type", "udp")
                        put("server", "77.88.8.8")
                    })

                    // Fake-IP сервер — возвращает виртуальные IP из 198.18.0.0/15
                    add(buildJsonObject {
                        put("tag", "dns-fakeip")
                        put("type", "fakeip")
                        put("inet4_range", "198.18.0.0/15")
                    })
                })

                put("rules", buildJsonArray {
                    // .ru / .рф / .su — резолвить напрямую, без туннеля
                    if (bypassRuEnabled) {
                        add(buildJsonObject {
                            put("domain_suffix", buildJsonArray {
                                add(".ru"); add(".рф"); add(".su")
                            })
                            put("server", "dns-direct")
                        })
                    }

                    // Дополнительные домены пользователя — тоже напрямую
                    val userDomains = extraBypassDomains.filter { it.isNotBlank() && !it.contains("/") }
                    if (userDomains.isNotEmpty()) {
                        add(buildJsonObject {
                            put("domain", buildJsonArray { userDomains.forEach { add(it) } })
                            put("server", "dns-direct")
                        })
                    }

                    // Все остальные A/AAAA запросы → fake-ip
                    add(buildJsonObject {
                        put("query_type", buildJsonArray { add("A"); add("AAAA") })
                        put("server", "dns-fakeip")
                    })
                })

                put("independent_cache", true)
                put("strategy", "ipv4_only")
            })

            // --- INBOUNDS ---
            put("inbounds", buildJsonArray {
                add(buildJsonObject {
                    put("type", "tun")
                    put("tag", "tun-in")
                    // Адрес TUN интерфейса. 198.18.0.0/15 = диапазон fake-ip.
                    // sing-box сам назначает адрес и поднимает маршруты.
                    put("interface_name", "Olcbox")
                    put("address", buildJsonArray {
                        add("198.18.0.1/15")
                    })
                    put("mtu", 1500)
                    // auto_route = sing-box сам добавляет default route через TUN
                    // и исключает loopback / link-local / private адреса.
                    put("auto_route", true)
                    // strict_route на Windows блокирует DNS утечки через WFP.
                    put("strict_route", true)
                    // mixed stack = gVisor для UDP, system для TCP (лучшая совместимость)
                    put("stack", "mixed")
                })

            })

            // --- OUTBOUNDS ---
            put("outbounds", buildJsonArray {
                // Основной: SOCKS5 → olcrtc cnc
                add(buildJsonObject {
                    put("type", "socks")
                    put("tag", "proxy-out")
                    put("server", "127.0.0.1")
                    put("server_port", socksPort)
                    put("version", "5")
                    // udp_over_tcp: оборачивает UDP в TCP stream.
                    // Необходимо т.к. olcrtc поддерживает только TCP.
                    put("udp_over_tcp", buildJsonObject {
                        put("enabled", true)
                        put("version", 2)
                    })
                })
                // Прямое соединение (для bypass)
                add(buildJsonObject {
                    put("type", "direct")
                    put("tag", "direct")
                })
            })

            // --- ROUTE ---
            put("route", buildJsonObject {
                put("default_domain_resolver", "dns-direct")
                put("rules", buildJsonArray {

                    // 1. Сниффинг протоколов (должен быть первым)
                    add(buildJsonObject { put("action", "sniff") })

                    // 2. DNS трафик → sing-box DNS модуль
                    add(buildJsonObject {
                        put("protocol", "dns")
                        put("action", "hijack-dns")
                    })

                    // 3. Локальные / RFC1918 → прямо (иначе локалка сломается)
                    add(buildJsonObject {
                        put("ip_is_private", true)
                        put("outbound", "direct")
                    })

                    // 4. .ru сегмент → прямо
                    if (bypassRuEnabled) {
                        add(buildJsonObject {
                            put("domain_suffix", buildJsonArray {
                                add(".ru"); add(".рф"); add(".su")
                            })
                            put("outbound", "direct")
                        })
                        // geoip-ru — российские IP блоки из базы SagerNet
                        add(buildJsonObject {
                            put("rule_set", "geoip-ru")
                            put("outbound", "direct")
                        })
                    }

                    // 5. Дополнительные домены пользователя → прямо
                    val userDomains = extraBypassDomains.filter { it.isNotBlank() && !it.contains("/") }
                    if (userDomains.isNotEmpty()) {
                        add(buildJsonObject {
                            put("domain", buildJsonArray { userDomains.forEach { add(it) } })
                            put("outbound", "direct")
                        })
                    }

                    // 6. Дополнительные CIDR пользователя → прямо
                    val userCidrs = extraBypassCidrs.filter { it.isNotBlank() }
                    if (userCidrs.isNotEmpty()) {
                        add(buildJsonObject {
                            put("ip_cidr", buildJsonArray { userCidrs.forEach { add(it) } })
                            put("outbound", "direct")
                        })
                    }
                })

                // rule_set: sing-box скачает и закеширует автоматически
                if (bypassRuEnabled) {
                    put("rule_set", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "remote")
                            put("tag", "geoip-ru")
                            put("format", "binary")
                            put("url", "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-ru.srs")
                            put("download_detour", "direct")
                            put("update_interval", "168h0m0s") // раз в неделю
                        })
                    })
                }

                // Всё что не попало в правила — через туннель
                put("final", "proxy-out")
                // sing-box автоматически определит дефолтный интерфейс для direct
                put("auto_detect_interface", true)
            })
        }

        return Json { prettyPrint = true }.encodeToString(config)
    }
}
