// Android wrapper around the vendored Flycast core in ../core.
//
// This module does not contain any Java/Kotlin code or Flycast's own
// frontend: it only compiles the upstream CMake project with LIBRETRO=ON,
// producing flycast_libretro.so. The EmuCoreH JNI bridge in :app loads the
// produced library at runtime through dlopen.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.sbro.emucoreh.core.flycast"
    compileSdk {
        version = release(37)
    }
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DLIBRETRO=ON",
                    "-DANDROID_STL=c++_shared",
                    "-DUSE_HOST_SDL=OFF",
                    "-DUSE_HOST_LIBZIP=OFF",
                    "-DUSE_HOST_GLSLANG=OFF"
                )
                targets += "flycast_libretro"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../core/CMakeLists.txt")
            version = "3.30.5"
        }
    }

    buildTypes {
        debug {
            // This module compiles the emulator itself. Keep the debug symbols,
            // but optimize the core so test APKs measure real emulation speed.
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DCMAKE_C_FLAGS_DEBUG=-O3 -g",
                        "-DCMAKE_CXX_FLAGS_DEBUG=-O3 -g"
                    )
                }
            }
        }
        release {
            externalNativeBuild {
                cmake {
                    arguments += listOf(
                        "-DCMAKE_C_FLAGS_RELWITHDEBINFO=-O3 -g -DNDEBUG",
                        "-DCMAKE_CXX_FLAGS_RELWITHDEBINFO=-O3 -g -DNDEBUG"
                    )
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
