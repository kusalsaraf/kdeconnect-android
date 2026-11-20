/*
 * SPDX-FileCopyrightText: 2026 Kusal Saraf <kusalsaraf5@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.helpers

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.spyk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationHelperTest {
    @Test
    fun persistentChannelShowsStatusIconSilently() {
        // App string resources aren't available to Robolectric in this project
        val context = spyk(ApplicationProvider.getApplicationContext<Application>())
        every { context.getString(any()) } returns "Persistent indicator"
        val channel = NotificationHelper.persistentChannel(context)
        // LOW shows the status-bar icon without sound or a pop-up; MIN shows no icon
        assertEquals(NotificationManagerCompat.IMPORTANCE_LOW, channel.importance)
        // Android won't raise the importance of an existing channel, so it needs a new id
        assertNotEquals("persistent", channel.id)
    }
}
