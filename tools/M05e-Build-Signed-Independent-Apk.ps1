# PersonalMedia M05e: independent ARM64 APK for direct installation / GitHub Releases.
# Requires your existing native dependency import and PRIVATE signing key outside this repo.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$KeyStorePath,
    [string]$KeyAlias = 'personalmedia-upload',
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Assert-Arm64Elf16K {
    param([Parameter(Mandatory = $true)][string]$Path)
    $file = [System.IO.File]::OpenRead($Path)
    $reader = New-Object System.IO.BinaryReader($file)
    try {
        $ident = $reader.ReadBytes(6)
        if ($ident.Length -ne 6 -or $ident[0] -ne 127 -or $ident[1] -ne 69 -or
            $ident[2] -ne 76 -or $ident[3] -ne 70 -or $ident[4] -ne 2 -or $ident[5] -ne 1) {
            throw 'Expected little-endian ELF64.'
        }
        $file.Position = 18
        if ($reader.ReadUInt16() -ne 183) { throw 'Expected AArch64 (ELF machine 183).' }
        $file.Position = 32
        $programOffset = $reader.ReadUInt64()
        $file.Position = 54
        $entrySize = $reader.ReadUInt16()
        $entryCount = $reader.ReadUInt16()
        if ($entrySize -lt 56 -or $entryCount -eq 0 -or
            $programOffset -gt [uint64]$file.Length -or
            ($programOffset + ([uint64]$entrySize * $entryCount)) -gt [uint64]$file.Length) {
            throw 'Invalid ELF program header table.'
        }
        $loads = @()
        $relros = @()
        for ($i = 0; $i -lt $entryCount; $i++) {
            $header = [long]($programOffset + ([uint64]$i * $entrySize))
            $file.Position = $header
            $kind = $reader.ReadUInt32()
            $flags = $reader.ReadUInt32()
            $offset = $reader.ReadUInt64()
            $virtualAddress = $reader.ReadUInt64()
            $file.Position = $header + 40
            $memorySize = $reader.ReadUInt64()
            $alignment = $reader.ReadUInt64()
            if ($kind -eq 1) {
                if ($alignment -lt 16384 -or ($offset % 16384) -ne ($virtualAddress % 16384)) {
                    throw 'ELF LOAD segment is not 16 KB aligned.'
                }
                $loads += [pscustomobject]@{ Start = $virtualAddress; End = ($virtualAddress + $memorySize); Flags = $flags }
            } elseif ($kind -eq 0x6474e552) {
                $relros += [pscustomobject]@{ End = ($virtualAddress + $memorySize) }
            }
        }
        if ($loads.Count -eq 0) { throw 'ELF has no LOAD segments.' }
        foreach ($relro in $relros) {
            if (($relro.End % 16384) -eq 0) { continue }
            $nextPage = $relro.End + (16384 - ($relro.End % 16384))
            foreach ($load in $loads) {
                if (($load.Flags -band 2) -ne 0 -and
                    $load.Start -lt $nextPage -and $load.End -gt $relro.End) {
                    throw 'ELF GNU_RELRO overlaps writable data at 16 KB page size.'
                }
            }
        }
    } finally {
        $reader.Dispose()
    }
}

$project = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$deps = Join-Path $project 'native-deps\installed'
$triplet = Join-Path $deps 'pm-arm64-android'
foreach ($required in @('PM_STR5C_BUILD_INFO.json', 'include\libtorrent\session.hpp',
                        'lib\libssl.a', 'lib\libcrypto.a')) {
    if (-not (Test-Path -LiteralPath (Join-Path $triplet $required) -PathType Leaf)) {
        throw "Missing required ARM64 native dependency: $required"
    }
}
if (@(Get-ChildItem -LiteralPath (Join-Path $triplet 'lib') -Filter '*torrent*.a' -File).Count -eq 0 -or
    @(Get-ChildItem -LiteralPath (Join-Path $triplet 'share') -Filter 'LibtorrentRasterbarConfig.cmake' -Recurse -File).Count -eq 0) {
    throw 'Missing libtorrent static library or CMake package.'
}
$info = Get-Content -LiteralPath (Join-Path $triplet 'PM_STR5C_BUILD_INFO.json') -Raw | ConvertFrom-Json
if ($info.triplet -ne 'pm-arm64-android' -or $info.cxx_stl -ne 'c++_static' -or $info.android_api -ne 26) {
    throw 'ARM64 libtorrent metadata does not match this build.'
}
if (-not (Test-Path -LiteralPath (Join-Path $project 'app\src\main\jniLibs\arm64-v8a\libmpv.so') -PathType Leaf)) {
    throw 'Missing ARM64 libmpv.so. Stage your licensed MPV build first.'
}
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME) -or
    -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe') -PathType Leaf)) {
    throw 'Set JAVA_HOME to your installed JDK 17.'
}
$key = (Resolve-Path -LiteralPath $KeyStorePath -ErrorAction Stop).Path
$projectPrefix = $project.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
if ($key.StartsWith($projectPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Keep the private signing keystore outside the project/repository.'
}
if ([string]::IsNullOrWhiteSpace($KeyAlias)) { throw 'KeyAlias cannot be blank.' }

$gradleFile = Get-Content -LiteralPath (Join-Path $project 'app\build.gradle.kts') -Raw
if ($gradleFile -notmatch 'versionCode\s*=\s*200012\b' -or
    $gradleFile -notmatch 'versionName\s*=\s*"20\.0\.12-android-play-m05e"') {
    throw 'This script only releases the tested M05e v200012 source. Check app/build.gradle.kts.'
}

$sdkCandidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
    $sdkCandidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
}
$toolset = $null
foreach ($sdk in $sdkCandidates) {
    if ([string]::IsNullOrWhiteSpace($sdk) -or -not (Test-Path -LiteralPath (Join-Path $sdk 'build-tools'))) { continue }
    $versions = @(Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory | Sort-Object {
        try { [version]($_.Name -replace '-.*$', '') } catch { [version]'0.0' }
    } -Descending)
    foreach ($dir in $versions) {
        $zipalign = Join-Path $dir.FullName 'zipalign.exe'
        $apksigner = Join-Path $dir.FullName 'apksigner.bat'
        $aapt = Join-Path $dir.FullName 'aapt.exe'
        if ((Test-Path -LiteralPath $zipalign) -and (Test-Path -LiteralPath $apksigner) -and
            (Test-Path -LiteralPath $aapt)) {
            $toolset = [pscustomobject]@{ Zipalign = $zipalign; Apksigner = $apksigner; Aapt = $aapt }
            break
        }
    }
    if ($null -ne $toolset) { break }
}
if ($null -eq $toolset) { throw 'Android SDK Build Tools 35+ with zipalign.exe, apksigner.bat and aapt.exe are required.' }

$outDir = Join-Path $project 'dist\github-m05e'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$output = Join-Path $outDir "PersonalMedia-M05e-arm64-v200012-$stamp.apk"
$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ('PersonalMedia-Independent-' + [guid]::NewGuid().ToString('N'))
$oldDepsRoot = $env:PM_TORRENT_DEPS_ROOT
$env:PM_TORRENT_DEPS_ROOT = $deps

Push-Location $project
try {
    Write-Host 'Building M05e independent ARM64 APK with real libtorrent and MPV...' -ForegroundColor Cyan
    $gradleArgs = @(':app:clean', ':app:assembleRelease', '-PpmTorrent=true',
              '-PpmTorrentAbis=arm64-v8a', "-PpmTorrentDepsRoot=$deps", '--stacktrace')
    if (-not $SkipTests) { $gradleArgs += ':app:testDebugUnitTest' }
    & '.\gradlew.bat' @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw 'Gradle failed. Do NOT distribute a stale APK.' }

    $unsigned = Join-Path $project 'app\build\outputs\apk\release\app-release-unsigned.apk'
    if (-not (Test-Path -LiteralPath $unsigned -PathType Leaf)) {
        throw 'Expected an unsigned release APK. Do not sign a debug or stale build.'
    }

    Add-Type -AssemblyName 'System.IO.Compression'
    Add-Type -AssemblyName 'System.IO.Compression.FileSystem'
    New-Item -ItemType Directory -Path $tempDir -ErrorAction Stop | Out-Null
    $apk = [System.IO.Compression.ZipFile]::OpenRead($unsigned)
    try {
        $native = @($apk.Entries | Where-Object { $_.FullName -match '^lib/[^/]+/[^/]+\.so$' })
        if ($native.Count -eq 0) { throw 'Release APK has no native libraries.' }
        foreach ($name in @('lib/arm64-v8a/libpersonalmedia.so', 'lib/arm64-v8a/libmpv.so')) {
            if ($null -eq $apk.GetEntry($name)) { throw "Release APK missing required $name" }
        }
        foreach ($entry in $native) {
            if (-not $entry.FullName.StartsWith('lib/arm64-v8a/', [System.StringComparison]::Ordinal)) {
                throw "Unexpected native ABI in release APK: $($entry.FullName)"
            }
            $library = Join-Path $tempDir ([System.IO.Path]::GetFileName($entry.FullName))
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $library)
            try { Assert-Arm64Elf16K -Path $library }
            catch { throw "$($entry.FullName) failed 16 KB ELF validation: $($_.Exception.Message)" }
            Write-Host "PASS 16 KB ARM64 ELF: $($entry.FullName)" -ForegroundColor Green
        }
    } finally { $apk.Dispose() }

    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    if (Test-Path -LiteralPath $output) { throw 'Output name already exists. Retry in one second.' }
    $aligned = Join-Path $tempDir 'aligned.apk'
    $candidate = Join-Path $tempDir 'signed.apk'
    & $toolset.Zipalign -P 16 -f -v 4 $unsigned $aligned
    if ($LASTEXITCODE -ne 0) { throw 'zipalign failed.' }
    Write-Host 'Signing locally. Enter your PRIVATE keystore password at the apksigner prompt.' -ForegroundColor Cyan
    & $toolset.Apksigner sign --ks $key --ks-key-alias $KeyAlias --out $candidate $aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    & $toolset.Apksigner verify --verbose --print-certs $candidate
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    & $toolset.Zipalign -c -P 16 -v 4 $candidate
    if ($LASTEXITCODE -ne 0) { throw 'Final APK ZIP alignment validation failed.' }
    $badging = @(& $toolset.Aapt dump badging $candidate)
    if ($LASTEXITCODE -ne 0) { throw 'Android manifest inspection failed.' }
    $packageLine = @($badging | Where-Object { $_ -match '^package:' } | Select-Object -First 1)
    if ($packageLine.Count -ne 1 -or
        $packageLine[0] -notmatch "name='com\.personalmedia\.android'" -or
        $packageLine[0] -notmatch "versionCode='200012'" -or
        $packageLine[0] -notmatch "versionName='20\.0\.12-android-play-m05e'") {
        throw 'APK package or M05e version does not match the release identity.'
    }
    Move-Item -LiteralPath $candidate -Destination $output -ErrorAction Stop
    $hash = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash
    Write-Host "SUCCESS: signed independent ARM64 APK: $output" -ForegroundColor Green
    Write-Host "SHA-256: $hash" -ForegroundColor Green
    Write-Host 'Test this EXACT APK before attaching it to a GitHub Release.' -ForegroundColor Yellow
    Write-Host 'CAUTION: Google Play may use a different app signing certificate. A Play install may reject an update from this APK.' -ForegroundColor Yellow
} finally {
    Pop-Location
    $env:PM_TORRENT_DEPS_ROOT = $oldDepsRoot
    if (Test-Path -LiteralPath $tempDir) { Remove-Item -LiteralPath $tempDir -Recurse -Force -ErrorAction SilentlyContinue }
}
