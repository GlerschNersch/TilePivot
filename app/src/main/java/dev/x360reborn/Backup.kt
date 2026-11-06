package dev.x360reborn

import org.json.JSONObject

/**
 * Settings backup: the launcher's whole "hub" preferences file (pins, recents, folders,
 * theme, tile size, background, clock, hidden channels) as a typed JSON document.
 *
 * It exists so a phone can move between builds signed with different keys (a self-built
 * debug copy and a signed release cannot update each other, so switching means an
 * uninstall). Keys are stored verbatim: app keys are component names, which are the same
 * in every build, so pins and folders come back pointing at the same apps.
 */
object Backup {
    const val FORMAT = "x360reborn-settings"
    const val VERSION = 1

    /** Thrown for a file that is not a settings backup this build can read. */
    class BadBackup(message: String) : Exception(message)

    /** Encodes [prefs] (a SharedPreferences.getAll() map). Unsupported value types are skipped. */
    fun encode(prefs: Map<String, *>): String {
        val values = JSONObject()
        for ((key, value) in prefs.toSortedMap()) {
            val (type, v) = when (value) {
                is String -> "string" to value
                is Boolean -> "boolean" to value
                is Int -> "int" to value
                is Long -> "long" to value
                is Float -> "float" to value.toDouble()
                else -> continue // string sets etc.: the launcher never writes them
            }
            values.put(key, JSONObject().put("type", type).put("value", v))
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("prefs", values)
            .toString(2)
    }

    /** Decodes a backup back to typed values; throws [BadBackup] for anything else. */
    fun decode(text: String): Map<String, Any> {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw BadBackup("Not a settings backup")
        }
        if (root.optString("format") != FORMAT) throw BadBackup("Not a TilePivot settings backup")
        val version = root.optInt("version", -1)
        if (version < 1 || version > VERSION) throw BadBackup("Backup is from a newer version of TilePivot")
        val values = root.optJSONObject("prefs") ?: throw BadBackup("Backup has no settings")
        val out = LinkedHashMap<String, Any>()
        for (key in values.keys()) {
            val entry = values.optJSONObject(key) ?: throw BadBackup("Damaged entry: $key")
            out[key] = try {
                when (entry.getString("type")) {
                    "string" -> entry.getString("value")
                    "boolean" -> entry.getBoolean("value")
                    "int" -> entry.getInt("value")
                    "long" -> entry.getLong("value")
                    "float" -> entry.getDouble("value").toFloat()
                    else -> throw BadBackup("Unknown type for $key")
                }
            } catch (e: BadBackup) {
                throw e
            } catch (e: Exception) {
                throw BadBackup("Damaged entry: $key")
            }
        }
        return out
    }
}
