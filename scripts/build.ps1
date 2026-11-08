param([string[]]$Tasks = @('testDebugUnitTest', 'lintDebug', 'packageDebugApk'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$env:TEMP = Join-Path $projectRoot 'tmp/work'
$env:TMP = $env:TEMP
$env:TMPDIR = $env:TEMP
$env:GRADLE_USER_HOME = Join-Path $projectRoot 'tmp/gradle-home'
$env:ANDROID_USER_HOME = Join-Path $projectRoot 'tmp/android-user'
@($env:TEMP, $env:GRADLE_USER_HOME, $env:ANDROID_USER_HOME, "$projectRoot/tmp/home", "$projectRoot/tmp/keys") |
    ForEach-Object { New-Item -ItemType Directory -Path $_ -Force | Out-Null }
if (-not $env:JAVA_HOME -or -not $env:ANDROID_HOME) {
    throw 'Set JAVA_HOME to JDK 17 or 21 and ANDROID_HOME to an Android SDK with platform 34.'
}
& ./gradlew.bat --no-daemon --project-cache-dir "$projectRoot/tmp/gradle-project" `
    "-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=$env:TEMP -Duser.home=$projectRoot/tmp/home" `
    "-Pkotlin.project.persistent.dir=$projectRoot/tmp/kotlin" @Tasks
exit $LASTEXITCODE
