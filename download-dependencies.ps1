# download-deps.ps1
$libDir = "lib"
New-Item -ItemType Directory -Force -Path $libDir | Out-Null

$dependencies = @(
    # DJL / ONNX
    "ai.djl:api:0.27.0",
    "ai.djl:model-zoo:0.27.0",
    "ai.djl:tokenizers:0.27.0",
    "com.microsoft.onnxruntime:onnxruntime:1.16.0",

    # Jackson (JSON)
    "com.fasterxml.jackson.core:jackson-databind:2.15.3",
    "com.fasterxml.jackson.core:jackson-core:2.15.3",
    "com.fasterxml.jackson.core:jackson-annotations:2.15.3",

    # OkHttp (HTTP Client)
    "com.squareup.okhttp3:okhttp:4.12.0",
    "com.squareup.okio:okio:3.6.0",

    # Apache POI (Excel)
    "org.apache.poi:poi:5.2.5",
    "org.apache.poi:poi-ooxml:5.2.5",
    "org.apache.poi:poi-ooxml-lite:5.2.5",
    "org.apache.xmlbeans:xmlbeans:5.2.0",
    "org.apache.commons:commons-compress:1.25.0",
    "org.apache.commons:commons-math3:3.6.1",
    "org.apache.commons:commons-collections4:4.4"
)

foreach ($dep in $dependencies) {
    $parts = $dep -split ":"
    $group = $parts[0] -replace "\.", "/"
    $artifact = $parts[1]
    $version = $parts[2]
    $jarName = "${artifact}-${version}.jar"
    $url = "https://repo1.maven.org/maven2/${group}/${artifact}/${version}/${jarName}"
    $output = Join-Path $libDir $jarName

    if (-not (Test-Path $output)) {
        Write-Host "Downloading $jarName ..."
        Invoke-WebRequest -Uri $url -OutFile $output
    } else {
        Write-Host "$jarName already exists."
    }
}
Write-Host "All dependencies downloaded to $libDir/"