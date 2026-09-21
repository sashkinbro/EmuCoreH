/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.tuningfork

import java.lang.RuntimeException

data class AppKey(public val name: String, public val version: Int) {

    companion object {
        val pathRegex = Regex("/?applications/([^/]+)/apks/(\\d+)")
        fun parse(path_in: String): AppKey {
            val match = pathRegex.find(path_in)
            if (match==null) {
                throw RuntimeException("Couldn't parse path: $path_in")
            }
            val appName = match.groupValues[1]
            val appVersion = Integer.valueOf(match.groupValues[2])
            return AppKey(appName, appVersion)
        }
    }
}

