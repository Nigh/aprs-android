[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet("build", "release", "test", "install", "run", "adb")]
    [string]$Command,

    [Parameter(Position = 1, ValueFromRemainingArguments = $true)]
    [string[]]$AdbArgs
)

$ErrorActionPreference = "Stop"

$ProjectDir = $PSScriptRoot
$GradleDir = Join-Path $ProjectDir ".gradle-docker"
$AndroidDir = Join-Path $HOME ".android"
$Image = if ($env:ANDROID_DEV_IMAGE) { $env:ANDROID_DEV_IMAGE } else { "xianii/android-dev:latest" }
$Package = "com.nigh.aprstx"
$Activity = "$Package/.MainActivity"
$DebugApk = "/workspace/app/build/outputs/apk/debug/app-debug.apk"
$GradleLauncher = @("java", "-Xmx64m", "-Xms64m", "-classpath", "gradle/wrapper/gradle-wrapper.jar", "org.gradle.wrapper.GradleWrapperMain")

New-Item -ItemType Directory -Force -Path $GradleDir, $AndroidDir | Out-Null

function Invoke-Wslc {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    & wslc @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "wslc exited with code $LASTEXITCODE"
    }
}

function Get-BuildContainerArgs {
    return @(
        "run", "--rm",
        "--volume", "${ProjectDir}:/workspace",
        "--volume", "${AndroidDir}:/root/.android",
        "--env", "GRADLE_USER_HOME=/workspace/.gradle-docker",
        "--workdir", "/workspace"
    )
}

function Invoke-Build {
    Write-Host "==> Building debug..."
    Invoke-Wslc ((Get-BuildContainerArgs) + @($Image) + $GradleLauncher + @("assembleDebug"))
}

function Invoke-Release {
    $ReleaseEnv = Join-Path $ProjectDir "keystore/release.env"
    if (-not (Test-Path -LiteralPath $ReleaseEnv -PathType Leaf)) {
        throw "keystore/release.env not found - run keytool first (see AGENTS.md)."
    }

    Write-Host "==> Building release (signed)..."
    Invoke-Wslc ((Get-BuildContainerArgs) + @("--env-file", $ReleaseEnv, $Image) + $GradleLauncher + @("assembleRelease"))
}

function Invoke-Test {
    Write-Host "==> Unit tests..."
    Invoke-Wslc ((Get-BuildContainerArgs) + @($Image) + $GradleLauncher + @("test"))
}

function Invoke-AdbContainer {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    $AdbInit = @'
mkdir -p "$HOME/.android"
if [ ! -s "$HOME/.android/adbkey" ]; then
    adb keygen "$HOME/.android/adbkey"
    chmod 600 "$HOME/.android/adbkey"
    echo "==> New adb key in ~/.android - authorize once on device (Always allow)."
fi
adb start-server >/dev/null 2>&1
exec adb "$@"
'@

    $ContainerArgs = Get-BuildContainerArgs
    Invoke-Wslc ($ContainerArgs + @($Image, "bash", "-c", $AdbInit, "bash") + $Arguments)
}

function Invoke-Install {
    $InstallScript = @"
mkdir -p \"`$HOME/.android\"
if [ ! -s \"`$HOME/.android/adbkey\" ]; then
    adb keygen \"`$HOME/.android/adbkey\"
    chmod 600 \"`$HOME/.android/adbkey\"
    echo \"==> New adb key in ~/.android - authorize once on device (Always allow).\"
fi
adb start-server >/dev/null 2>&1
echo '==> Waiting for device...'
for i in `$(seq 1 90); do
    state=`$(adb get-state 2>/dev/null || true)
    if [ \"`$state\" = device ]; then break; fi
    if [ \"`$i\" = 90 ]; then
        echo 'ERROR: device not authorized or not connected' >&2
        exit 1
    fi
    sleep 1
done
if adb shell pm path '$Package' >/dev/null 2>&1; then
    echo '==> Removing existing install...'
    adb uninstall '$Package'
fi
echo '==> Installing...'
adb install -r -t '$DebugApk'
echo '==> Launching...'
adb shell am start -n '$Activity'
echo '==> Done.'
"@

    Invoke-Wslc ((Get-BuildContainerArgs) + @($Image, "bash", "-c", $InstallScript))
}

switch ($Command) {
    "build"   { Invoke-Build }
    "release" { Invoke-Release }
    "test"    { Invoke-Test }
    "install" { Invoke-Install }
    "run"     { Invoke-Build; Invoke-Install }
    "adb" {
        if (-not $AdbArgs) {
            throw "Usage: .\build.ps1 adb <adb-args...> (for example: .\build.ps1 adb devices)"
        }
        Invoke-AdbContainer $AdbArgs
    }
    default { Invoke-Build; Invoke-Install }
}
