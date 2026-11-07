# Build Goldishka Loader as a portable onedir folder with PyInstaller.
# Requirements: Python 3.10+, PyQt5, PyInstaller, Pillow (for the icon).

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

python -c "import PyInstaller" 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host "PyInstaller is not installed. Run: python -m pip install pyinstaller" -ForegroundColor Yellow
    exit 1
}

$name = "Goldishka Loader"
$target = "dist\$name"
if (Get-Process -Name $name -ErrorAction SilentlyContinue) {
    Write-Host "Close $name.exe before building." -ForegroundColor Yellow
    exit 1
}

if (-not (Test-Path "assets\icon.ico")) {
    python -c "from PIL import Image; Image.open(r'assets/icon.png').save(r'assets/icon.ico', sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])"
}

$assets = Join-Path $PSScriptRoot "assets"
$entry = Join-Path $PSScriptRoot "Goldishka Loader.pyw"
$icon = Join-Path $assets "icon.ico"

# Build into a staging folder so the user's game\ and config.json in dist are preserved.
$stage = "build\pyi-dist"
python -m PyInstaller --noconfirm --clean --onedir --windowed --name $name --icon $icon --add-data "$assets;assets" --paths $PSScriptRoot --distpath $stage --workpath "build\pyi-work" --specpath "build" $entry

if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed." -ForegroundColor Red
    exit $LASTEXITCODE
}

New-Item -ItemType Directory -Force $target | Out-Null
Get-ChildItem $target -Force |
    Where-Object { $_.Name -notin @("game", "config.json", "logs") } |
    Remove-Item -Recurse -Force
Copy-Item -Path "$stage\$name\*" -Destination $target -Recurse -Force

$jar = Join-Path (Split-Path $PSScriptRoot -Parent) "build\libs\goldish-1.0.0.jar"
if (Test-Path $jar) {
    Copy-Item $jar (Join-Path $target "goldish-1.0.0.jar") -Force
    Write-Host "Goldish jar copied next to the exe." -ForegroundColor Green
} else {
    Write-Host "build\libs\goldish-1.0.0.jar not found - run gradlew build first." -ForegroundColor Yellow
}
Copy-Item "README.md" (Join-Path $target "README.md") -Force

Remove-Item $stage, "build\pyi-work", "build\$name.spec" -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item "Goldishka Loader.spec" -Force -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "Done: $target\$name.exe" -ForegroundColor Green
Write-Host "Game files and config.json in $target are kept between builds." -ForegroundColor Gray
