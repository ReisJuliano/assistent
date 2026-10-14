# Serves <repo>/updates on port 8318 so phones can self-update from this PC.
# Usage:  ./gradlew publishUpdate ; powershell -File tools/serve-updates.ps1
# Then set the app's update URL to http://<this-pc-ip>:8318/latest.json (Settings > Atualizações).
$root = Join-Path $PSScriptRoot "..\updates"
New-Item -ItemType Directory -Force $root | Out-Null
Write-Host "Serving $root on http://0.0.0.0:8318/ (Ctrl+C to stop)"
python -m http.server 8318 --bind 0.0.0.0 --directory $root
