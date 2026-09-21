package com.google.androidgamesdk

/**
 * Describe a Android Library project (that will create an AAR), optionally
 * with a prefab folder to include in the AAR (useful as we patch AAR manually
 * to include prefab - can be removed once we use prefabPublishing).
 */
data class AndroidArchiveLibrary(
    val libraryInfo: LibraryInfo
) {
    val nativeLibraryName: String = libraryInfo.nickname
    val aarLibraryName: String = libraryInfo.jetpackName
    val aarVersion: String = libraryInfo.jetpackVersion()
    val projectName: String = libraryInfo.projectName ?: "UnknownProject"
    var prefabFolderName: String = ""
        private set

    fun setPrefabFolderName(
        prefabFolderName: String
    ): AndroidArchiveLibrary {
        this.prefabFolderName = prefabFolderName

        return this
    }
}
