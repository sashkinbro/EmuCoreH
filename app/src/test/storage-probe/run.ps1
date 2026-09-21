param(
    [Parameter(Mandatory=$true)][string]$Sdk,
    [Parameter(Mandatory=$true)][string]$Demos,
    [Parameter(Mandatory=$true)][string]$Fixtures,
    [string]$BuildTools='37.0.0',
    [string]$Platform='android-37.0'
)
$ErrorActionPreference='Stop'
$project=(Resolve-Path "$PSScriptRoot/../../../..").Path
$work="$project/build/storage-probe"
$androidJar="$Sdk/platforms/$Platform/android.jar"
New-Item -ItemType Directory -Force "$work/classes","$work/dex" | Out-Null
function CheckExit { if($LASTEXITCODE -ne 0) { throw "Validation command failed: $LASTEXITCODE" } }
& "$env:JAVA_HOME/bin/javac.exe" -classpath $androidJar -d "$work/classes" "$PSScriptRoot/Probe.java" "$PSScriptRoot/FixtureProvider.java"
CheckExit
& "$Sdk/build-tools/$BuildTools/d8.bat" --lib $androidJar --output "$work/dex" "$work/classes/probe/Probe.class" "$work/classes/probe/FixtureProvider.class"
CheckExit
& "$Sdk/build-tools/$BuildTools/aapt2.exe" link -I $androidJar --manifest "$PSScriptRoot/AndroidManifest.xml" -o "$work/probe.apk"
CheckExit
python "$PSScriptRoot/package.py" --apk "$project/app/build/outputs/apk/debug/app-debug.apk" --work $work --demos $Demos --fixtures $Fixtures
CheckExit
& "$Sdk/build-tools/$BuildTools/apksigner.bat" sign --ks "$env:USERPROFILE/.android/debug.keystore" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android "$work/probe.apk"
CheckExit
& "$Sdk/platform-tools/adb.exe" install -r "$work/probe.apk"
CheckExit
$result = & "$Sdk/platform-tools/adb.exe" shell am instrument -w com.sbro.emucorea.probe/probe.Probe
CheckExit
$result | Write-Output
if (($result -join "`n") -notmatch "STORAGE_PROBE_PASS") { throw "Storage/preview probe did not pass" }
