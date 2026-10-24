param(
    [string]$Toolchain = $(if ($IsWindows -or $env:OS -eq 'Windows_NT') { '1.98.1-x86_64-pc-windows-gnu' } else { '1.98.1' })
)

$ErrorActionPreference = 'Stop'
$rustRoot = Join-Path $PSScriptRoot '../../..'
$previousPath = $env:PATH
try {
    if (Test-Path 'C:/msys64/ucrt64/bin') {
        $env:PATH = 'C:/msys64/ucrt64/bin' + [IO.Path]::PathSeparator + $env:PATH
    }
    Push-Location $rustRoot
    try {
        # Run the shipping optimization profile, including the actual libretro
        # initialization/error/panic paths in an isolated child process.
        & cargo "+$Toolchain" test --locked --release -p jgenesis-libretro -- --test-threads=1
        if ($LASTEXITCODE -ne 0) { throw "Rust release regression tests failed ($LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
} finally {
    $env:PATH = $previousPath
}
