package dev.x360reborn.home

/** App search over labels: starts-with matches rank ahead of other contains matches. */
object Search {
    fun filter(apps: List<AppInfo>, query: String): List<AppInfo> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return apps.sortedBy { it.label.lowercase() }
        val starts = ArrayList<AppInfo>()
        val contains = ArrayList<AppInfo>()
        for (a in apps) {
            val label = a.label.lowercase()
            when {
                label.startsWith(q) -> starts += a
                q in label -> contains += a
            }
        }
        return starts.sortedBy { it.label.lowercase() } + contains.sortedBy { it.label.lowercase() }
    }
}
