package com.cinemaplus.rebuilt.ui.mobile

import android.content.Context
import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class DeviceInterfaceTest {
    private fun device(type: Int): Context {
        val app = RuntimeEnvironment.getApplication()
        val configuration = Configuration(app.resources.configuration)
        configuration.uiMode = type or Configuration.UI_MODE_NIGHT_YES
        return app.createConfigurationContext(configuration)
    }

    @Test fun modePersistsAcrossContextsAndCanReturnToDeviceDetection() {
        val phone = device(Configuration.UI_MODE_TYPE_NORMAL)
        val tv = device(Configuration.UI_MODE_TYPE_TELEVISION)
        assertFalse(usesTelevisionInterface(phone))
        assertTrue(usesTelevisionInterface(tv))

        setInterfaceMode(phone, InterfaceMode.Television)
        assertTrue(usesTelevisionInterface(device(Configuration.UI_MODE_TYPE_NORMAL)))
        setInterfaceMode(tv, InterfaceMode.Mobile)
        assertFalse(usesTelevisionInterface(device(Configuration.UI_MODE_TYPE_TELEVISION)))

        setInterfaceMode(phone, InterfaceMode.Automatic)
        assertFalse(usesTelevisionInterface(phone))
        assertTrue(usesTelevisionInterface(tv))
    }
}
