# build-war.ps1
$ErrorActionPreference = "Stop"

$warName = "RuleBridge"
$buildDir = "target\war\$warName"
$classesDir = "$buildDir\WEB-INF\classes"
$libDir = "$buildDir\WEB-INF\lib"

Write-Host "Cleaning previous build..."
if (Test-Path "target") { Remove-Item -Recurse -Force "target" }

Write-Host "Creating WAR structure..."
New-Item -ItemType Directory -Force -Path $classesDir | Out-Null
New-Item -ItemType Directory -Force -Path $libDir | Out-Null

Write-Host "Copying compiled classes..."
$compiledClasses = "out"
if (Test-Path "out\production\RuleBridge") { $compiledClasses = "out\production\RuleBridge" }
Copy-Item -Path "$compiledClasses\*" -Destination $classesDir -Recurse -Force

Write-Host "Copying dependencies (Excluding Servlet API)..."
# CRITICAL: Exclude servlet-api so it doesn't conflict with WildFly's native modules
Copy-Item -Path "lib\*.jar" -Destination $libDir -Force -Exclude "*servlet-api*", "*javax.servlet*"

Write-Host "Copying configuration and UI..."
Copy-Item -Path "rulebridge.properties" -Destination $classesDir -Force
if (Test-Path "index.html") {
    Copy-Item -Path "index.html" -Destination $buildDir -Force
}

Write-Host "Packaging WAR file..."
$jarExe = "$env:JAVA_HOME\bin\jar.exe"
if (-Not (Test-Path $jarExe)) { $jarExe = "jar" }

Push-Location $buildDir
& $jarExe -cvf "..\..\$warName.war" *
Pop-Location

Write-Host "=============================================="
Write-Host "Successfully created target\$warName.war"
Write-Host "=============================================="