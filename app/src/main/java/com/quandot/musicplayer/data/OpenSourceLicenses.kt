/**
 * OpenSourceLicenses — Third-party library credits and verbatim license texts bundled offline.
 *
 * @remarks
 * Keeps license disclosures self-contained inside the APK so users can inspect attribution and
 * verbatim license terms in [AboutScreen] without requiring network connectivity. Entries mirror
 * the runtime dependencies declared in `app/build.gradle.kts` (and key transitive libraries).
 * Verbatim GPL-3.0 text is stored in `res/raw/license_gpl_v3.txt` to avoid duplicating the full
 * 35 KB document inside Kotlin constant pools while remaining compliant with GPL-3.0 Sections 4 & 6.
 *
 * @see AboutScreen
 */
package com.quandot.musicplayer.data

// External SDK / Jetpack / third-party libraries
import android.content.Context
import androidx.annotation.RawRes

// Internal modules
import com.quandot.musicplayer.R

// ═══ Models ═══
/**
 * Immutable attribution record for an open-source dependency or PlayDepth's own license.
 *
 * When [licenseRawRes] is non-null, [licenseText] acts as an optional copyright/notice header
 * prepended before the verbatim raw resource text loaded off the Main thread via
 * [OpenSourceLicenses.resolveLicenseText].
 */
data class OpenSourceCredit(
    val id: String,
    val name: String,
    val author: String,
    val artifact: String,
    val licenseName: String,
    val url: String,
    val licenseText: String,
    @RawRes val licenseRawRes: Int? = null
)

// ═══ Standard License Texts ═══
private const val APACHE_2_0_LICENSE_TEXT = """Apache License
Version 2.0, January 2004
http://www.apache.org/licenses/

TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

1. Definitions.

"License" shall mean the terms and conditions for use, reproduction, and distribution as defined by Sections 1 through 9 of this document.

"Licensor" shall mean the copyright owner or entity authorized by the copyright owner that is granting the License.

"Legal Entity" shall mean the union of the acting entity and all other entities that control, are controlled by, or are under common control with that entity. For the purposes of this definition, "control" means (i) the power, direct or indirect, to cause the direction or management of such entity, whether by contract or otherwise, or (ii) ownership of fifty percent (50%) or more of the outstanding shares, or (iii) beneficial ownership of such entity.

"You" (or "Your") shall mean an individual or Legal Entity exercising permissions granted by this License.

"Source" form shall mean the preferred form for making modifications, including but not limited to software source code, documentation source, and configuration files.

"Object" form shall mean any form resulting from mechanical transformation or translation of a Source form, including but not limited to compiled object code, generated documentation, and conversions to other media types.

"Work" shall mean the work of authorship, whether in Source or Object form, made available under the License, as indicated by a copyright notice that is included in or attached to the work.

"Derivative Works" shall mean any work, whether in Source or Object form, that is based on (or derived from) the Work and for which the editorial revisions, annotations, elaborations, or other modifications represent, as a whole, an original work of authorship.

"Contribution" shall mean any work of authorship, including the original version of the Work and any modifications or additions to that Work or Derivative Works thereof, that is intentionally submitted to Licensor for inclusion in the Work by the copyright owner or by an individual or Legal Entity authorized to submit on behalf of the copyright owner.

"Contributor" shall mean Licensor and any individual or Legal Entity on behalf of whom a Contribution has been received by Licensor and subsequently incorporated within the Work.

2. Grant of Copyright License. Subject to the terms and conditions of this License, each Contributor hereby grants to You a perpetual, worldwide, non-exclusive, no-charge, royalty-free, irrevocable copyright license to reproduce, prepare Derivative Works of, publicly display, publicly perform, sublicense, and distribute the Work and such Derivative Works in Source or Object form.

3. Grant of Patent License. Subject to the terms and conditions of this License, each Contributor hereby grants to You a perpetual, worldwide, non-exclusive, no-charge, royalty-free, irrevocable (except as stated in this section) patent license to make, have made, use, offer to sell, sell, import, and otherwise transfer the Work, where such license applies only to those patent claims licensable by such Contributor that are necessarily infringed by their Contribution(s) alone or by combination of their Contribution(s) with the Work to which such Contribution(s) was submitted.

4. Redistribution. You may reproduce and distribute copies of the Work or Derivative Works thereof in any medium, with or without modifications, and in Source or Object form, provided that You meet the following conditions:
(a) You must give any other recipients of the Work or Derivative Works a copy of this License; and
(b) You must cause any modified files to carry prominent notices stating that You changed the files; and
(c) You must retain, in the Source form of any Derivative Works that You distribute, all copyright, patent, trademark, and attribution notices from the Source form of the Work; and
(d) If the Work includes a "NOTICE" text file as part of its distribution, then any Derivative Works that You distribute must include a readable copy of the attribution notices contained within such NOTICE file.

5. Submission of Contributions. Unless You explicitly state otherwise, any Contribution intentionally submitted for inclusion in the Work by You to the Licensor shall be under the terms and conditions of this License, without any additional terms or conditions.

6. Trademarks. This License does not grant permission to use the trade names, trademarks, service marks, or product names of the Licensor, except as required for reasonable and customary use in describing the origin of the Work and reproducing the content of the NOTICE file.

7. Disclaimer of Warranty. Unless required by applicable law or agreed to in writing, Licensor provides the Work (and each Contributor provides its Contributions) on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied, including, without limitation, any warranties or conditions of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A PARTICULAR PURPOSE.

8. Limitation of Liability. In no event and under no legal theory, whether in tort (including negligence), contract, or otherwise, unless required by applicable law (such as deliberate and grossly negligent acts) or agreed to in writing, shall any Contributor be liable to You for damages, including any direct, indirect, special, incidental, or consequential damages of any character arising as a result of this License or out of the use or inability to use the Work.

9. Accepting Warranty or Additional Liability. While redistributing the Work or Derivative Works thereof, You may choose to offer, and charge a fee for, acceptance of support, warranty, indemnity, or other liability obligations and/or rights consistent with this License."""

private const val PLAYDEPTH_GPL_HEADER = """PlayDepth — Hi-Res & Bit-Perfect USB DAC Music Player
Copyright (C) Quan. Works

This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, version 3 of the License.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the verbatim GNU General Public License v3.0 text below for full terms and conditions."""

private const val FFMPEG_NOTICE_HEADER = """FFmpeg — Software Audio Decoding Libraries
Copyright (c) 2000-2025 the FFmpeg developers

PlayDepth uses FFmpeg audio decoders compiled inside Jellyfin Media3 FFmpeg Decoder (`media3-ffmpeg-decoder`) to decode lossless and legacy audio formats (FLAC, ALAC, APE, WMA, etc.).

FFmpeg is licensed under the GNU Lesser General Public License (LGPL) version 2.1 or later, and builds enabling GPL components (such as Jellyfin's `media3-ffmpeg-decoder`) are distributed under the GNU General Public License (GPL) version 3.0.

In accordance with GNU GPL v3.0 / LGPL v2.1+:
- This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
- PlayDepth itself is distributed under the GNU General Public License v3.0 (GPL-3.0).

License references:
- GNU LGPL v2.1: https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt
- GNU GPL v3.0: https://www.gnu.org/licenses/gpl-3.0.txt
- FFmpeg legal info: https://ffmpeg.org/legal.html"""

// ═══ Registry ═══
object OpenSourceLicenses {

    val playDepthAppLicense = OpenSourceCredit(
        id = "playdepth_gplv3",
        name = "PlayDepth",
        author = "Quan. Works",
        artifact = "com.quandot.playdepth.music",
        licenseName = "GPL-3.0",
        url = "https://www.gnu.org/licenses/gpl-3.0.en.html",
        licenseText = PLAYDEPTH_GPL_HEADER,
        licenseRawRes = R.raw.license_gpl_v3
    )

    val credits: List<OpenSourceCredit> = listOf(
        OpenSourceCredit(
            id = "androidx_media3",
            name = "AndroidX Media3 (ExoPlayer & MediaSession)",
            author = "The Android Open Source Project",
            artifact = "androidx.media3:media3-{exoplayer,session,ui,common}:1.5.0",
            licenseName = "Apache-2.0",
            url = "https://github.com/androidx/media",
            licenseText = "Copyright (C) The Android Open Source Project\n\n$APACHE_2_0_LICENSE_TEXT"
        ),
        OpenSourceCredit(
            id = "jellyfin_media3_ffmpeg",
            name = "Jellyfin Media3 FFmpeg Decoder",
            author = "Jellyfin Contributors",
            artifact = "org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1",
            licenseName = "GPL-3.0",
            url = "https://github.com/jellyfin/jellyfin-androidx-media",
            licenseText = "Copyright (C) Jellyfin Contributors",
            licenseRawRes = R.raw.license_gpl_v3
        ),
        OpenSourceCredit(
            id = "ffmpeg",
            name = "FFmpeg",
            author = "FFmpeg Developers",
            artifact = "libavcodec / libavutil (via media3-ffmpeg-decoder)",
            licenseName = "LGPL-2.1+ / GPL-3.0",
            url = "https://ffmpeg.org/",
            licenseText = FFMPEG_NOTICE_HEADER,
            licenseRawRes = R.raw.license_gpl_v3
        ),
        OpenSourceCredit(
            id = "jetpack_compose_m3",
            name = "Jetpack Compose & Material 3",
            author = "The Android Open Source Project",
            artifact = "androidx.compose:compose-bom:2024.10.01",
            licenseName = "Apache-2.0",
            url = "https://developer.android.com/jetpack/compose",
            licenseText = "Copyright (C) The Android Open Source Project\n\n$APACHE_2_0_LICENSE_TEXT"
        ),
        OpenSourceCredit(
            id = "androidx_jetpack",
            name = "AndroidX Core, Lifecycle, Navigation, DataStore & ProfileInstaller",
            author = "The Android Open Source Project",
            artifact = "androidx.{core,lifecycle,activity,navigation,datastore,profileinstaller}",
            licenseName = "Apache-2.0",
            url = "https://source.android.com/",
            licenseText = "Copyright (C) The Android Open Source Project\n\n$APACHE_2_0_LICENSE_TEXT"
        ),
        OpenSourceCredit(
            id = "kotlin",
            name = "Kotlin Standard Library & Coroutines",
            author = "JetBrains s.r.o. and Kotlin Contributors",
            artifact = "org.jetbrains.kotlin / kotlinx-coroutines",
            licenseName = "Apache-2.0",
            url = "https://kotlinlang.org/",
            licenseText = "Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors\n\n$APACHE_2_0_LICENSE_TEXT"
        ),
        OpenSourceCredit(
            id = "coil",
            name = "Coil (Coroutine Image Loader)",
            author = "Coil Contributors",
            artifact = "io.coil-kt:coil-compose:2.7.0",
            licenseName = "Apache-2.0",
            url = "https://github.com/coil-kt/coil",
            licenseText = "Copyright 2024 Coil Contributors\n\n$APACHE_2_0_LICENSE_TEXT"
        ),
        OpenSourceCredit(
            id = "okhttp_okio",
            name = "OkHttp & Okio",
            author = "Square, Inc.",
            artifact = "com.squareup.okhttp3:okhttp / com.squareup.okio:okio (via Coil)",
            licenseName = "Apache-2.0",
            url = "https://square.github.io/okhttp/",
            licenseText = "Copyright (C) Square, Inc.\n\n$APACHE_2_0_LICENSE_TEXT"
        ),
        OpenSourceCredit(
            id = "guava",
            name = "Guava",
            author = "The Guava Authors (Google LLC)",
            artifact = "com.google.guava:guava (via AndroidX Media3)",
            licenseName = "Apache-2.0",
            url = "https://github.com/google/guava",
            licenseText = "Copyright (C) The Guava Authors\n\n$APACHE_2_0_LICENSE_TEXT"
        ),
        OpenSourceCredit(
            id = "reorderable",
            name = "Reorderable",
            author = "Calvin Liang (Calvin-LL)",
            artifact = "sh.calvin.reorderable:reorderable:2.4.3",
            licenseName = "Apache-2.0",
            url = "https://github.com/Calvin-LL/Reorderable",
            licenseText = "Copyright 2024 Calvin Liang\n\n$APACHE_2_0_LICENSE_TEXT"
        ),
        OpenSourceCredit(
            id = "fastscroller",
            name = "FastScroller for Compose Multiplatform",
            author = "Yuya (oikvpqya)",
            artifact = "io.github.oikvpqya.compose.fastscroller:fastscroller-{core,material3,indicator}:0.3.2",
            licenseName = "Apache-2.0",
            url = "https://github.com/oikvpqya/fastscroller-compose-multiplatform",
            licenseText = "Copyright 2025 Yuya (oikvpqya)\n\n$APACHE_2_0_LICENSE_TEXT"
        )
    )

    /**
     * Resolves the full license body for [credit], reading [OpenSourceCredit.licenseRawRes]
     * verbatim when present. Must be called from a background dispatcher (e.g. `Dispatchers.IO`).
     */
    fun resolveLicenseText(context: Context, credit: OpenSourceCredit): String {
        val rawRes = credit.licenseRawRes ?: return credit.licenseText
        val verbatimText = context.resources.openRawResource(rawRes)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
            .trimEnd()
        return if (credit.licenseText.isBlank()) {
            verbatimText
        } else {
            "${credit.licenseText}\n\n$verbatimText"
        }
    }
}
