# Builds the downloadable mfrg archives into dist/:
#   mfrg.zip          - portable, needs Java 17 or newer (Windows, macOS, Linux)
#   mfrg-windows.zip  - the same with a trimmed Java runtime inside, nothing else to install
#
# Run from the repository root:  powershell -File tools/package-mfrg.ps1
# JAVA_HOME must point to a JDK 17 (it provides jlink).

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not $env:JAVA_HOME) { throw 'JAVA_HOME is not set (a JDK 17 is needed for jlink)' }
$jlink = Join-Path $env:JAVA_HOME 'bin\jlink.exe'
if (-not (Test-Path $jlink)) { throw "jlink not found in $env:JAVA_HOME" }

& "$root\gradlew.bat" :tools:packer:installDist --console=plain -q
if ($LASTEXITCODE -ne 0) { throw 'gradle failed' }

$install = Join-Path $root 'tools\packer\build\install\mfrg'
$dist = Join-Path $root 'dist'
New-Item -ItemType Directory -Force $dist | Out-Null

# Portable archive: the folder named mfrg with bin/ and lib/.
$portable = Join-Path $dist 'mfrg.zip'
if (Test-Path $portable) { Remove-Item $portable }
Compress-Archive -Path $install -DestinationPath $portable

# Windows archive with its own runtime.
$stage = Join-Path $env:TEMP ('mfrg-stage-' + [guid]::NewGuid())
$target = Join-Path $stage 'mfrg'
Copy-Item -Recurse $install $target
$modules = 'java.base,java.logging,java.net.http,java.xml,java.naming,java.sql,java.desktop,java.management,jdk.crypto.ec,jdk.unsupported,jdk.zipfs'
& $jlink --add-modules $modules --strip-debug --no-header-files --no-man-pages --compress=2 --output (Join-Path $target 'runtime')
if ($LASTEXITCODE -ne 0) { throw 'jlink failed' }

# The start script of Gradle honours JAVA_HOME, so the launcher points it at the bundled runtime.
$launcher = @'
@echo off
set "JAVA_HOME=%~dp0..\runtime"
call "%~dp0mfrg.bat" %*
'@
Set-Content -Path (Join-Path $target 'bin\mfrg.cmd') -Value $launcher -Encoding ascii

$windows = Join-Path $dist 'mfrg-windows.zip'
if (Test-Path $windows) { Remove-Item $windows }
Compress-Archive -Path $target -DestinationPath $windows
Remove-Item -Recurse -Force $stage

Get-ChildItem $dist\mfrg*.zip | ForEach-Object { '{0}  {1:N1} MB' -f $_.Name, ($_.Length / 1MB) }
