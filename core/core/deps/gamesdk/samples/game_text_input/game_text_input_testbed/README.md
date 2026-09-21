# Game Text Input Testbed sample

Note: this sample must be used with the Game Text Input library built from sources.
We'll publish later prebuilt AARs of Game Text Input.

## Run the sample after building Game Text Input from sources

* First, build the GameTextInput AAR: `cd <top-level-gamesdk-dir> && ./gradlew packageLocalZip -Plibraries=game_text_input`.
* Open and run the sample project in Android Studio

Note: the latest version of Prefab must be used to fix a problem in previous versions with headers only libraries. The version is specified in `gradle.properties` so you should not have any issue. Make sure to use a recent version of Android Studio (4.1+).