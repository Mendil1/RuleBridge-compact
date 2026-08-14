# Compiles the Java sources against jars in .\lib and servlet-api provided by WildFly
if (!(Test-Path .\lib)) {
    Write-Host "lib\ folder not found. Populate it first (see README.md)." -ForegroundColor Red
    exit 1
}
if (!(Test-Path .\out)) { New-Item -ItemType Directory -Path .\out | Out-Null }

$jarFiles = Get-ChildItem -Path .\lib -Filter *.jar | ForEach-Object { $_.FullName }
$servletApi = "/opt/wildfly/modules/system/layers/base/javax/servlet/api/main/jboss-servlet-api_4.0_spec-2.0.1.Final.jar"

if (Test-Path $servletApi) {
    $cp = ($jarFiles + $servletApi) -join [System.IO.Path]::PathSeparator
} else {
    $cp = $jarFiles -join [System.IO.Path]::PathSeparator
}

$sources = Get-ChildItem -Recurse -Path .\src -Filter *.java | ForEach-Object { $_.FullName }

javac -source 1.8 -target 1.8 -d out -cp "$cp" $sources

if ($LASTEXITCODE -eq 0) {
    Write-Host "Compiled OK -> .\out" -ForegroundColor Green
} else {
    Write-Host "Compilation failed." -ForegroundColor Red
}
