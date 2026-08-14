$ErrorActionPreference = "Stop"
$warName = "RuleBridge"
$buildDir = "target/war/$warName"
$classesDir = "$buildDir/WEB-INF/classes"
$libDir = "$buildDir/WEB-INF/lib"

Write-Host "Cleaning previous build..."
if (Test-Path "target") { Remove-Item -Recurse -Force "target" }

Write-Host "Creating WAR structure..."
New-Item -ItemType Directory -Force -Path $classesDir | Out-Null
New-Item -ItemType Directory -Force -Path $libDir | Out-Null

Write-Host "Copying compiled classes..."
if (Test-Path "out/rulebridge") {
    Copy-Item -Path "out/rulebridge/*" -Destination $classesDir -Recurse -Force
} elseif (Test-Path "out") {
    Copy-Item -Path "out/*" -Destination $classesDir -Recurse -Force
}

Write-Host "Copying dependencies..."
Copy-Item -Path "lib/*.jar" -Destination $libDir -Force

Write-Host "Aggressively removing Servlet API to prevent WildFly classloader conflicts..."
Remove-Item -Path "$libDir/*servlet*" -Force -ErrorAction SilentlyContinue
Remove-Item -Path "$libDir/*javax*" -Force -ErrorAction SilentlyContinue

Write-Host "Generating web.xml to force annotation scanning..."
$webXmlContent = @"
<?xml version="1.0" encoding="UTF-8"?>
<web-app xmlns="http://xmlns.jcp.org/xml/ns/javaee"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://xmlns.jcp.org/xml/ns/javaee http://xmlns.jcp.org/xml/ns/javaee/web-app_3_1.xsd"
         version="3.1"
         metadata-complete="false">
</web-app>
"@
Set-Content -Path "$buildDir/WEB-INF/web.xml" -Value $webXmlContent -Encoding UTF8

Write-Host "Copying configuration and UI..."
Copy-Item -Path "rulebridge.properties" -Destination $classesDir -Force
if (Test-Path "index.html") {
    Copy-Item -Path "index.html" -Destination $buildDir -Force
}

Write-Host "Packaging WAR file..."
Push-Location $buildDir
jar -cvf "../../$warName.war" *
Pop-Location

Write-Host "=============================================="
Write-Host "Successfully created target/$warName.war"
Write-Host "=============================================="
