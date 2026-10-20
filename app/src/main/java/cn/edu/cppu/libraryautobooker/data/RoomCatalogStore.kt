package cn.edu.cppu.libraryautobooker.data

import android.content.Context
import org.json.JSONArray

/** A bounded catalogue of public room rules read from the school, not a seat-status cache. */
class RoomCatalogStore(context: Context) {
    private val prefs = context.getSharedPreferences("room_catalog_v1", Context.MODE_PRIVATE)

    fun load(): List<RoomProfile> {
        val saved = runCatching {
            val array = JSONArray(prefs.getString("rooms", "[]"))
            require(array.length() <= 200)
            (0 until array.length()).mapNotNull { RoomProfile.fromJson(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        return (saved + RoomProfile.DEFAULT).distinctBy { it.id }
            .sortedWith(compareBy({ it.groupId.toInt() }, { it.id.toInt() }))
    }

    fun find(id: String) = load().find { it.id == id }

    @Synchronized
    fun merge(profiles: List<RoomProfile>) {
        require(profiles.size <= 200 && profiles.all { it.validationError() == null })
        require(profiles.groupBy { it.id }.all { (_, items) -> items.distinct().size == 1 })
        val merged = (profiles + load()).distinctBy { it.id }
        require(merged.size <= 200)
        val array = JSONArray().apply { merged.forEach { put(it.toJson()) } }
        check(prefs.edit().putString("rooms", array.toString()).commit())
    }
}
