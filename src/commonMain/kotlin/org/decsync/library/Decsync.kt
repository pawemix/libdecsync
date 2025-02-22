/**
 * libdecsync - Decsync.kt
 *
 * Copyright (C) 2019 Aldo Gunsing
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; if not, see <http://www.gnu.org/licenses/>.
 */

package org.decsync.library

import kotlinx.serialization.json.*
import kotlin.native.concurrent.SharedImmutable
import kotlin.random.Random

@SharedImmutable
val json = Json.Default

enum class DecsyncVersion {
    V1, V2;

    fun toInt(): Int =
            when (this) {
                V1 -> 1
                V2 -> 2
            }

    override fun toString(): String =
            when (this) {
                V1 -> "v1"
                V2 -> "v2"
            }

    companion object {
        fun fromInt(input: Int): DecsyncVersion? =
                when (input) {
                    1 -> V1
                    2 -> V2
                    else -> null
                }
    }
}

@SharedImmutable
val SUPPORTED_VERSION = DecsyncVersion.V2
@SharedImmutable
val DEFAULT_VERSION = DecsyncVersion.V2

expect sealed class DecsyncException : Exception
expect fun getInvalidInfoException(e: Exception): DecsyncException
expect fun getUnsupportedVersionException(requiredVersion: Int, supportedVersion: Int): DecsyncException

/**
 * The `DecSync` class represents an interface to synchronized key-value mappings stored on the file
 * system.
 *
 * The mappings can be synchronized by synchronizing the directory [decsyncDir]. The stored mappings
 * are stored in a conflict-free way. When the same keys are updated independently, the most recent
 * value is taken. This should not cause problems when the individual values contain as little
 * information as possible.
 *
 * Every entry consists of a path, a key and a value. The path is a list of strings which contains
 * the location to the used mapping. This can make interacting with the data easier. It is also used
 * to construct a path in the file system. All characters are allowed in the path. However, other
 * limitations of the file system may apply. For example, there may be a maximum length or the file
 * system may be case insensitive.
 *
 * To update an entry, use the method [setEntry]. When multiple keys in the same path are updated
 * simultaneous, it is encouraged to use the more efficient methods [setEntriesForPath] and
 * [setEntries].
 *
 * To get notified about updated entries, use the method [executeAllNewEntries] to get all updated
 * entries and call the corresponding listeners. Listeners can be added by the method [addListener].
 *
 * Sometimes, updates cannot be execute immediately. For example, if the name of a category is
 * updated when the category does not exist yet, the name cannot be changed. In such cases, the
 * updates have to be executed retroactively. In the example, the update can be executed when the
 * category is created. For such cases, use the method [executeStoredEntry], [executeStoredEntries]
 * or [executeStoredEntriesForPath].
 *
 * Finally, to initialize the stored entries to the most recent values, use the method
 * [initStoredEntries]. This method is almost exclusively used when the application is installed. It
 * is almost always followed by a call to [executeStoredEntry] or similar.
 *
 * @param T the type of the extra data passed to the [listeners].
 * @param decsyncDir the directory in which the synchronized DecSync files are stored. For the
 * default location, use [getDefaultDecsyncDir].
 * @param syncType the type of data to sync. For example, "rss", "contacts" or "calendars".
 * @param collection an optional collection identifier when multiple instances of the [syncType] are
 * supported. For example, this is the case for "contacts" and "calendars", but not for "rss".
 * @property ownAppId the unique appId corresponding to the stored data by the application. There
 * must not be two simultaneous instances with the same appId. However, if an application is
 * reinstalled, it may reuse its old appId. In that case, it has to call [initStoredEntries] and
 * [executeStoredEntry] or similar. Even if the old appId is not reused, it is still recommended to
 * call these. For the default appId, use [getAppId].
 * @throws DecsyncException if a DecSync configuration error occurred.
 */
@ExperimentalStdlibApi
class Decsync<T> internal constructor(
        private val decsyncDir: NativeFile,
        private val localDir: DecsyncFile,
        private val syncType: String,
        private val collection: String?,
        private val ownAppId: String
) {
    private val localInfo: MutableMap<String, JsonElement> = getLocalInfo()
    private fun getLocalInfo(): MutableMap<String, JsonElement> {
        val text = localDir.child("info").readText() ?: return mutableMapOf()
        return json.parseToJsonElement(text).jsonObject.toMutableMap()
    }
    private fun writeLocalInfo() {
        val text = JsonObject(localInfo).toString()
        localDir.child("info").writeText(text)
    }
    private var version: DecsyncVersion
    private var instance: DecsyncInst<T>
    var isInInit = false

    init {
        val decsyncInfo = getDecsyncInfoOrDefault(decsyncDir)
        val decsyncVersion = getDecsyncVersion(decsyncInfo)!! // Also checks whether we support the main DecSync version
        val localVersion = getDecsyncVersion(localInfo) // Caches getLatestOwnDecsyncVersion
        if (localVersion != null) {
            version = localVersion
        } else {
            version = getLatestDecsyncVersion(decsyncDir, syncType, collection, ownAppId) ?:
                    getLatestDecsyncVersion(decsyncDir, syncType, collection) ?:
                    decsyncVersion
            localInfo["version"] = JsonPrimitive(version.toInt())
            writeLocalInfo()
        }
        instance = getInstance(version)
    }

    private fun <V> getInstance(decsyncVersion: DecsyncVersion): DecsyncInst<V> {
        return when (decsyncVersion) {
            DecsyncVersion.V1 -> DecsyncV1(decsyncDir, localDir, syncType, collection, ownAppId)
            DecsyncVersion.V2 -> DecsyncV2(decsyncDir, localDir, syncType, collection, ownAppId)
        }
    }

    /**
     * Adds a listener, which describes the actions to execute on some updated entries. When an
     * entry is updated, the function [onEntryUpdate] is called on the listener whose [subpath]
     * matches. It matches when the given subpath is a prefix of the path of the entry.
     */
    fun addListener(subpath: List<String>, onEntryUpdate: (path: List<String>, entry: Entry, extra: T) -> Unit) =
            instance.addListener(subpath) { path, entry, extra ->
                onEntryUpdate(path, entry, extra)
                true
            }

    /**
     * Adds a listener, which describes the actions to execute on some updated entries. When an
     * entry is updated, the function [onEntryUpdate] is called on the listener whose [subpath]
     * matches. It matches when the given subpath is a prefix of the path of the entry.
     *
     * @return Boolean indicating whether the call succeeded. If false, the entry will be called
     * again later. If an entry is not supported it should return true, as retrying will not help.
     */
    fun addListenerWithSuccess(subpath: List<String>, onEntryUpdate: (path: List<String>, entry: Entry, extra: T) -> Boolean) =
            instance.addListener(subpath, onEntryUpdate)

    fun addMultiListener(subpath: List<String>, onEntriesUpdate: (path: List<String>, entries: List<Entry>, extra: T) -> Unit) =
            instance.addMultiListener(subpath) { path, entries, extra ->
                onEntriesUpdate(path, entries, extra)
                true
            }

    fun addMultiListenerWithSuccess(subpath: List<String>, onEntriesUpdate: (path: List<String>, entries: List<Entry>, extra: T) -> Boolean) =
            instance.addMultiListener(subpath, onEntriesUpdate)

    internal class OnEntriesUpdateListener<T>(
            val subpath: List<String>,
            val callback: (path: List<String>, entries: MutableList<Entry>, extra: T) -> Boolean
    ) {
        fun matchesPath(path: List<String>): Boolean = path.take(subpath.size) == subpath
        fun onEntriesUpdate(path: List<String>, entries: MutableList<Entry>, extra: T): Boolean {
            val convertedPath = path.drop(subpath.size)
            return callback(convertedPath, entries, extra)
        }
    }

    /**
     * Represents an [Entry] with its path.
     */
    data class EntryWithPath(val path: List<String>, val entry: Entry) {
        /**
         * Convenience constructors for nicer syntax.
         */
        constructor(path: List<String>, datetime: String, key: JsonElement, value: JsonElement) : this(path, Entry(datetime, key, value))
        constructor(path: List<String>, key: JsonElement, value: JsonElement) : this(path, Entry(key, value))

        internal fun toJson(): JsonElement {
            return buildJsonArray {
                addJsonArray {
                    path.forEach { add(it) }
                }
                add(entry.datetime)
                add(entry.key)
                add(entry.value)
            }
        }

        override fun toString(): String = toJson().toString()

        companion object {
            internal fun fromLine(line: String): EntryWithPath? =
                    try {
                        val array = json.parseToJsonElement(line).jsonArray
                        if (array.size != 4) throw Exception("Size of array not 4")
                        val path = array[0].jsonArray.map { it.jsonPrimitive.content }
                        val datetime = array[1].jsonPrimitive.content
                        val key = array[2]
                        val value = array[3]
                        EntryWithPath(path, datetime, key, value)
                    } catch (e: Exception) {
                        Log.e("Invalid entry: $line")
                        Log.e(e.message!!)
                        null
                    }
        }
    }

    /**
     * Represents a key/value pair stored by DecSync. Additionally, it has a datetime property
     * indicating the most recent update. It does not store its path, see [EntryWithPath].
     */
    data class Entry(val datetime: String, val key: JsonElement, val value: JsonElement) {
        /**
         * Convenience constructor which sets the [datetime] property to the current datetime.
         */
        constructor(key: JsonElement, value: JsonElement) : this(currentDatetime(), key, value)

        internal fun toJson(): JsonElement {
            return buildJsonArray {
                add(datetime)
                add(key)
                add(value)
            }
        }

        override fun toString(): String = toJson().toString()

        companion object {
            internal fun fromLine(line: String): Entry? =
                    try {
                        val array = json.parseToJsonElement(line).jsonArray
                        if (array.size != 3) throw Exception("Size of array not 3")
                        val datetime = array[0].jsonPrimitive.content
                        val key = array[1]
                        val value = array[2]
                        Entry(datetime, key, value)
                    } catch (e: Exception) {
                        Log.e("Invalid entry: $line")
                        Log.e(e.message!!)
                        null
                    }
            }
    }

    /**
     * Represents the path and key stored by DecSync. It does not store its value, as it is unknown
     * when retrieving a stored entry.
     */
    data class StoredEntry(val path: List<String>, val key: JsonElement)

    /**
     * Associates the given [value] with the given [key] in the map corresponding to the given
     * [path]. This update is sent to synchronized devices.
     */
    fun setEntry(path: List<String>, key: JsonElement, value: JsonElement) {
        Log.d("Write 1 entry")
        instance.setEntry(path, key, value)
    }

    /**
     * Like [setEntry], but allows multiple entries to be set. This is more efficient if multiple
     * entries share the same path.
     *
     * @param entriesWithPath entries with path which are inserted.
     */
    fun setEntries(entriesWithPath: List<EntryWithPath>) {
        if (entriesWithPath.isEmpty()) return
        Log.d("Write ${entriesWithPath.size} entries")
        instance.setEntries(entriesWithPath)
    }

    /**
     * Like [setEntries], but only allows the entries to have the same path. Consequently, it can
     * be slightly more convenient since the path has to be specified just once.
     *
     * @param path path to the map in which the entries are inserted.
     * @param entries entries which are inserted.
     */
    fun setEntriesForPath(path: List<String>, entries: List<Entry>) {
        if (entries.isEmpty()) return
        Log.d("Write ${entries.size} entries")
        instance.setEntriesForPath(path, entries)
    }

    /**
     * Gets all updated entries and executes the corresponding actions.
     *
     * This method also performs some maintenance work like upgrading the own DecSync files to the
     * correct version. This can take occasionally more time, which makes it undesirable in some
     * situations. To disable it, set [disableMaintenance] to true. Note that it is still necessary
     * to periodically do the maintenance work in order to function properly.
     *
     * @param extra extra userdata passed to the [listeners].
     * @param disableMaintenance do not execute an upgrade in this call.
     */
    fun executeAllNewEntries(extra: T, disableMaintenance: Boolean = false) {
        if (isInInit) {
            Log.d("executeAllNewEntries called while in init")
            return
        }
        Log.d("Execute all new entries")
        instance.executeAllNewEntries(WithExtra(extra))

        if (!disableMaintenance) {
            val oldVersion = version
            val newVersion = getNewDecsyncVersion(decsyncDir)
            if (oldVersion < newVersion) {
                Log.d("Upgrading from DecSync version $oldVersion to $newVersion")
                decsyncDir.resetCache() // Make sure no duplicate directories are created for the new version
                val oldDecsync = getInstance<MutableList<EntryWithPath>>(oldVersion)
                val newDecsync = getInstance<T>(newVersion)
                newDecsync.listeners.addAll(instance.listeners)
                upgrade(oldDecsync, newDecsync)
                localInfo["version"] = JsonPrimitive(newVersion.toInt())
                writeLocalInfo()
                version = newVersion
                instance = newDecsync

                // Also get the updates in the new DecSync version
                instance.executeAllNewEntries(WithExtra(extra))
            }

            val lastActive = localInfo["last-active"]?.jsonPrimitive?.content
            val currentDate = currentDatetime().take(10) // YYYY-MM-DD
            if (lastActive == null || currentDate > lastActive) {
                localInfo["last-active"] = JsonPrimitive(currentDate)
                writeLocalInfo()
                setEntry(listOf("info"), JsonPrimitive("last-active-$ownAppId"), JsonPrimitive(currentDate))
            }

            val supportedVersion = localInfo["supported-version"]?.jsonPrimitive?.int?.let(DecsyncVersion::fromInt)
            if (supportedVersion == null || SUPPORTED_VERSION > supportedVersion) {
                val supportedVersionPrimitive = JsonPrimitive(SUPPORTED_VERSION.toInt())
                localInfo["supported-version"] = supportedVersionPrimitive
                writeLocalInfo()
                setEntry(listOf("info"), JsonPrimitive("supported-version-$ownAppId"), supportedVersionPrimitive)
            }
        }
    }

    /**
     * Gets the stored entry in [path] with key [key] and executes the corresponding action, passing
     * extra data [extra] to the listener.
     */
    fun executeStoredEntry(path: List<String>, key: JsonElement, extra: T): Boolean {
        Log.d("Execute 1 stored entry")
        return instance.executeStoredEntry(path, key, extra)
    }

    /**
     * Like [executeStoredEntry], but allows multiple entries to be executed. This is more efficient
     * if multiple entries share the same path.
     *
     * @param storedEntries entries with path and key to be executed.
     * @param extra extra data passed to the listeners.
     */
    fun executeStoredEntries(storedEntries: List<StoredEntry>, extra: T): Boolean {
        if (storedEntries.isEmpty()) return true
        Log.d("Execute ${storedEntries.size} stored entries")
        return instance.executeStoredEntries(storedEntries, extra)
    }

    /**
     * Like [executeStoredEntries], but only allows the stored entries to have the same path.
     * Consequently, it can be slightly more convenient since the path has to be specified just
     * once.
     *
     * @param path exact path to the entries to execute.
     * @param extra extra data passed to the [listeners].
     * @param keys list of keys to execute. When null, all keys are executed.
     */
    fun executeStoredEntriesForPathExact(
            path: List<String>,
            extra: T,
            keys: List<JsonElement>? = null
    ): Boolean {
        Log.d("Execute stored entries of path $path")
        return instance.executeStoredEntriesForPathExact(path, extra, keys)
    }

    /**
     * Like [executeStoredEntriesForPathExact], but the path parameter is replaced by the [prefix]
     * parameter. This means that any path that is an extension of [prefix] is considered, but it
     * also makes the method less efficient.
     *
     * @param prefix path prefix to the entries to execute.
     * @param extra extra data passed to the [listeners].
     * @param keys list of keys to execute. When null, all keys are executed.
     */
    fun executeStoredEntriesForPathPrefix(
            prefix: List<String>,
            extra: T,
            keys: List<JsonElement>? = null
    ): Boolean {
        Log.d("Execute stored entries of prefix $prefix")
        return instance.executeStoredEntriesForPathPrefix(prefix, extra, keys)
    }

    /**
     * Initializes the stored entries. This method does not execute any actions. This is often
     * followed with a call to [executeStoredEntries].
     */
    fun initStoredEntries() {
        Log.d("Init stored entries")
        isInInit = true
        instance.executeAllNewEntries(NoExtra())
        isInInit = false
    }

    /**
     * Returns the most up-to-date appId. This is the appId which has stored the most recent entry.
     * In case of a tie, the appId corresponding to the current application is used, if possible.
     */
    @Deprecated(
            message = "Its use should not be necessary. Partially replaced by [getEntriesCount].",
            level = DeprecationLevel.WARNING
    )
    fun latestAppId(): String = instance.latestAppId()

    companion object {
        /**
         * Returns the most up-to-date values stored in the path `["info"]`, in the given DecSync
         * dir [decsyncDir], sync type [syncType] and collection [collection].
         */
        fun getStaticInfo(decsyncDir: NativeFile, syncType: String, collection: String?): Map<JsonElement, JsonElement> {
            Log.d("Get static info in $decsyncDir for syncType $syncType and collection $collection")
            val obj = getDecsyncInfoOrDefault(decsyncDir)
            val version = getDecsyncVersion(obj)!!

            val info = mutableMapOf<JsonElement, JsonElement>()
            val datetimes = mutableMapOf<JsonElement, String>()
            DecsyncV1.getStaticInfo(decsyncDir, syncType, collection, info, datetimes)
            if (version >= DecsyncVersion.V2) {
                DecsyncV2.getStaticInfo(decsyncDir, syncType, collection, info, datetimes)
            }
            return info
        }

        /**
         * Counts the number of non-null entries in the given DecSync dir [decsyncDir], sync type
         * [syncType] and collection [collection]. It only considers entries with the given path
         * [prefix].
         *
         * Mainly useful for debugging purposes for the user.
         */
        fun getEntriesCount(decsyncDir: NativeFile, syncType: String, collection: String?, prefix: List<String>): Int {
            Log.d("Getting the entries count in $decsyncDir for syncType $syncType and collection $collection")
            val latestVersion = getLatestDecsyncVersion(decsyncDir, syncType, collection) ?: return 0
            return when (latestVersion) {
                DecsyncVersion.V1 -> DecsyncV1.getEntriesCount(decsyncDir, syncType, collection, prefix)
                DecsyncVersion.V2 -> DecsyncV2.getEntriesCount(decsyncDir, syncType, collection, prefix)
            }
        }

        private fun getLatestDecsyncVersion(decsyncDir: NativeFile, syncType: String, collection: String?, appId: String? = null): DecsyncVersion? {
            val subdir = getDecsyncSubdir(decsyncDir, syncType, collection)

            // v2
            var dirV2 = subdir.child("v2")
            if (appId != null) {
                dirV2 = dirV2.child(appId)
            }
            if (dirV2.file.fileSystemNode is RealDirectory) return DecsyncVersion.V2

            // v1
            var dirV1 = subdir.child("stored-entries")
            if (appId != null) {
                dirV1 = dirV1.child(appId)
            }
            if (dirV1.file.fileSystemNode is RealDirectory) return DecsyncVersion.V1

            return null
        }

        private fun <T> upgrade(oldDecsync: DecsyncInst<MutableList<EntryWithPath>>, newDecsync: DecsyncInst<T>) {
            // Get old entries
            oldDecsync.addListener(emptyList()) { path, entry, entriesWithPath ->
                entriesWithPath.add(EntryWithPath(path, entry))
            }
            val entriesWithPath = mutableListOf<EntryWithPath>()
            oldDecsync.executeStoredEntriesForPathPrefix(emptyList(), entriesWithPath)

            // Set new entries
            newDecsync.setEntries(entriesWithPath)

            // Delete old entries
            async {
                oldDecsync.deleteOwnEntries()
            }
        }

        data class AppData(val appId: String, val lastActive: String?, val version: DecsyncVersion, val supportedVersion: Int?)

        fun getActiveApps(decsyncDir: NativeFile, syncType: String, collection: String?): Pair<DecsyncVersion, List<AppData>> {
            Log.d("Get active apps in $decsyncDir for syncType $syncType and collection $collection")
            val obj = getDecsyncInfoOrDefault(decsyncDir)
            val version = getDecsyncVersion(obj)!!
            val appDatas = mutableListOf<AppData>()

            // Version 1
            val appIdsV1 = DecsyncV1.getActiveApps(decsyncDir, syncType, collection)
            val infoV1 = DecsyncV1.getStaticInfo(decsyncDir, syncType, collection)
            for (appId in appIdsV1) {
                val lastActive = infoV1[JsonPrimitive("last-active-$appId")]?.jsonPrimitive?.content
                val supportedVersion = infoV1[JsonPrimitive("supported-version-$appId")]?.jsonPrimitive?.int
                appDatas += AppData(appId, lastActive, DecsyncVersion.V1, supportedVersion)
            }

            // Version 2
            if (version >= DecsyncVersion.V2) {
                val appIdsV2 = DecsyncV2.getActiveApps(decsyncDir, syncType, collection)
                val infoV2 = DecsyncV2.getStaticInfo(decsyncDir, syncType, collection)
                for (appId in appIdsV2) {
                    val lastActive = infoV2[JsonPrimitive("last-active-$appId")]?.jsonPrimitive?.content
                    val supportedVersion = infoV2[JsonPrimitive("supported-version-$appId")]?.jsonPrimitive?.int
                    appDatas += AppData(appId, lastActive, DecsyncVersion.V2, supportedVersion)
                }
            }

            appDatas.sortWith(
                    compareBy(AppData::lastActive)
                            .thenBy(AppData::version)
                            .thenBy(AppData::appId)
            )
            return Pair(version, appDatas)
        }

        fun deleteAppData(decsyncDir: NativeFile, syncType: String, collection: String?,
                          appId: String, version: DecsyncVersion, currentVersion: DecsyncVersion) {
            when (version) {
                DecsyncVersion.V1 -> {
                    val includeNewEntries = currentVersion > DecsyncVersion.V1
                    DecsyncV1.deleteApp(decsyncDir, syncType, collection, appId, includeNewEntries)
                }
                DecsyncVersion.V2 -> {
                    DecsyncV2.deleteApp(decsyncDir, syncType, collection, appId)
                }
            }
        }

        fun permDeleteCollection(decsyncDir: NativeFile, syncType: String, collection: String?) {
            val dir = getDecsyncSubdir(decsyncDir, syncType, collection)
            dir.delete()
        }
    }
}

/**
 * Checks whether the .decsync-info file in [decsyncDir] is of the right format and contains a
 * supported version. If it does not exist, a new one with version 1 is created.
 *
 * @throws DecsyncException if a DecSync configuration error occurred.
 */
@ExperimentalStdlibApi
fun checkDecsyncInfo(decsyncDir: NativeFile) {
    val decsyncInfo = getDecsyncInfoOrDefault(decsyncDir)
    getDecsyncVersion(decsyncInfo)
}

/**
 * Returns a list of DecSync collections inside a [decsyncDir] for a [syncType]. This function does
 * not apply for sync types with single instances.
 *
 * @param decsyncDir the path to the main DecSync directory.
 * @param syncType the type of data to sync. For example, "contacts" or "calendars".
 */
@ExperimentalStdlibApi
fun listDecsyncCollections(decsyncDir: NativeFile, syncType: String): List<String> =
        getDecsyncSubdir(decsyncDir, syncType, null).listDirectories()

/**
 * Generates an appId corresponding to the current device and application combination. If [isRandom]
 * is enabled, a random id is attached as well. This is especially useful when the device and
 * application combination may not be unique, which is often the case on Android.
 *
 * This method cannot be consider stable: its implementation can change and the returned value has
 * to be stored by the application.
 *
 * @param appName the name of the application.
 * @param isRandom whether to append a random id or not.
 */
fun generateAppId(appName: String, isRandom: Boolean): String {
    val appId = "${getDeviceName()}-$appName"
    return if (isRandom) {
        val id = Random.Default.nextInt(100000)
        "$appId-${id.toString().padStart(5, '0')}"
    } else {
        appId
    }
}

/**
 * Returns the appId of the current device and application combination.
 *
 * Note: on Android the device name is based on the model of the device, which may not be unique.
 * Therefore, it is recommended to also generate a random [id].
 *
 * @param appName the name of the application.
 * @param id an optional integer (between 0 and 100000 exclusive) to distinguish different instances
 * with the same device and application names.
 */
@Deprecated(message = "Use generateAppId instead")
fun getAppId(appName: String, id: Int? = null): String {
    val appId = "${getDeviceName()}-$appName"
    return when (id) {
        null -> appId
        else -> "$appId-${id.toString().padStart(5, '0')}"
    }
}

internal sealed class OptExtra<T>
internal class NoExtra<T> : OptExtra<T>()
internal data class WithExtra<T>(val value: T): OptExtra<T>()

@ExperimentalStdlibApi
internal abstract class DecsyncInst<T> {
    abstract val decsyncDir: NativeFile
    abstract val localDir: DecsyncFile
    abstract val syncType: String
    abstract val collection: String?
    abstract val ownAppId: String

    val listeners: MutableList<Decsync.OnEntriesUpdateListener<T>> = mutableListOf()

    open fun addListener(subpath: List<String>, onEntryUpdate: (path: List<String>, entry: Decsync.Entry, extra: T) -> Boolean) {
        listeners += Decsync.OnEntriesUpdateListener(subpath) { path, entries, extra ->
            var allSuccess = true
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val success = onEntryUpdate(path, entry, extra)
                if (!success) {
                    iterator.remove()
                    allSuccess = false
                }
            }
            allSuccess
        }
    }

    open fun addMultiListener(subpath: List<String>, onEntriesUpdate: (path: List<String>, entries: List<Decsync.Entry>, extra: T) -> Boolean) {
        listeners += Decsync.OnEntriesUpdateListener(subpath) { path, entries, extra ->
            val success = onEntriesUpdate(path, entries, extra)
            if (!success) {
                entries.clear()
            }
            success
        }
    }

    open fun setEntry(path: List<String>, key: JsonElement, value: JsonElement) =
            setEntriesForPath(path, listOf(Decsync.Entry(key, value)))

    open fun setEntries(entriesWithPath: List<Decsync.EntryWithPath>) =
            entriesWithPath.groupBy({ it.path }, { it.entry }).forEach { (path, entries) ->
                setEntriesForPath(path, entries)
            }

    abstract fun setEntriesForPath(path: List<String>, entries: List<Decsync.Entry>)

    abstract fun executeAllNewEntries(optExtra: OptExtra<T>)

    open fun callListener(path: List<String>, entries: MutableList<Decsync.Entry>, extra: T): Boolean {
        entries.removeAll {
            path == listOf("info") &&
                    it.key is JsonPrimitive &&
                    it.key.isString &&
                    (it.key.content.startsWith("last-active-") ||
                            it.key.content.startsWith("supported-version-"))
        }
        if (entries.isEmpty()) return true
        val listener = listeners.firstOrNull { it.matchesPath(path) } ?: run {
            Log.e("Unknown action for path $path")
            return true
        }
        return listener.onEntriesUpdate(path, entries, extra)
    }

    open fun executeStoredEntry(path: List<String>, key: JsonElement, extra: T): Boolean =
            executeStoredEntriesForPathExact(path, extra, listOf(key))

    open fun executeStoredEntries(storedEntries: List<Decsync.StoredEntry>, extra: T): Boolean {
        var allSuccess = true
        storedEntries.groupBy({ it.path }, { it.key }).forEach { (path, keys) ->
            val success = executeStoredEntriesForPathExact(path, extra, keys)
            allSuccess = allSuccess && success
        }
        return allSuccess
    }

    abstract fun executeStoredEntriesForPathExact(
            path: List<String>,
            extra: T,
            keys: List<JsonElement>? = null): Boolean

    abstract fun executeStoredEntriesForPathPrefix(
            prefix: List<String>,
            extra: T,
            keys: List<JsonElement>? = null): Boolean

    abstract fun latestAppId(): String

    abstract fun deleteOwnEntries()

    open fun deleteOwnSubdir(subdir: DecsyncFile) {
        deleteSubdir(subdir, ownAppId)
    }

    companion object {
        fun deleteSubdir(subdir: DecsyncFile, appId: String) {
            subdir.child(appId).delete()
            if (subdir.listDirectories().isEmpty()) {
                subdir.delete()
            }
        }
    }
}

@ExperimentalStdlibApi
internal fun getDecsyncSubdir(decsyncDir: NativeFile, syncType: String, collection: String?): DecsyncFile {
    var dir = DecsyncFile(decsyncDir)
    dir = dir.child(syncType)
    if (collection != null) {
        dir = dir.child(collection)
    }
    return dir
}

@ExperimentalStdlibApi
private fun getDecsyncInfo(decsyncDir: NativeFile): JsonObject? {
    val file = decsyncDir.child(".decsync-info")
    val bytes = file.read() ?: return null
    val text = byteArrayToString(bytes)
    return json.parseToJsonElement(text).jsonObject
}

@SharedImmutable
private val defaultDecsyncInfo: JsonObject = buildJsonObject {
    put("version", DEFAULT_VERSION.toInt())
}

@ExperimentalStdlibApi
private fun getDecsyncInfoOrDefault(decsyncDir: NativeFile): JsonObject =
        try {
            getDecsyncInfo(decsyncDir) ?: defaultDecsyncInfo.also { setDecsyncInfo(decsyncDir, it) }
        } catch (e: Exception) {
            throw getInvalidInfoException(e)
        }

@ExperimentalStdlibApi
private fun getDecsyncVersion(info: Map<String, JsonElement>): DecsyncVersion? {
    val version = try {
        info["version"]?.jsonPrimitive?.int
    } catch (e: Exception) {
        throw getInvalidInfoException(e)
    } ?: return null
    return DecsyncVersion.fromInt(version) ?: throw getUnsupportedVersionException(version, SUPPORTED_VERSION.toInt())
}

private fun getIsFixed(info: Map<String, JsonElement>): Boolean {
    return try {
        info["fixed"]?.jsonPrimitive?.boolean ?: false
    } catch (e: Exception) {
        false
    }
}

@ExperimentalStdlibApi
private fun getNewDecsyncVersion(decsyncDir: NativeFile): DecsyncVersion {
    val decsyncInfo = getDecsyncInfoOrDefault(decsyncDir)
    val version = getDecsyncVersion(decsyncInfo)!!
    if (version >= DEFAULT_VERSION || getIsFixed(decsyncInfo)) return version

    // Check if all active apps support the new version
    val syncTypesWithoutCollections = listOf("rss")
    val syncTypesWithCollections = listOf("contacts", "calendars", "tasks")
    for (syncType in syncTypesWithoutCollections) {
        val (_, appDatas) = Decsync.getActiveApps(decsyncDir, syncType, null)
        if (appDatas.any(::isLegacyAppData)) return version
    }
    for (syncType in syncTypesWithCollections) {
        val collections = listDecsyncCollections(decsyncDir, syncType)
        for (collection in collections) {
            val (_, appDatas) = Decsync.getActiveApps(decsyncDir, syncType, collection)
            if (appDatas.any(::isLegacyAppData)) return version
        }
    }

    // Update to new default version
    val newDecsyncInfo = buildJsonObject {
        for ((key, value) in decsyncInfo.entries) {
            if (key != "version") {
                put(key, value)
            }
        }
        put("version", DEFAULT_VERSION.toInt())
    }
    setDecsyncInfo(decsyncDir, newDecsyncInfo)
    return DEFAULT_VERSION
}

@ExperimentalStdlibApi
private fun isLegacyAppData(appData: Decsync.Companion.AppData): Boolean {
    // If an active app explicitly doesn't support the latest version, we do not upgrade
    return appData.lastActive != null && appData.lastActive >= oldDatetime() &&
            appData.supportedVersion != null && appData.supportedVersion < DEFAULT_VERSION.toInt()
}

@ExperimentalStdlibApi
private fun setDecsyncInfo(decsyncDir: NativeFile, obj: JsonObject) {
    val file = decsyncDir.child(".decsync-info")
    file.write(obj.toString().encodeToByteArray())
}