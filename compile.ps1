# Compiles the 3 classes against the jars in .\lib
# Run once you have populated .\lib (see README.md).
if (!(Test-Path .\lib)) {
    Write-Host "lib\ folder not found. Populate it first (see README.md)." -ForegroundColor Red
    exit 1
}
if (!(Test-Path .\out)) { New-Item -ItemType Directory -Path .\out | Out-Null }

$libs = (Get-ChildItem -Path .\lib -Filter *.jar | ForEach-Object { $_.FullName }) -join ';'
$sources = (Get-ChildItem -Recurse -Path .\src -Filter *.java | ForEach-Object { $_.FullName })

# Added '--release 8' to force Java 8 compatibility
javac --release 8 -d out -cp $libs $sources

if ($LASTEXITCODE -eq 0) {
    Write-Host "Compiled OK -> .\out" -ForegroundColor Green
} else {
    Write-Host "Compilation failed." -ForegroundColor Red
}
