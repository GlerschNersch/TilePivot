package dev.x360reborn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostPathsTest {
    private val ext = "content://com.android.externalstorage.documents"
    private val volumes = mapOf(
        "primary" to "/storage/emulated/0",
        "1A2B-3C4D" to "/storage/1A2B-3C4D",
    )

    private fun map(uri: String) = HostPaths.map(uri, volumes)

    @Test fun primaryTreeAndDocumentMapUnderTheSharedStorageRoot() {
        assertEquals("/storage/emulated/0/Xbox360", map("$ext/tree/primary%3AXbox360"))
        assertEquals("/storage/emulated/0/Xbox360/Halo 3/default.xex",
            map("$ext/tree/primary%3AXbox360/document/primary%3AXbox360%2FHalo%203%2Fdefault.xex"))
        assertEquals("/storage/emulated/0/Games/Skate 3.iso", map("$ext/document/primary%3AGames%2FSkate%203.iso"))
        // The volume root itself.
        assertEquals("/storage/emulated/0", map("$ext/tree/primary%3A"))
    }

    @Test fun sdCardVolumeMapsThroughTheStorageVolumes() {
        assertEquals("/storage/1A2B-3C4D/ROMs/360/Game.iso",
            map("$ext/tree/1A2B-3C4D%3AROMs/document/1A2B-3C4D%3AROMs%2F360%2FGame.iso"))
        // Volume ids match case-insensitively (the provider and StorageVolume.getUuid may differ).
        assertEquals("/storage/1A2B-3C4D/x.iso", map("$ext/document/1a2b-3c4d%3Ax.iso"))
    }

    @Test fun rawDocumentIdsCarryTheirPathVerbatim() {
        assertEquals("/storage/emulated/0/Download/Game.iso",
            map("content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2FGame.iso"))
        assertEquals("/storage/emulated/0/Download/Xbox",
            map("content://com.android.providers.downloads.documents/tree/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2FXbox%2F"))
        // A raw id must be absolute.
        assertNull(map("content://com.android.providers.downloads.documents/document/raw%3ADownload%2Fx.iso"))
    }

    @Test fun downloadsAndCloudProvidersCannotBeMapped() {
        assertNull(map("content://com.android.providers.downloads.documents/tree/downloads"))
        assertNull(map("content://com.android.providers.downloads.documents/document/msf%3A1000000123"))
        assertNull(map("content://com.android.providers.downloads.documents/document/42"))
        assertNull(map("content://com.google.android.apps.docs.storage/tree/acc%3D1%3Bdoc%3Dencoded%3Dabc"))
        assertNull(map("content://com.microsoft.skydrive.content.StorageAccessProvider/tree/1%7Croot"))
        assertNull(map("content://com.android.providers.media.documents/document/video%3A55"))
    }

    @Test fun unknownVolumesAndMalformedOrUnsafeUrisAreNull() {
        // A card that is not mounted (or not reported by StorageManager) has no path.
        assertNull(map("$ext/tree/9F00-0001%3AGames"))
        assertNull(map("$ext/tree/home%3AGames"))
        assertNull(map("$ext/tree/noColonId"))
        assertNull(map("file:///storage/emulated/0/Xbox360"))
        assertNull(map("content://"))
        assertNull(map("$ext/tree/"))
        assertNull(map("$ext/tree/primary%3AXbox/document"))
        assertNull(map("$ext/tree/primary%3A%ZZ"))
        // No climbing out of the volume.
        assertNull(map("$ext/document/primary%3AXbox360%2F..%2F..%2Fdata"))
        assertNull(map("content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2F..%2Fdata"))
    }

    @Test fun plusSignsAndUnicodeSurviveDecoding() {
        assertEquals("/storage/emulated/0/Games/Rock Band+/Pokémon.iso",
            map("$ext/document/primary%3AGames%2FRock%20Band%2B%2FPok%C3%A9mon.iso"))
        assertEquals("/storage/emulated/0/a+b", map("$ext/document/primary%3Aa+b"))
    }

    @Test fun describeShowsThePathOrTheFolderName() {
        assertEquals("/storage/emulated/0/Xbox360", HostPaths.describe("$ext/tree/primary%3AXbox360", volumes))
        assertEquals("Xbox", HostPaths.describe(
            "content://com.google.android.apps.docs.storage/tree/acc%3D1%2FXbox", volumes))
    }
}
