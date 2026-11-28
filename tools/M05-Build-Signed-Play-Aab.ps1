# PersonalMedia M05e: clean ARM64-only, real-libtorrent, signed Google Play AAB.
# The upload keystore must be generated and stored OUTSIDE this project.
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
            throw 'Expected an unstripped little-endian ELF64 ARM64 shared library.'
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
            throw 'Invalid ELF program-header table.'
        }

        $loads = @()
        $relros = @()
        for ($index = 0; $index -lt $entryCount; $index++) {
            $headerOffset = [long]($programOffset + ([uint64]$index * $entrySize))
            $file.Position = $headerOffset
            $kind = $reader.ReadUInt32()
            $flags = $reader.ReadUInt32()
            $offset = $reader.ReadUInt64()
            $virtualAddress = $reader.ReadUInt64()
            $file.Position = $headerOffset + 40
            $memorySize = $reader.ReadUInt64()
            $alignment = $reader.ReadUInt64()
            if ($kind -eq 1) {
                if ($alignment -lt 16384 -or ($offset % 16384) -ne ($virtualAddress % 16384)) {
                    throw ("LOAD segment alignment {0} is not 16 KB compatible." -f $alignment)
                }
                $loads += [pscustomobject]@{ VirtualAddress = $virtualAddress; End = ($virtualAddress + $memorySize); Flags = $flags }
            } elseif ($kind -eq 0x6474e552) {
                $relros += [pscustomobject]@{ End = ($virtualAddress + $memorySize) }
            }
        }
        if ($loads.Count -eq 0) { throw 'No ELF LOAD segments found.' }

        # On 16 KB devices, an unaligned GNU_RELRO ending inside writable
        # LOAD data can make writable memory read-only at runtime.
        foreach ($relro in $relros) {
            if (($relro.End % 16384) -eq 0) { continue }
            $nextPage = $relro.End + (16384 - ($relro.End % 16384))
            foreach ($load in $loads) {
                if (($load.Flags -band 2) -ne 0 -and
                    $load.VirtualAddress -lt $nextPage -and $load.End -gt $relro.End) {
                    throw 'GNU_RELRO ends inside writable memory on a 16 KB page.'
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
$manifestPath = Join-Path $triplet 'PM_STR5C_BUILD_INFO.json'
foreach ($relative in @('PM_STR5C_BUILD_INFO.json', 'include\libtorrent\session.hpp',
                         'lib\libssl.a', 'lib\libcrypto.a')) {
    if (-not (Test-Path -LiteralPath (Join-Path $triplet $relative) -PathType Leaf)) {
        throw "Missing cloud-built ARM64 libtorrent dependency: $relative. Import STR5C files first."
    }
}
$torrentArchives = @(Get-ChildItem -LiteralPath (Join-Path $triplet 'lib') -Filter '*torrent*.a' -File)
$configs = @(Get-ChildItem -LiteralPath (Join-Path $triplet 'share') -Filter 'LibtorrentRasterbarConfig.cmake' -Recurse -File)
if ($torrentArchives.Count -eq 0 -or $configs.Count -eq 0) {
    throw 'Missing ARM64 libtorrent archive or CMake package configuration.'
}
$dependencyInfo = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($dependencyInfo.triplet -ne 'pm-arm64-android' -or
    $dependencyInfo.cxx_stl -ne 'c++_static' -or $dependencyInfo.android_api -ne 26) {
    throw 'STR5C native dependency metadata does not match the required ARM64/NDK ABI.'
}

# Fail fast; the optional MPV dependency is present in the user's known-good
# debug build and must not silently disappear from the Play release.
$mpvPath = Join-Path $project 'app\src\main\jniLibs\arm64-v8a\libmpv.so'
if (-not (Test-Path -LiteralPath $mpvPath -PathType Leaf)) {
    throw 'libmpv.so is not staged at app\src\main\jniLibs\arm64-v8a. Stage the licensed ARM64 MPV build first.'
}

if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    throw 'Set JAVA_HOME to your installed JDK 17 before running this script (see M05 README).'
}
$jarsigner = Join-Path $env:JAVA_HOME 'bin\jarsigner.exe'
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path -LiteralPath $jarsigner -PathType Leaf) -or
    -not (Test-Path -LiteralPath $java -PathType Leaf)) {
    throw 'JAVA_HOME must point to a full JDK (including jarsigner.exe and java.exe).'
}

$key = (Resolve-Path -LiteralPath $KeyStorePath -ErrorAction Stop).Path
$projectPrefix = $project.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
if ($key.StartsWith($projectPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Move the private upload keystore OUTSIDE the project before signing.'
}
if ([string]::IsNullOrWhiteSpace($KeyAlias)) { throw 'KeyAlias cannot be blank.' }

$oldDepsRoot = $env:PM_TORRENT_DEPS_ROOT
$env:PM_TORRENT_DEPS_ROOT = $deps
Push-Location $project
try {
    Write-Host 'M05e Play candidate: cleaning generated app output, testing and building ARM64 release AAB...' -ForegroundColor Cyan
    $gradleArgs = @(':app:clean', ':app:bundleRelease', '-PpmTorrent=true',
                    '-PpmTorrentAbis=arm64-v8a', "-PpmTorrentDepsRoot=$deps", '--stacktrace')
    if (-not $SkipTests) { $gradleArgs += ':app:testDebugUnitTest' }
    & '.\gradlew.bat' @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw 'Gradle release build failed. DO NOT upload a previous or stale bundle.' }

    $unsigned = Join-Path $project 'app\build\outputs\bundle\release\app-release.aab'
    if (-not (Test-Path -LiteralPath $unsigned -PathType Leaf)) {
        throw 'Gradle did not produce app-release.aab. DO NOT upload an old bundle.'
    }

    Add-Type -AssemblyName 'System.IO.Compression'
    Add-Type -AssemblyName 'System.IO.Compression.FileSystem'
    $temp = Join-Path ([System.IO.Path]::GetTempPath()) ('PersonalMedia-M05-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $temp -ErrorAction Stop | Out-Null
    try {
        $bundle = [System.IO.Compression.ZipFile]::OpenRead($unsigned)
        try {
            if ($null -eq $bundle.GetEntry('base/manifest/AndroidManifest.xml')) {
                throw 'AAB is missing its base manifest.'
            }
            $nativeEntries = @($bundle.Entries | Where-Object { $_.FullName -match '^base/lib/[^/]+/[^/]+\.so$' })
            if ($nativeEntries.Count -eq 0) { throw 'AAB has no native libraries.' }
            $required = @('base/lib/arm64-v8a/libpersonalmedia.so', 'base/lib/arm64-v8a/libmpv.so')
            foreach ($expected in $required) {
                if ($null -eq $bundle.GetEntry($expected)) { throw "Release AAB missing required native library: $expected" }
            }
            foreach ($entry in $nativeEntries) {
                if (-not $entry.FullName.StartsWith('base/lib/arm64-v8a/', [System.StringComparison]::Ordinal)) {
                    throw "AAB unexpectedly contains another ABI: $($entry.FullName)"
                }
                $localSo = Join-Path $temp ([System.IO.Path]::GetFileName($entry.FullName))
                [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $localSo)
                try {
                    Assert-Arm64Elf16K -Path $localSo
                    Write-Host "PASS 16 KB ARM64 ELF: $($entry.FullName)" -ForegroundColor Green
                } catch {
                    throw "Native library $($entry.FullName) failed 16 KB checks: $($_.Exception.Message)"
                }
            }
            Write-Host "PASS: release bundle has real ARM64 PersonalMedia + MPV and $($nativeEntries.Count) 16 KB-compatible native libraries." -ForegroundColor Green
        } finally { $bundle.Dispose() }
    } finally {
        # Only a uniquely generated, narrowly scoped temporary directory is removed.
        Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
    }

    $outDir = Join-Path $project 'dist\play-m05'
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $signed = Join-Path $outDir "PersonalMedia-Play-M05e-arm64-v200012-$stamp.aab"
    if (Test-Path -LiteralPath $signed) { throw 'Signed output name already exists; wait a second and rerun.' }
    $candidate = Join-Path $outDir "PersonalMedia-M05-signing-$([guid]::NewGuid().ToString('N')).candidate.aab"
    Copy-Item -LiteralPath $unsigned -Destination $candidate
    try {
        Write-Host 'Signing with your private UPLOAD key. Enter the password at the JDK prompt; it is not stored by this script.' -ForegroundColor Cyan
        & $jarsigner -keystore $key -sigalg SHA256withRSA -digestalg SHA-256 $candidate $KeyAlias
        if ($LASTEXITCODE -ne 0) { throw 'jarsigner failed: verify your upload-key alias and password.' }
        & $jarsigner -verify $candidate
        if ($LASTEXITCODE -ne 0) { throw 'Bundle signature verification failed.' }
        Move-Item -LiteralPath $candidate -Destination $signed -ErrorAction Stop
    } finally {
        if (Test-Path -LiteralPath $candidate) { Remove-Item -LiteralPath $candidate -Force }
    }

    $sha256 = (Get-FileHash -LiteralPath $signed -Algorithm SHA256).Hash
    Write-Host "SUCCESS: signed ARM64 Play AAB: $signed" -ForegroundColor Green
    Write-Host "SHA-256: $sha256" -ForegroundColor Green
    Write-Host 'Upload to Play Console > Testing > Internal testing first. This is NOT a production approval.' -ForegroundColor Yellow
} finally {
    Pop-Location
    $env:PM_TORRENT_DEPS_ROOT = $oldDepsRoot
}
