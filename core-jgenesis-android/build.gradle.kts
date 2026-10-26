import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.gradle.api.file.FileSystemOperations
import javax.inject.Inject
import java.io.File

plugins { alias(libs.plugins.android.library) }

abstract class BuildJGenesis @Inject constructor(
    private val processes: ExecOperations,
    private val files: FileSystemOperations,
) : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rustSources: ConfigurableFileCollection
    @get:Internal abstract val sourceDirectory: DirectoryProperty
    @get:Internal abstract val ndkDirectory: DirectoryProperty
    @get:Input abstract val enableDiagnostics: Property<Boolean>
    @get:Input abstract val rustToolchain: Property<String>
    @get:Input abstract val ndkVersion: Property<String>
    @get:Input abstract val hostTag: Property<String>
    @get:Input abstract val hostToolsDirectory: Property<String>
    @get:LocalState abstract val cargoTargetDirectory: DirectoryProperty
    @get:OutputDirectory abstract val jniDirectory: DirectoryProperty
    @get:OutputDirectory abstract val assetsDirectory: DirectoryProperty

    @TaskAction fun build() {
        val windows = hostTag.get().startsWith("windows")
        val bin = ndkDirectory.file("toolchains/llvm/prebuilt/${hostTag.get()}/bin").get().asFile
        check(bin.isDirectory) { "Install Android NDK ${ndkVersion.get()} (missing $bin)" }
        val compiler = bin.resolve("aarch64-linux-android26-clang" + if (windows) ".cmd" else "")
        val archiver = bin.resolve("llvm-ar" + if (windows) ".exe" else "")
        val path = listOf(hostToolsDirectory.get(), bin.absolutePath, System.getenv("PATH").orEmpty())
            .filter { it.isNotBlank() }.joinToString(File.pathSeparator)
        processes.exec {
            workingDir(sourceDirectory)
            commandLine("cargo", "+${rustToolchain.get()}", "build", "--locked", "--release", "--no-default-features", "--target", "aarch64-linux-android", "-p", "jgenesis-libretro")
            environment("PATH", path)
            environment("CARGO_TARGET_DIR", cargoTargetDirectory.get().asFile.absolutePath)
            environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", compiler.absolutePath)
            environment("CC_aarch64_linux_android", compiler.absolutePath)
            environment("AR_aarch64_linux_android", archiver.absolutePath)
            // Android's arm64 ABI includes ARMv8-A devices without LSE, FP16 or
            // dot-product support. Keep the baseline ISA; a CPU scheduling target
            // such as cortex-a55 also enables optional instructions globally.
            // Keep optimized emulation for debug APKs, but isolate all diagnostic
            // cfgs and artifacts from the shipping release DSO.
            val debugAssertions = if (enableDiagnostics.get()) "yes" else "no"
            environment("RUSTFLAGS", "-C debug-assertions=$debugAssertions -C target-cpu=generic -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384 -C link-arg=-Wl,-soname,libjgenesis_libretro.so")
        }
        files.copy {
            from(cargoTargetDirectory.file("aarch64-linux-android/release/libjgenesis_libretro.so"))
            into(jniDirectory.dir("arm64-v8a"))
        }
        files.copy {
            from(sourceDirectory.file("LICENSE"), sourceDirectory.file("UPSTREAM.txt"), sourceDirectory.file("README-ANDROID.md"))
            into(assetsDirectory.dir("jgenesis"))
        }
    }
}

val ndkRevision = "29.0.14206865"
val windowsHost = System.getProperty("os.name").startsWith("Windows")
val hostTagValue = if (windowsHost) "windows-x86_64" else if (System.getProperty("os.name").startsWith("Mac")) "darwin-x86_64" else "linux-x86_64"

android {
    namespace = "com.sbro.emucoreh.core.jgenesis"
    compileSdk { version = release(37) }
    ndkVersion = ndkRevision
    defaultConfig { minSdk = 26; ndk { abiFilters += "arm64-v8a" } }
}

// Compatibility aggregate; normal Android variant builds depend only on their
// own task, never on the other variant's diagnostics or cached native outputs.
val buildRust = tasks.register("buildRust") {
    group = "build"
    description = "Build isolated optimized Jgenesis cores for each Android variant"
}

androidComponents.onVariants { variant ->
    val variantName = variant.name
    val capitalizedName = variantName.replaceFirstChar { it.uppercaseChar() }
    val buildVariantRust = tasks.register<BuildJGenesis>("build${capitalizedName}Rust") {
        group = "build"
        description = "Build the optimized $variantName jgenesis core for arm64 Android"
        enableDiagnostics.set(variant.buildType == "debug")
        sourceDirectory.set(layout.projectDirectory.dir("../core-jgenesis"))
        rustSources.from(fileTree(sourceDirectory) { exclude("target/**", ".git/**"); include("**/*.rs", "**/*.toml", "Cargo.lock", "**/*.bin", "LICENSE", "UPSTREAM.txt", "README-ANDROID.md") })
        ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory)
        ndkVersion.set(ndkRevision)
        hostTag.set(hostTagValue)
        rustToolchain.set(providers.gradleProperty("jgenesis.rustToolchain").orElse(if (windowsHost) "1.98.1-x86_64-pc-windows-gnu" else "1.98.1"))
        hostToolsDirectory.set(providers.gradleProperty("jgenesis.hostToolsDir").orElse(if (windowsHost) "C:/msys64/ucrt64/bin" else ""))
        cargoTargetDirectory.set(layout.buildDirectory.dir("rust/$variantName"))
        jniDirectory.set(layout.buildDirectory.dir("generated/jniLibs/$variantName"))
        assetsDirectory.set(layout.buildDirectory.dir("generated/assets/$variantName"))
    }
    buildRust.configure { dependsOn(buildVariantRust) }
    variant.sources.jniLibs?.addGeneratedSourceDirectory(buildVariantRust, BuildJGenesis::jniDirectory)
    variant.sources.assets?.addGeneratedSourceDirectory(buildVariantRust, BuildJGenesis::assetsDirectory)
}
