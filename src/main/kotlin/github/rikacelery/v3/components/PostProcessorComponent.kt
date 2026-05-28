package github.rikacelery.v3.components

import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.core.RequestBus
import github.rikacelery.v3.events.*
import github.rikacelery.v3.postprocessors.Processor
import github.rikacelery.v3.postprocessors.ProcessorCtx
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.time.Instant
import java.util.*

sealed interface PostProcessorMsg
data class OnProcessorEvent(val event: Any) : PostProcessorMsg

class PostProcessorComponent(
    private val requestBus: RequestBus,
    eventBus: EventBus,
    parentScope: CoroutineScope,
    maxConcurrency: Int = 4
) : Actor<PostProcessorMsg>("PostProcessorComponent", eventBus, parentScope) {

    private var processors: List<Processor> = emptyList()
    private val semaphore = Semaphore(maxConcurrency)
    val jobs = Hashtable<String, Job>()

    fun setProcessors(procs: List<Processor>) {
        processors = procs
    }

    override suspend fun onStart(scope: CoroutineScope) {
        subscribe<FileReady>(FileReady::class)
    }

    override suspend fun wrapEvent(event: Any): PostProcessorMsg? = when (event) {
        is FileReady -> OnProcessorEvent(event)
        else -> null
    }

    override suspend fun handle(msg: PostProcessorMsg) {
        when (msg) {
            is OnProcessorEvent -> when (val e = msg.event) {
                is FileReady -> withContext(NonCancellable) {
                    val key = "${msg.event.file}"
                    jobs.set(key, scope.launch {
                        semaphore.withPermit {
                            try {
                                logger.info("processing {}", e.file)
                                processFile(e)
                                logger.info("process ok {}", e.file)
                            } finally {
                                jobs.remove(key)

                            }
                        }
                    })
                }

                else -> {}
            }
        }
    }

    private suspend fun processFile(event: FileReady) {
        var files = listOf(event.file)
        val roomName = try {
            requestBus.request<RoomNameResponse>(GetRoomName(event.roomId)).name
        } catch (_: Exception) { "unknown" }
        val quality = try {
            requestBus.request<RoomConfigResponse>(GetRoomConfig(event.roomId)).quality
        } catch (_: Exception) { "" }
        val endTime = System.currentTimeMillis()
        val startTime = endTime - (event.file.lastModified().let { if (it > 0) endTime - it else 0L })
        for (processor in processors) {
            files = files.flatMap { f ->
                try {
                    val ctx = ProcessorCtx(
                        roomId = event.roomId, roomName = roomName,
                        startTime = startTime, endTime = endTime,
                        durationMs = endTime - startTime, quality = quality
                    )
                    processor.process(f, ctx)
                } catch (e: Exception) {
                    logger.error("Processor error: ${e.message}")
                    listOf(f)
                }
            }
        }
        eventBus.publish(FileProcessed(event.roomId, files.lastOrNull() ?: event.file))
    }
}
