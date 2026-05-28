package github.rikacelery.v3.data

import java.io.File

data class SystemConfig(
    val outputDir: File,
    val tmpDir: File,
    val port: Int,
    val proxy: String?,
    val decryptKeys: Map<String, String>,
    val streamAuthKey: String,
    val authToken: String,
    val platformHost: String,
    val listConfPath: String = "list.conf",
    val configPath: String = "xhrec.json",
    val telegramBotToken: String = System.getenv("TELEGRAM_BOT_TOKEN") ?: "",
    val telegramChannelId: String = System.getenv("TELEGRAM_CHANNEL_ID") ?: "",
    val telegramAllowedUsers: List<Long> = (System.getenv("TELEGRAM_ALLOWED_USERS") ?: "")
        .split(",").filter { it.isNotBlank() }.mapNotNull { it.trim().toLongOrNull() }
)
