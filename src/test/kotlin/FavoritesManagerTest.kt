import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File
import java.nio.file.Files

class FavoritesManagerTest {

    @Test
    fun testFavoritesToggleAndPersistence() {
        val tempDir = Files.createTempDirectory("fav-test").toFile()
        val favFile = File(tempDir, "favorites.json")

        try {
            val pkg1 = RemotePackage(
                folderName = "BeatSaber-v1.37",
                displayName = "Beat Saber",
                packageName = "com.beatgames.beatsaber"
            )
            val pkg2 = RemotePackage(
                folderName = "Superhot-v1.0",
                displayName = "SUPERHOT VR",
                packageName = "com.superhot.vr"
            )

            // Initially empty
            var favs = FavoritesManager.loadFavorites(favFile)
            assertTrue(favs.isEmpty())
            assertFalse(FavoritesManager.isFavorite(pkg1, favs))

            // Add pkg1
            favs = FavoritesManager.toggleFavorite(pkg1, favs, favFile)
            assertEquals(1, favs.size)
            assertTrue(FavoritesManager.isFavorite(pkg1, favs))
            assertFalse(FavoritesManager.isFavorite(pkg2, favs))

            // Add pkg2
            favs = FavoritesManager.toggleFavorite(pkg2, favs, favFile)
            assertEquals(2, favs.size)
            assertTrue(FavoritesManager.isFavorite(pkg1, favs))
            assertTrue(FavoritesManager.isFavorite(pkg2, favs))

            // Reload from file to ensure persistence
            val reloaded = FavoritesManager.loadFavorites(favFile)
            assertEquals(favs, reloaded)

            // Remove pkg1
            favs = FavoritesManager.toggleFavorite(pkg1, favs, favFile)
            assertEquals(1, favs.size)
            assertFalse(FavoritesManager.isFavorite(pkg1, favs))
            assertTrue(FavoritesManager.isFavorite(pkg2, favs))

            // Check reload after removal
            val reloadedAfterRemove = FavoritesManager.loadFavorites(favFile)
            assertEquals(favs, reloadedAfterRemove)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
