package com.google.androidgamesdk

import java.lang.reflect.UndeclaredThrowableException
import java.util.stream.Collectors
import org.gradle.api.Project
import java.time.LocalDateTime
import java.time.Duration

/**
 * Expose the toolchains to use to compile a library against all combinations
 * of architecture/STL/Android NDK/Android SDK version.
 */
class ToolchainEnumerator {
    private val abis32Bits = listOf("armeabi-v7a", "x86")
    private val abis64Bits = listOf("arm64-v8a", "x86_64")
    val allAbis = abis32Bits + abis64Bits

    private val stls = listOf(
        "c++_static", "c++_shared"
    )

    // For the AAR, only build the dynamic libraries against shared STL.
    private val aarStls = listOf("c++_shared")

    private val allNdkToSdkMap = mapOf(
        "r23" to listOf(19, 21)
    )

    private val expressNdkToSdkMap = mapOf(
        "r23" to listOf(19, 21)
    )

    fun enumerate(
        toolchainSet: ToolchainSet,
        project: Project
    ): List<EnumeratedToolchain> {
        return when (toolchainSet) {
            ToolchainSet.ALL -> enumerateAllAarToolchains(project, allNdkToSdkMap)
            ToolchainSet.AAR -> enumerateAllAarToolchains(project, allNdkToSdkMap)
            ToolchainSet.EXPRESS -> enumerateAllAarToolchains(project, allNdkToSdkMap)
            ToolchainSet.EXPRESS_AAR -> enumerateAllAarToolchains(project, allNdkToSdkMap)
        }
    }

    private fun enumerateAllToolchains(project: Project,
                                       ndkToSdkMap: Map<String, List<Int>>): List<EnumeratedToolchain> {
        return allAbis.flatMap { abi ->
                stls.flatMap { stl ->
                    enumerateToolchains(project, abi, stl, ndkToSdkMap)
                }
            }
    }

    private fun enumerateAllAarToolchains(project: Project,
                                          ndkToSdkMap: Map<String, List<Int>>): List<EnumeratedToolchain> {
        // In the AAR, library search is handled by Prefab, that looks for API 21 for 64 bits architectures
        // even if a lower API level is requested. We need to build a different set of libraries for 32 and
        // 64 bits as a consequence.
        val aar32BitsNdkToSdkMap = ndkToSdkMap.entries.associate {
            it.key to it.value.filter { sdk -> sdk<21 } }
        val aar64BitsNdkToSdkMap = ndkToSdkMap.entries.associate {
            it.key to it.value.filter { sdk -> sdk>=21 } }

        return abis32Bits.flatMap { abi ->
            aarStls.flatMap { stl ->
                enumerateToolchains(project, abi, stl, aar32BitsNdkToSdkMap)
            }
        } + abis64Bits.flatMap { abi ->
            aarStls.flatMap { stl ->
                enumerateToolchains(project, abi, stl, aar64BitsNdkToSdkMap)
            }
        }
    }

    private fun enumerateToolchains(
        project: Project,
        abi: String,
        stl: String,
        ndksToSdks: Map<String, List<Int>>
    ): List<EnumeratedToolchain> {
        return ndksToSdks.flatMap { ndkToSdks ->
            val ndk: String = ndkToSdks.key
            ndkToSdks.value.map { sdk ->
                EnumeratedToolchain(
                    abi,
                    stl, SpecificToolchain(project, sdk.toString(), ndk)
                )
            }
        }
    }

    /**
     * Execute the specified function concurrently on the toolchains.
     * In case of an exception, it will be rethrown early (not waiting for
     * all tasks to finish).
     */
    @Throws(Exception::class)
    fun <T> innerParallelMap(
        toolchains: List<EnumeratedToolchain>,
        f: (EnumeratedToolchain) -> T
    ): List<T> {
        return toolchains.parallelStream().map { toolchain ->
            try {
                f(toolchain)
            } catch (e: UndeclaredThrowableException) {
                // Groovy thrown exceptions will be wrapped in a
                // UndeclaredThrowableException. Unwrap them.
                throw e.cause ?: e
            }
        }.collect(Collectors.toList())
    }
    @Throws(Exception::class)
    fun <T> parallelMap(
        toolchains: List<EnumeratedToolchain>,
        f: (EnumeratedToolchain) -> T,
        chunkSize: Int,
        debugMessage: String
    ): List<T> {
        val nChunkedToolchains = toolchains.size / chunkSize
        var estDuration = Duration.ZERO
        val weight = 0.9e0 // For exponentially weighted moving average of each chunk's duration
        return toolchains.chunked(chunkSize).mapIndexed { i, ts ->
            System.out.println("ToolchainEnumerator::parallelMap(${debugMessage}) "
                               + "${i+1}/${nChunkedToolchains}, chunkSize=${chunkSize}")
            val startTime = LocalDateTime.now()
            val result = innerParallelMap(ts, f)
            val endTime = LocalDateTime.now()
            val duration = Duration.between(startTime, endTime)
            estDuration = Duration.ofMillis((estDuration.toMillis() * (1-weight)
                                             + duration.toMillis() * weight).toLong())
            val estTimeLeft = Duration.ofMillis(((nChunkedToolchains - i - 1)
                                                  * estDuration.toMillis()).toLong())
            System.out.println("ToolchainEnumerator::parallelMap(${debugMessage}) took "
                               + "${duration.toString()}, est. time left: ${estTimeLeft}")
            result
        }.flatten()
    }

    data class EnumeratedToolchain(
        val abi: String,
        val stl: String,
        val toolchain: Toolchain
    )

    companion object {
        /**
         * Filter the libraries to keep only those that are compatible
         * with the Android version, NDK version and STL specified in the
         * toolchain/build options.
         */
        @JvmStatic
        fun filterBuiltLibraries(
            libraries: Collection<NativeLibrary>,
            buildOptions: BuildOptions,
            toolchain: Toolchain
        ): Collection<NativeLibrary> {
            return libraries.filter { nativeLibrary ->
                val androidVersion = toolchain.getAndroidVersion().toInt()
                val ndkVersion = toolchain.getNdkVersionNumber().toInt()
                if (nativeLibrary.minimumAndroidApiLevel != null &&
                    nativeLibrary.minimumAndroidApiLevel!! > androidVersion
                ) {
                    false
                } else if (nativeLibrary.minimumNdkVersion != null &&
                    nativeLibrary.minimumNdkVersion!! > ndkVersion
                ) {
                    false
                } else !(nativeLibrary.supportedStlVersions != null &&
                    !nativeLibrary.supportedStlVersions!!
                        .contains(buildOptions.stl))
            }
        }
    }
}
