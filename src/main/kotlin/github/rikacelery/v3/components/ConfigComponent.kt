package github.rikacelery.v3.components

import github.rikacelery.v3.core.Actor
import github.rikacelery.v3.core.EventBus
import github.rikacelery.v3.data.SystemConfig
import github.rikacelery.v3.events.*
import github.rikacelery.v3.storage.MongoStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

sealed interface ConfigMsg
data class HandleConfigQuery(val env: CommandEnvelope) : ConfigMsg

class ConfigComponent(
    private val config: SystemConfig,
    eventBus: EventBus,
    parentScope: CoroutineScope,
    private val mongo: MongoStore = MongoStore(null)
) : Actor<ConfigMsg>("ConfigComponent", eventBus, parentScope) {

    private val configFile = File(config.configPath)
    private var persistedStreamAuthKey: String = config.streamAuthKey
    private val persistedDecryptKeys = config.decryptKeys.toMutableMap()

    private fun loadConfig() {
        if (!configFile.exists()) return
        try {
            val json = Json.parseToJsonElement(configFile.readText()).jsonObject
            json["streamAuthKey"]?.jsonPrimitive?.content?.let { persistedStreamAuthKey = it }
            json["decryptKeys"]?.jsonObject?.forEach { (k, v) ->
                persistedDecryptKeys[k] = v.jsonPrimitive.content
            }
            logger.info("Loaded config from ${config.configPath}")
        } catch (e: Exception) {
            logger.warn("Failed to load config.json: ${e.message}")
        }
    }

    private fun saveConfig() {
        try {
            val json = Json { prettyPrint = true }
            configFile.writeText(json.encodeToString(JsonElement.serializer(),
                buildJsonObject {
                    put("streamAuthKey", persistedStreamAuthKey)
                    put("decryptKeys", buildJsonObject {
                        persistedDecryptKeys.forEach { (k, v) -> put(k, v) }
                    })
                }
            ))
            logger.info("Saved config to ${config.configPath}")
        } catch (e: Exception) {
            logger.warn("Failed to save config.json: ${e.message}")
        }
    }

    fun replaceAll(streamAuthKey: String, decryptKeys: Map<String, String>) {
        persistedStreamAuthKey = streamAuthKey
        persistedDecryptKeys.clear()
        persistedDecryptKeys.putAll(decryptKeys)
    }

    fun persistedAuthKey() = persistedStreamAuthKey
    fun persistedDecryptKeys() = persistedDecryptKeys.toMap()

    override suspend fun onStart(scope: CoroutineScope) {
        subscribe<CommandEnvelope>(CommandEnvelope::class)
        subscribe<PersistConfig>(PersistConfig::class)
        loadConfig() // refresh from disk in case Main.kt already loaded
        loadFromMongo() // MongoDB overrides file-based config
        scope.launch { saveConfig() } // ensure config file exists
    }

    override suspend fun wrapEvent(event: Any): ConfigMsg? = when (event) {
        is CommandEnvelope -> HandleConfigQuery(event)
        is PersistConfig -> {
            saveConfig()
            scope.launch { saveConfigToMongo() }
            null
        }

        else -> null
    }

    override suspend fun handle(msg: ConfigMsg) = when (msg) {
        is HandleConfigQuery -> handleQuery(msg.env)
    }

    private suspend fun loadFromMongo() {
        if (!mongo.isConnected()) return
        try {
            mongo.loadConfig("streamAuthKey")?.let { persistedStreamAuthKey = it }
            val raw = mongo.loadConfig("decryptKeys")
            if (raw != null) {
                try {
                    val obj = Json.parseToJsonElement(raw).jsonObject
                    persistedDecryptKeys.clear()
                    obj.forEach { (k, v) -> persistedDecryptKeys[k] = v.jsonPrimitive.content }
                } catch (_: Exception) { }
            }
            logger.info("Loaded config from MongoDB")
        } catch (e: Exception) {
            logger.warn("Failed to load config from MongoDB: ${e.message}")
        }
    }

    private suspend fun saveConfigToMongo() {
        if (!mongo.isConnected()) return
        try {
            mongo.saveConfig("streamAuthKey", persistedStreamAuthKey)
            mongo.saveConfig("decryptKeys", Json.encodeToString(JsonElement.serializer(),
                buildJsonObject { persistedDecryptKeys.forEach { (k, v) -> put(k, v) } }
            ))
        } catch (e: Exception) {
            logger.warn("Failed to save config to MongoDB: ${e.message}")
        }
    }

    private suspend fun handleQuery(env: CommandEnvelope) {
        val ack = when (env.command) {
            is GetDecryptKey -> ConfigResponse(persistedDecryptKeys[env.command.keyName])
            else -> return
        }
        eventBus.publish(CommandAck(env.id, ack))
    }
}
