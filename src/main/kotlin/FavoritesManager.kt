import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object FavoritesManager {
    private val favoritesFile: File = File(AppFiles.root, "favorites.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun loadFavorites(file: File = favoritesFile): Set<String> {
        return try {
            if (!file.exists()) return emptySet()
            val text = file.readText().trim()
            if (text.isBlank()) return emptySet()
            json.decodeFromString<List<String>>(text).toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun saveFavorites(favorites: Set<String>, file: File = favoritesFile) {
        try {
            val text = json.encodeToString(favorites.toList().sorted())
            AppFiles.writeAtomic(file, text)
        } catch (_: Exception) {
            // ignore failure in saving gracefully
        }
    }

    fun isFavorite(item: RemotePackage, favorites: Set<String>): Boolean {
        if (item.packageName.isNotBlank() && favorites.contains(item.packageName)) return true
        if (item.folderName.isNotBlank() && favorites.contains(item.folderName)) return true
        return false
    }

    fun toggleFavorite(item: RemotePackage, currentFavorites: Set<String>, file: File = favoritesFile): Set<String> {
        val key = item.packageName.ifBlank { item.folderName }
        val newFavorites = currentFavorites.toMutableSet()
        val isFav = isFavorite(item, currentFavorites)
        if (isFav) {
            if (item.packageName.isNotBlank()) newFavorites.remove(item.packageName)
            if (item.folderName.isNotBlank()) newFavorites.remove(item.folderName)
        } else {
            newFavorites.add(key)
        }
        saveFavorites(newFavorites, file)
        return newFavorites
    }
}
