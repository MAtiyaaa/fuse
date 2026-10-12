package io.github.matiyaaa.fuse.integrations

import io.github.matiyaaa.fuse.integrations.emucorex.EmuCoreX
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** EmuCoreX's catalogues, read from entries as its GitHub serves them (trimmed to a few). */
class EmuCoreXTest {
    private val textures = """{
 "schemaVersion": 1,
 "entries": [
  {
   "id": "xxtherockoxx-prince-of-persia-two-thrones-slus-21287-20260722",
   "name": "Prince of Persia: The Two Thrones HD Texture Pack",
   "gameTitle": "Prince of Persia: The Two Thrones",
   "serials": [
    "SLUS-21287"
   ],
   "version": "2026-07-22 mirror",
   "authors": [
    "xXtherockoXx"
   ],
   "downloadUrl": "https://github.com/sashkinbro/EmuCoreX-Textures/releases/download/texture-catalog-2026-07-22/prince-of-persia-two-thrones-xxtherockoxx-slus-21287.zip",
   "sourceUrl": "https://gbatemp.net/threads/prince-of-persia-warrior-within-hd.622976/",
   "license": "See source page terms",
   "sizeBytes": 1516733130,
   "sha256": "25A4C2925CB80387155F1EBE14EF18F64445A6BBB5D205758575B4CB55002758",
   "fileCount": 2129
  },
  {
   "id": "quicksliver1-ape-escape-3-multi-region-20260727",
   "name": "Ape Escape 3 UHD Texture Pack",
   "gameTitle": "Ape Escape 3",
   "serials": [
    "SCUS-97501",
    "SCES-53642",
    "SCPS-15096"
   ],
   "version": "Complete multi-region public snapshot",
   "authors": [
    "quicksliver1"
   ],
   "downloadUrl": "https://github.com/sashkinbro/EmuCoreX-Textures/releases/download/texture-catalog-2026-07-22/ape-escape-3-quicksliver1-scus-97501-sces-53642-scps-15096.zip.part001",
   "sourceUrl": "https://gbatemp.net/threads/ape-escape-3-uhd-texture.673002/",
   "license": "See source page terms",
   "sizeBytes": 2326146131,
   "sha256": "77A46E48D29C003668C7A346720617AA2B32EEAE84C8CDFACB8FED1DBE17D917",
   "fileCount": 35900,
   "parts": [
    {
     "downloadUrl": "https://github.com/sashkinbro/EmuCoreX-Textures/releases/download/texture-catalog-2026-07-22/ape-escape-3-quicksliver1-scus-97501-sces-53642-scps-15096.zip.part001",
     "sizeBytes": 1900000000,
     "sha256": "29EEEEEAD858E9B8AFAB58E31ACACE358FA3702300299034F9CA73D79A30519E"
    },
    {
     "downloadUrl": "https://github.com/sashkinbro/EmuCoreX-Textures/releases/download/texture-catalog-2026-07-22/ape-escape-3-quicksliver1-scus-97501-sces-53642-scps-15096.zip.part002",
     "sizeBytes": 426146131,
     "sha256": "9F7E97D8D8CB9FAE8EA0F03492AF72309E41A1FA0E06B36CB1ACA368C597EAB3"
    }
   ]
  },
  {
   "id": "original-texture-creator-is-not-identified-in-the-public-mirror-preserved-by-the-pcsx2-hd-texture-packs-community-archive-007-nightfire-hd-remaster-community-archive-slus-20579-20260728",
   "name": "007 Nightfire HD Texture Pack by SomberShroud",
   "gameTitle": "007 Nightfire",
   "serials": [
    "SLUS-20579"
   ],
   "version": "Complete public source snapshot",
   "authors": [
    "SomberShroud"
   ],
   "downloadUrl": "https://github.com/sashkinbro/EmuCoreX-Textures/releases/download/texture-catalog-2026-07-22/ia-007-nightfire-slus-20579.zip",
   "sourceUrl": "https://gbatemp.net/threads/james-bond-007-nightfire-usa-hd-textures.637947/",
   "license": "No explicit redistribution license stated; see source page terms.",
   "sizeBytes": 1677357777,
   "sha256": "04F9F50F72E72535AC0950CF99235DBDB2FB37D387291161445C7C9DE63892DB",
   "fileCount": 15450
  }
 ]
}"""

    private val cheats = """{
 "schemaVersion": 1,
 "entries": [
  {
   "id": "nethersx2-separated-patches-batch005-cheats-mi-bde7d749",
   "title": ".hack Mutation Part 2",
   "serials": [
    "SLES-52467"
   ],
   "crc": "BDE7D749",
   "authors": [
    "Saramagrean / PCSX2 patch contributors"
   ],
   "downloadUrl": "https://raw.githubusercontent.com/Saramagrean/NetherSX2-cheats/52187122e0adf73a50869b52ce9143e878ab4129/cheats_mi/BDE7D749.pnach",
   "sourceUrl": "https://github.com/Saramagrean/NetherSX2-cheats/blob/52187122e0adf73a50869b52ce9143e878ab4129/cheats_mi/BDE7D749.pnach",
   "sourceName": "NetherSX2 Separated Enhancement Patches",
   "license": "See upstream source terms",
   "blockCount": 14
  },
  {
   "id": "pcsx2-curated-patches-batch004-patches-sles-52467-bde7d749",
   "title": ".hack Mutation Part 2",
   "serials": [
    "SLES-52467"
   ],
   "crc": "BDE7D749",
   "authors": [
    "ElHecht"
   ],
   "downloadUrl": "https://raw.githubusercontent.com/PCSX2/pcsx2_patches/00b0003a05d8dec5f4a988c19f9c1cd162ce1d77/patches/SLES-52467_BDE7D749.pnach",
   "sourceUrl": "https://github.com/PCSX2/pcsx2_patches/blob/00b0003a05d8dec5f4a988c19f9c1cd162ce1d77/patches/SLES-52467_BDE7D749.pnach",
   "sourceName": "PCSX2 Curated Game Patches",
   "license": "See upstream source terms",
   "blockCount": 1
  },
  {
   "id": "xgamingdragon93x-slus-20265-79646c72",
   "title": "007 - Agent Under Fire NTSC-U SLUS-20265",
   "serials": [
    "SLUS-20265"
   ],
   "crc": "79646C72",
   "authors": [
    "GamingDragon93"
   ],
   "downloadUrl": "https://raw.githubusercontent.com/sashkinbro/EmuCoreX-Cheat/main/files/xgamingdragon93x/SLUS-20265_79646C72.pnach",
   "sourceUrl": "https://github.com/xGamingDragon93x/PCSX2-Ultimative-Cheats-Collection-PNACH",
   "sourceName": "PCSX2 Ultimate Cheats Collection",
   "license": "See upstream source terms",
   "blockCount": 1
  }
 ]
}"""

    @Test
    fun texturePacksAreMatchedByTheDiscsSerialAndOnlyCheckedOnesAreFetched() {
        val packs = assertNotNull(EmuCoreX.parseTextures(textures))
        assertEquals(3, packs.size)
        // The serial as PCSX2 and file names write it, or as the disc's SYSTEM.CNF does.
        assertEquals(listOf("Prince of Persia: The Two Thrones HD Texture Pack"), EmuCoreX.packsFor(packs, "SLUS-21287").map { it.name })
        assertEquals(1, EmuCoreX.packsFor(packs, "slus_212.87").size)
        assertEquals(1, EmuCoreX.packsFor(packs, "SCES-53642").size)
        assertTrue(EmuCoreX.packsFor(packs, "SLUS-99999").isEmpty())
        assertTrue(EmuCoreX.packsFor(packs, "Prince of Persia").isEmpty())
        val prince = packs[0]
        assertNull(prince.notFetchable)
        assertEquals(1_516_733_130L, prince.sizeBytes)
        assertEquals(64, prince.sha256!!.length)
        // A pack in pieces, and one whose makers gave no permission to share it, are left to their pages.
        assertTrue(packs[1].notFetchable!!.contains("2 pieces"))
        assertTrue(packs[2].notFetchable!!.contains("no permission"))
    }

    @Test
    fun pnachFilesAreMatchedByCrcAndPatchesAreToldFromCheats() {
        val list = assertNotNull(EmuCoreX.parseCheats(cheats))
        val found = EmuCoreX.cheatsFor(list, "bde7d749")
        assertEquals(2, found.size)
        // PCSX2's own patches and the enhancement collections are patches; the cheat collections are cheats.
        assertTrue(found.all { it.isPatch })
        assertTrue(!list.single { it.crc == "79646C72" }.isPatch)
        assertTrue(found.all { it.notFetchable == null })
        assertTrue(EmuCoreX.cheatsFor(list, "not a crc").isEmpty())
        assertTrue(EmuCoreX.cheatsFor(list, "00000000").isEmpty())
    }

    @Test
    fun onlyGithubIsTrustedAndAnotherSchemaIsntRead() {
        assertTrue(EmuCoreX.trusted("https://raw.githubusercontent.com/PCSX2/pcsx2_patches/x/patches/a.pnach"))
        assertTrue(!EmuCoreX.trusted("http://github.com/a"))
        assertTrue(!EmuCoreX.trusted("https://github.com.example.org/a"))
        assertNull(EmuCoreX.parseTextures(textures.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")))
        assertNull(EmuCoreX.parseCheats("not json"))
    }
}
