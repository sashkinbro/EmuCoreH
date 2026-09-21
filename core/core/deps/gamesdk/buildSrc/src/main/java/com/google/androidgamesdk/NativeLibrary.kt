package com.google.androidgamesdk

/**
 * Describe a native library that can be compiled with CMake and that can
 * have one or more sample or related Android project.
 *
 * @see ExternalAndroidProject
 */
data class NativeLibrary(
    val libraryInfo: LibraryInfo
) {
    val nativeLibraryName: String = libraryInfo.nickname
    val aarLibraryName: String = libraryInfo.jetpackName
    val aarVersion: String = libraryInfo.jetpackVersion()
    var hybridProjectDir: String = libraryInfo.projectName ?: ""
    var sampleAndroidProjects: ArrayList<ExternalAndroidProject> = ArrayList()
        private set
    var sampleExtraFolderPaths: ArrayList<SampleFolder> = ArrayList()
        private set
    var minimumAndroidApiLevel: Int? = null
        private set
    var minimumNdkVersion: Int? = null
        private set
    var supportedStlVersions: List<String>? = null
        private set
    var isHybridLibrary = false
        private set
    var isThirdParty = false
        private set
    var usesProtobuf = false
        private set
    var usesTensorflow = false
        private set
    var assetsDirectory: String? = null
        private set

    fun addSampleAndroidProject(
        sampleAndroidProject: ExternalAndroidProject
    ): NativeLibrary {
        sampleAndroidProjects.add(sampleAndroidProject)
        return this
    }

    fun addSampleExtraFolder(
        sampleFolder: SampleFolder
    ): NativeLibrary {
        sampleExtraFolderPaths.add(sampleFolder)
        return this
    }

    fun setHybridLibrary(): NativeLibrary {
        this.isHybridLibrary = true
        return this
    }
    /* End hybrid project setters */

    fun setMinimumAndroidApiLevel(
        minimumAndroidApiLevel: Int?
    ): NativeLibrary {
        this.minimumAndroidApiLevel = minimumAndroidApiLevel
        return this
    }

    fun setMinimumNdkVersion(minimumNdkVersion: Int?): NativeLibrary {
        this.minimumNdkVersion = minimumNdkVersion
        return this
    }

    fun setSupportedStlVersions(
        supportedStlVersions: List<String>?
    ): NativeLibrary {
        this.supportedStlVersions = supportedStlVersions
        return this
    }

    fun setThirdParty(): NativeLibrary {
        this.isThirdParty = true
        return this
    }

    fun setUsesProtobuf(): NativeLibrary {
        this.usesProtobuf = true
        return this
    }

    fun setUsesTensorflow(): NativeLibrary {
        this.usesTensorflow = true
        return this
    }

    fun setAssetsDirectory(
      assetsDirectory: String?
    ): NativeLibrary {
      this.assetsDirectory = assetsDirectory
      return this
    }

    data class SampleFolder(
        val sourcePath: String,
        val destinationPath: String
    ) {
        constructor(sourcePath: String) : this(sourcePath, sourcePath)

        var includePattern: String? = null
            private set

        fun include(includePattern: String): SampleFolder {
            this.includePattern = includePattern

            return this
        }
    }
}

