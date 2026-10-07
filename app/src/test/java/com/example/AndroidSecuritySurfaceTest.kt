package com.example

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidSecuritySurfaceTest {
    @Test
    fun backupsAreDisabledAndNoNonLauncherComponentIsExported() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageManager = context.packageManager
        val applicationInfo = packageManager.getApplicationInfo(context.packageName, 0)

        assertFalse((applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP) != 0)
        assertFalse(applicationInfo.usesCleartextTraffic)

        val packageInfo = packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS
        )
        val mainActivity = packageInfo.activities.orEmpty().firstOrNull {
            it.name == MainActivity::class.java.name
        }
        assertNotNull(mainActivity)
        assertTrue(mainActivity!!.exported)

        val appClassPrefix = "${MainActivity::class.java.packageName}."
        val exportedAppActivities = packageInfo.activities.orEmpty().filter {
            it.name.startsWith(appClassPrefix) && it.exported
        }
        assertEquals(1, exportedAppActivities.size)
        assertEquals(MainActivity::class.java.name, exportedAppActivities.single().name)
        assertTrue(packageInfo.services.orEmpty().filter { it.name.startsWith(appClassPrefix) }.none { it.exported })
        assertTrue(packageInfo.receivers.orEmpty().filter { it.name.startsWith(appClassPrefix) }.none { it.exported })
    }

    @Test
    fun fileProviderIsPrivateAndOnlySharesOfferPdfs() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val authority = "${context.packageName}.fileprovider"
        val provider = context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)

        assertNotNull(provider)
        assertFalse(provider!!.exported)
        assertTrue(provider.grantUriPermissions)

        val pathsResource = provider.metaData?.getInt("android.support.FILE_PROVIDER_PATHS") ?: 0
        assertTrue(pathsResource != 0)
        val parser = context.resources.getXml(pathsResource)
        var filesPathCount = 0
        var filesPath: String? = null
        try {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "files-path") {
                    filesPathCount++
                    filesPath = parser.getAttributeValue(
                        "http://schemas.android.com/apk/res/android",
                        "path"
                    )
                }
            }
        } finally {
            parser.close()
        }

        assertEquals(1, filesPathCount)
        assertEquals("offers/", filesPath)
    }
}
