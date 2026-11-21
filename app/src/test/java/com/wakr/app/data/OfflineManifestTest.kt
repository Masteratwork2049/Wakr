package com.wakr.app.data

import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element

class OfflineManifestTest {
    @Test fun dependenciesCannotIntroduceNetworkPermissions() {
        val config = Properties()
        requireNotNull(javaClass.getResourceAsStream("/com/android/tools/test_config.properties")).use(config::load)
        val manifest = File(config.getProperty("android_merged_manifest"))
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        for (tag in listOf("uses-permission", "uses-permission-sdk-23")) {
            val nodes = document.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val name = (nodes.item(i) as Element).getAttribute("android:name")
                assertFalse("Merged manifest requests $name",
                    name in setOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"))
            }
        }
    }
}
