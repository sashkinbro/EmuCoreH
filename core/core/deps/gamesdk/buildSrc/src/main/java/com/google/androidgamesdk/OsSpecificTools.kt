package com.google.androidgamesdk

import org.gradle.api.GradleException
import org.gradle.internal.os.OperatingSystem
import java.nio.file.Paths

enum class ExternalToolName { PROTOBUF, CMAKE }

class OsSpecificTools {
    companion object {
        @JvmStatic
        fun osExecutableSuffix(): String {
            if (OperatingSystem.current().isWindows) {
                return ".exe"
            } else {
                return ""
            }
        }

        @JvmStatic
        fun joinPath(first: String, vararg more: String): String {
            return Paths.get(first, *more).toString()
        }

        @JvmStatic
        fun osFolderName(externalToolName: ExternalToolName): String {
            if (OperatingSystem.current().isMacOsX) {
                if (externalToolName == ExternalToolName.PROTOBUF) {
                    return "mac"
                } else if (externalToolName == ExternalToolName.CMAKE) {
                    return "darwin-x86"
                }
            } else if (OperatingSystem.current().isLinux)
                return "linux-x86"
            else if (OperatingSystem.current().isWindows) {
                if (externalToolName == ExternalToolName.PROTOBUF) {
                    return "win"
                } else if (externalToolName == ExternalToolName.CMAKE) {
                    return "windows-x86"
                }
            }

            throw GradleException("Unsupported OS")
        }
    }
}
