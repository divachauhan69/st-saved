package github.rikacelery.v3.postprocessors

import java.io.File

class TelegramUploadProcessor(
    private val channelId: String,
    private val onUpload: suspend (File, String) -> Unit
) : Processor() {
    override suspend fun process(input: File, ctx: ProcessorCtx): List<File> {
        if (!input.exists() || input.length() == 0L) return listOf(input)
        val caption = buildString {
            appendLine("🎬 ${ctx.roomName} (#${ctx.roomId})")
            appendLine("⏱ ${formatDuration(ctx.durationMs)}")
            appendLine("📺 ${ctx.quality}")
        }
        try {
            onUpload(input, caption.trimEnd())
        } catch (e: Exception) {
            logger.error("Telegram upload failed: ${e.message}")
        }
        return listOf(input)
    }

    private fun formatDuration(ms: Long): String {
        val h = ms / 3600_000
        val m = (ms % 3600_000) / 60_000
        val s = (ms % 60_000) / 1000
        return if (h > 0) "${h}h${m}m${s}s" else if (m > 0) "${m}m${s}s" else "${s}s"
    }
}
