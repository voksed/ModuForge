# Installs mfrg (with its own Java) for the current user and adds it to PATH.
#   irm https://raw.githubusercontent.com/voksed/ModuForge/main/tools/install.ps1 | iex
$ErrorActionPreference = 'Stop'
$target = Join-Path $env:LOCALAPPDATA 'mfrg'
$zip = Join-Path $env:TEMP 'mfrg-windows.zip'
$url = 'https://github.com/voksed/ModuForge/releases/latest/download/mfrg-windows.zip'

Write-Host "Downloading $url"
Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
if (Test-Path $target) { Remove-Item -Recurse -Force $target }
Expand-Archive -Path $zip -DestinationPath $env:LOCALAPPDATA -Force
Remove-Item $zip

$bin = Join-Path $target 'bin'
$path = [Environment]::GetEnvironmentVariable('Path', 'User')
if (($path -split ';') -notcontains $bin) {
    [Environment]::SetEnvironmentVariable('Path', ($path.TrimEnd(';') + ';' + $bin), 'User')
}
Write-Host "Installed to $target. Open a new terminal and run: mfrg"
