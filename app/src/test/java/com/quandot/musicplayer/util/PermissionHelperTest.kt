/**
 * PermissionHelperTest — Unit tests for API-level storage permission sequencing logic.
 *
 * @remarks
 * Verifies Android 10 and below (single legacy storage prompt), Android 11–12 (legacy media
 * storage -> all files access), Android 13 (photos & videos -> music & audio -> all files access),
 * and Android 14+ partial visual media selection (`READ_MEDIA_VISUAL_USER_SELECTED`).
 *
 * @see PermissionHelper
 */
package com.quandot.musicplayer.util

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ═══ Unit Tests ═══
class PermissionHelperTest {

    @Test
    fun android10AndBelow_requiresOnlyLegacyStorage() {
        val denied = PermissionHelper.evaluateStorageStatus(
            sdkInt = Build.VERSION_CODES.Q,
            legacyReadGranted = false,
            legacyWriteGranted = false,
            readMediaImagesGranted = false,
            readMediaVideoGranted = false,
            readMediaVisualUserSelectedGranted = false,
            readMediaAudioGranted = false,
            isExternalStorageManager = false
        )
        assertFalse(denied.isFullyGranted)
        assertEquals(StoragePermissionStep.LEGACY_STORAGE, denied.nextRequiredStep)
        assertEquals(0, denied.completedStepCount)
        assertEquals(1, denied.totalStepCount)

        val granted = PermissionHelper.evaluateStorageStatus(
            sdkInt = Build.VERSION_CODES.Q,
            legacyReadGranted = true,
            legacyWriteGranted = true,
            readMediaImagesGranted = false,
            readMediaVideoGranted = false,
            readMediaVisualUserSelectedGranted = false,
            readMediaAudioGranted = false,
            isExternalStorageManager = false
        )
        assertTrue(granted.isFullyGranted)
        assertNull(granted.nextRequiredStep)
        assertEquals(1, granted.completedStepCount)
        assertEquals(1, granted.totalStepCount)
    }

    @Test
    fun android11And12_chainsLegacyMediaThenAllFilesAccess() {
        val step1 = PermissionHelper.evaluateStorageStatus(
            sdkInt = Build.VERSION_CODES.R,
            legacyReadGranted = false,
            legacyWriteGranted = false,
            readMediaImagesGranted = false,
            readMediaVideoGranted = false,
            readMediaVisualUserSelectedGranted = false,
            readMediaAudioGranted = false,
            isExternalStorageManager = false
        )
        assertFalse(step1.isFullyGranted)
        assertEquals(StoragePermissionStep.MEDIA_STORAGE_LEGACY, step1.nextRequiredStep)
        assertEquals(0, step1.completedStepCount)
        assertEquals(2, step1.totalStepCount)

        val step2 = PermissionHelper.evaluateStorageStatus(
            sdkInt = Build.VERSION_CODES.S_V2,
            legacyReadGranted = true,
            legacyWriteGranted = false,
            readMediaImagesGranted = false,
            readMediaVideoGranted = false,
            readMediaVisualUserSelectedGranted = false,
            readMediaAudioGranted = false,
            isExternalStorageManager = false
        )
        assertFalse(step2.isFullyGranted)
        assertEquals(StoragePermissionStep.ALL_FILES_ACCESS, step2.nextRequiredStep)
        assertEquals(1, step2.completedStepCount)
        assertEquals(2, step2.totalStepCount)

        val complete = PermissionHelper.evaluateStorageStatus(
            sdkInt = Build.VERSION_CODES.S_V2,
            legacyReadGranted = true,
            legacyWriteGranted = false,
            readMediaImagesGranted = false,
            readMediaVideoGranted = false,
            readMediaVisualUserSelectedGranted = false,
            readMediaAudioGranted = false,
            isExternalStorageManager = true
        )
        assertTrue(complete.isFullyGranted)
        assertNull(complete.nextRequiredStep)
        assertEquals(2, complete.completedStepCount)
    }

    @Test
    fun android13Plus_chainsPhotosVideosThenMusicAudioThenAllFiles() {
        val step1 = PermissionHelper.evaluateStorageStatus(
            sdkInt = Build.VERSION_CODES.TIRAMISU,
            legacyReadGranted = false,
            legacyWriteGranted = false,
            readMediaImagesGranted = false,
            readMediaVideoGranted = false,
            readMediaVisualUserSelectedGranted = false,
            readMediaAudioGranted = false,
            isExternalStorageManager = false
        )
        assertFalse(step1.isFullyGranted)
        assertEquals(StoragePermissionStep.PHOTOS_AND_VIDEOS, step1.nextRequiredStep)
        assertEquals(0, step1.completedStepCount)
        assertEquals(3, step1.totalStepCount)

        val step2 = PermissionHelper.evaluateStorageStatus(
            sdkInt = Build.VERSION_CODES.TIRAMISU,
            legacyReadGranted = false,
            legacyWriteGranted = false,
            readMediaImagesGranted = true,
            readMediaVideoGranted = true,
            readMediaVisualUserSelectedGranted = false,
            readMediaAudioGranted = false,
            isExternalStorageManager = false
        )
        assertFalse(step2.isFullyGranted)
        assertEquals(StoragePermissionStep.MUSIC_AND_AUDIO, step2.nextRequiredStep)
        assertEquals(1, step2.completedStepCount)

        val step3 = PermissionHelper.evaluateStorageStatus(
            sdkInt = Build.VERSION_CODES.TIRAMISU,
            legacyReadGranted = false,
            legacyWriteGranted = false,
            readMediaImagesGranted = true,
            readMediaVideoGranted = true,
            readMediaVisualUserSelectedGranted = false,
            readMediaAudioGranted = true,
            isExternalStorageManager = false
        )
        assertFalse(step3.isFullyGranted)
        assertEquals(StoragePermissionStep.ALL_FILES_ACCESS, step3.nextRequiredStep)
        assertEquals(2, step3.completedStepCount)

        val complete = PermissionHelper.evaluateStorageStatus(
            sdkInt = Build.VERSION_CODES.TIRAMISU,
            legacyReadGranted = false,
            legacyWriteGranted = false,
            readMediaImagesGranted = true,
            readMediaVideoGranted = true,
            readMediaVisualUserSelectedGranted = false,
            readMediaAudioGranted = true,
            isExternalStorageManager = true
        )
        assertTrue(complete.isFullyGranted)
        assertNull(complete.nextRequiredStep)
        assertEquals(3, complete.completedStepCount)
    }

    @Test
    fun android14Plus_acceptsUserSelectedVisualMediaForStep1() {
        val status = PermissionHelper.evaluateStorageStatus(
            sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            legacyReadGranted = false,
            legacyWriteGranted = false,
            readMediaImagesGranted = false,
            readMediaVideoGranted = false,
            readMediaVisualUserSelectedGranted = true,
            readMediaAudioGranted = true,
            isExternalStorageManager = true
        )
        assertTrue(status.photosAndVideosGranted)
        assertTrue(status.isFullyGranted)
        assertNull(status.nextRequiredStep)
        assertEquals(3, status.completedStepCount)
    }

    @Test
    fun notificationPermissionIsOptionalForAllRequiredGranted() {
        val grantedStorage = PermissionHelper.evaluateStorageStatus(
            sdkInt = Build.VERSION_CODES.TIRAMISU,
            legacyReadGranted = false,
            legacyWriteGranted = false,
            readMediaImagesGranted = true,
            readMediaVideoGranted = true,
            readMediaVisualUserSelectedGranted = false,
            readMediaAudioGranted = true,
            isExternalStorageManager = true
        )
        val withoutNotification = WelcomePermissionState(
            storage = grantedStorage,
            notificationGranted = false
        )
        assertTrue(withoutNotification.allRequiredGranted)

        val missingStorage = PermissionHelper.evaluateStorageStatus(
            sdkInt = Build.VERSION_CODES.TIRAMISU,
            legacyReadGranted = false,
            legacyWriteGranted = false,
            readMediaImagesGranted = true,
            readMediaVideoGranted = true,
            readMediaVisualUserSelectedGranted = false,
            readMediaAudioGranted = true,
            isExternalStorageManager = false
        )
        val withNotificationOnly = WelcomePermissionState(
            storage = missingStorage,
            notificationGranted = true
        )
        assertFalse(withNotificationOnly.allRequiredGranted)
    }
}
