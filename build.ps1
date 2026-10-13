# Native tools (java/apksigner/gradle) write warnings to stderr. Under PowerShell 5.1
# an ErrorActionPreference of 'Stop' turns the first such write into a terminating
# NativeCommandError even when the tool exits 0. Keep 'Continue' and gate on $LASTEXITCODE.
$ErrorActionPreference = 'Continue'
$project = $PSScriptRoot
$python = if ($env:OMNICAM_PYTHON) { $env:OMNICAM_PYTHON } else { (Get-Command python -ErrorAction Stop).Source }
$version = ((Get-Content (Join-Path $project 'module/module.prop') | Where-Object { $_ -like 'version=*' }) -replace '^version=', '').Trim()
$apkName = "omnicam-aura-$version.apk"
$archive = Join-Path $project "OmniCam-Aura-$version-KSU.zip"
$gitBash = Join-Path $env:ProgramFiles 'Git/bin/bash.exe'
if (Test-Path -LiteralPath $gitBash) {
    & $gitBash -n (Join-Path $project 'module/post-fs-data.sh') (Join-Path $project 'module/post-mount.sh') (Join-Path $project 'module/service.sh')
    if ($LASTEXITCODE -ne 0) { throw 'module shell syntax failed' }
}
$drive = (70..90 | ForEach-Object { [char]$_ } | Where-Object { -not (Test-Path "$($_):\") } | Select-Object -Last 1)
if (-not $drive) { throw 'no free drive letter for the build' }
subst "$($drive):" $project
if ($LASTEXITCODE -ne 0) { throw 'drive mapping failed' }
try {
    Push-Location "$($drive):\"
    try {
        & .\gradlew.bat :app:assembleRelease --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'gradle build failed' }
    } finally { Pop-Location }
} finally { subst "$($drive):" /D }
# Old release APKs linger in module/ across version bumps and zip_module.py sweeps the whole
# directory into the archive, so a 1.3.1 package would silently ship the 1.3.0 APK as well (which
# verify_aura.py then rejects). Drop every Aura APK first: the archive may only ever hold the one
# we are about to build.
Get-ChildItem -LiteralPath (Join-Path $project 'module') -Filter 'omnicam-aura-*.apk' -File |
    Remove-Item -Force -ErrorAction Stop
Copy-Item -LiteralPath (Join-Path $project 'app/build/outputs/apk/release/app-release.apk') -Destination (Join-Path $project "module/$apkName") -ErrorAction Stop
& $python (Join-Path $project 'tools/zip_module.py') (Join-Path $project 'module') $archive
if ($LASTEXITCODE -ne 0) { throw 'module packaging failed' }
& $python (Join-Path $project 'tools/verify_aura.py')
if ($LASTEXITCODE -ne 0) { throw 'verification failed' }
Get-Item (Join-Path $project "module/$apkName"), $archive | Select-Object FullName, Length
