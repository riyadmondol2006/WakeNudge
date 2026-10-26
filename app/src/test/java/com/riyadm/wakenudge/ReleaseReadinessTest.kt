package com.riyadm.wakenudge

import java.io.File
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Guards what is cheap to break by accident before a release: the listing text and images in fastlane/metadata (the
 * format app catalogues such as F-Droid read), and the manifest. Unit tests run with the module directory as the
 * working directory, so the repository root is one level up.
 *
 * The manifest checks read `src/main/AndroidManifest.xml`; the `verifyOfflineManifest*` Gradle tasks check the merged
 * manifests, libraries included.
 */
class ReleaseReadinessTest {
    private val listing = File("../fastlane/metadata/android/en-US")
    private val manifestFile = File("src/main/AndroidManifest.xml")

    private fun text(name: String) = File(listing, name).readText(Charsets.UTF_8).trimEnd('\n', '\r')

    // ---- store listing text --------------------------------------------------------------------------------------

    @Test fun `title short and full description fit the listing limits`() {
        assertTrue("title <= 30", text("title.txt").length in 1..30)
        assertTrue("short description <= 80", text("short_description.txt").length in 1..80)
        assertTrue("full description <= 4000", text("full_description.txt").length in 1..4000)
    }

    @Test fun `every release note fits the 500 character limit`() {
        val notes = File(listing, "changelogs").listFiles { f -> f.extension == "txt" }.orEmpty()
        assertTrue("at least one release note", notes.isNotEmpty())
        for (f in notes) {
            val length = f.readText(Charsets.UTF_8).trimEnd('\n', '\r').length
            assertTrue("${f.name} is $length characters", length in 1..500)
        }
    }

    @Test fun `short description is a single plain line`() {
        val short = text("short_description.txt")
        assertFalse("no line breaks", short.contains('\n'))
        assertFalse("no repeated punctuation", Regex("""[!?.]{2,}""").containsMatchIn(short))
        assertFalse("no all-caps words for emphasis", Regex("""\b[A-Z]{5,}\b""").containsMatchIn(short))
    }

    @Test fun `listing text has no emoji and no ranking or competitor claims`() {
        val forbidden = listOf(
            "alarmy", "google clock", "clock by google", "sleep as android", "samsung clock", "best alarm",
            "#1", "number one", "top-rated", "top rated", "download now", "install now", "limited time", "editor's choice",
        )
        val files = listOf("title.txt", "short_description.txt", "full_description.txt") +
            File(listing, "changelogs").listFiles().orEmpty().map { "changelogs/${it.name}" }
        for (name in files) {
            val content = text(name)
            val lower = content.lowercase()
            for (word in forbidden) assertFalse("$name mentions '$word'", lower.contains(word))
            content.codePoints().forEach { cp ->
                val emoji = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp == 0xFE0F || cp == 0x200D
                assertFalse("$name contains emoji/symbol U+${cp.toString(16)}", emoji)
            }
        }
    }

    // ---- store listing graphics ----------------------------------------------------------------------------------

    private fun size(path: String): Pair<Int, Int> {
        val image = ImageIO.read(File(listing, path)) ?: error("$path is not a readable image")
        return image.width to image.height
    }

    @Test fun `icon is 512 by 512 and under 1 MB`() {
        assertEquals(512 to 512, size("images/icon.png"))
        assertTrue(File(listing, "images/icon.png").length() <= 1024 * 1024)
    }

    @Test fun `feature graphic is 1024 by 500 without transparency`() {
        assertEquals(1024 to 500, size("images/featureGraphic.png"))
        val image = ImageIO.read(File(listing, "images/featureGraphic.png"))
        assertFalse("feature graphic must not have an alpha channel", image.colorModel.hasAlpha())
    }

    @Test fun `phone screenshots are two to eight, within the usual size and aspect rules`() {
        val shots = File(listing, "images/phoneScreenshots").listFiles { f -> f.extension in setOf("png", "jpg", "jpeg") }.orEmpty()
        assertTrue("2..8 phone screenshots, found ${shots.size}", shots.size in 2..8)
        for (f in shots) {
            val image = ImageIO.read(f) ?: error("${f.name} is not readable")
            val short = minOf(image.width, image.height)
            val long = maxOf(image.width, image.height)
            assertTrue("${f.name} sides must be 320..3840 px", short >= 320 && long <= 3840)
            assertTrue("${f.name} long side must not exceed twice the short side", long <= 2 * short)
            assertFalse("${f.name} must not have an alpha channel", image.colorModel.hasAlpha())
        }
    }

    // ---- shared manifest -----------------------------------------------------------------------------------------

    private val manifest: Element by lazy {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        factory.newDocumentBuilder().parse(manifestFile).documentElement
    }

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    private val application: Element get() = manifest.children("application").single()

    @Test fun `manifest declares no advertising-id or wide-reaching permissions, and no network one but INTERNET`() {
        // `tools:node="remove"` entries only strip a permission that a library would otherwise add.
        val permissions = manifest.children("uses-permission").filter { it.getAttribute("tools:node") != "remove" }
            .map { it.getAttribute("android:name") }
        val banned = setOf(
            "android.permission.ACCESS_NETWORK_STATE", "android.permission.ACCESS_WIFI_STATE",
            "com.google.android.gms.permission.AD_ID", "android.permission.QUERY_ALL_PACKAGES",
            "android.permission.REQUEST_INSTALL_PACKAGES", "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_BACKGROUND_LOCATION",
            "android.permission.READ_CONTACTS", "android.permission.RECORD_AUDIO", "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.READ_PHONE_STATE", "android.permission.SYSTEM_ALERT_WINDOW",
        )
        assertEquals("unexpected permissions", emptySet<String>(), permissions.toSet().intersect(banned))
        // INTERNET is for the optional update check alone.
        assertTrue(permissions.contains("android.permission.INTERNET"))
    }

    @Test fun `exact alarm permissions follow the alarm clock pattern`() {
        val permissions = manifest.children("uses-permission").associate { it.getAttribute("android:name") to it }
        assertTrue(permissions.containsKey("android.permission.USE_EXACT_ALARM"))
        val legacy = permissions.getValue("android.permission.SCHEDULE_EXACT_ALARM")
        assertEquals("SCHEDULE_EXACT_ALARM must be capped for Android 12L and lower", "32", legacy.getAttribute("android:maxSdkVersion"))
        assertTrue(permissions.containsKey("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"))
    }

    @Test fun `optional hardware is never required so the app installs on every device`() {
        val features = manifest.children("uses-feature").associate { it.getAttribute("android:name") to it.getAttribute("android:required") }
        for (name in listOf(
            "android.hardware.camera", "android.hardware.camera.autofocus", "android.hardware.nfc",
            "android.hardware.sensor.accelerometer", "android.hardware.sensor.stepcounter", "android.hardware.touchscreen",
        )) {
            assertEquals("$name must be declared required=false", "false", features[name])
        }
        assertFalse("no uses-feature may be required", features.values.any { it != "false" })
    }

    @Test fun `application is not debuggable, test-only or cleartext, and opts out of backup deliberately`() {
        assertEquals("", application.getAttribute("android:debuggable"))
        assertEquals("", application.getAttribute("android:testOnly"))
        assertEquals("false", application.getAttribute("android:allowBackup"))
        assertEquals("false", application.getAttribute("android:usesCleartextTraffic"))
        assertEquals("@xml/data_extraction_rules", application.getAttribute("android:dataExtractionRules"))
        assertEquals("@xml/backup_rules", application.getAttribute("android:fullBackupContent"))
        assertEquals("true", application.getAttribute("android:enableOnBackInvokedCallback"))
    }

    @Test fun `no screen orientation or resizability restrictions, which large screens ignore`() {
        val text = manifestFile.readText()
        assertFalse(text.contains("screenOrientation"))
        assertFalse(text.contains("resizeableActivity=\"false\""))
        assertFalse(text.contains("maxAspectRatio"))
    }

    @Test fun `every component states android exported and only the reviewed ones are exported`() {
        val components = listOf("activity", "activity-alias", "service", "receiver", "provider")
            .flatMap { tag -> application.children(tag) }
        assertTrue(components.isNotEmpty())
        for (c in components) {
            assertTrue("${c.getAttribute("android:name")} must state android:exported", c.hasAttribute("android:exported"))
        }
        val exported = components.filter { it.getAttribute("android:exported") == "true" }.map { it.getAttribute("android:name") }.toSet()
        val reviewed = setOf(
            ".MainActivity", ".TilePreferencesAlias", ".engine.SystemEventsReceiver",
            ".widget.NextAlarmWidgetReceiver", ".widget.ClockWidgetReceiver", ".widget.AlarmListWidgetReceiver",
            ".tile.NextAlarmTileService",
        )
        assertEquals("Exported components changed: review hostile-caller safety, then update docs/PERMISSIONS.md and this list", reviewed, exported)
        // The tile service must stay protected by the system-only binding permission.
        val tile = components.single { it.getAttribute("android:name") == ".tile.NextAlarmTileService" }
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", tile.getAttribute("android:permission"))
    }

    @Test fun `the ring screens are not exported and may show over the lock screen`() {
        for (name in listOf(".ui.ring.RingActivity", ".ui.ring.WakeCheckActivity")) {
            val activity = application.children("activity").single { it.getAttribute("android:name") == name }
            assertEquals("false", activity.getAttribute("android:exported"))
            assertEquals("true", activity.getAttribute("android:showWhenLocked"))
        }
        val service = application.children("service").single { it.getAttribute("android:name") == ".engine.RingService" }
        assertEquals("mediaPlayback", service.getAttribute("android:foregroundServiceType"))
        assertEquals("false", service.getAttribute("android:exported"))
    }

    // ---- backup rules --------------------------------------------------------------------------------------------

    @Test fun `backup rules exclude every domain from cloud backup and device transfer`() {
        val domains = listOf("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref")
        fun excluded(file: String, section: String?): Set<String> {
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/xml/$file")).documentElement
            val scope = if (section == null) root else root.children(section).single()
            return scope.children("exclude").map { it.getAttribute("domain") }.toSet()
        }
        assertEquals(domains.toSet(), excluded("data_extraction_rules.xml", "cloud-backup"))
        assertEquals(domains.toSet(), excluded("data_extraction_rules.xml", "device-transfer"))
        assertEquals(domains.toSet(), excluded("backup_rules.xml", null))
    }
}
