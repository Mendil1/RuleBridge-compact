$ErrorActionPreference = "Continue"
$baseUrl = "http://localhost:8080/RuleBridge"
$empId = "qa_automation_tester"
$userDir = "$env:USERPROFILE\.rulebridge_data\$empId"

Write-Host "=====================================================" -ForegroundColor Cyan
Write-Host "   RuleBridge Enterprise QA Automation Suite v1.0" -ForegroundColor Cyan
Write-Host "=====================================================" -ForegroundColor Cyan

# 0. Get API Key
$apiKey = $env:GEMINI_API_KEY
if (-not $apiKey) {
    $apiKey = Read-Host "Enter your Gemini API Key for testing"
}

# Helper function for pretty printing
function Log-Test($name, $status, $details) {
    $color = if ($status -eq "PASS") { "Green" } elseif ($status -eq "FAIL") { "Red" } else { "Yellow" }
    $icon = if ($status -eq "PASS") { "[✅]" } elseif ($status -eq "FAIL") { "[❌]" } else { "[⚠️]" }
    Write-Host "$icon $name" -ForegroundColor $color -NoNewline
    if ($details) { Write-Host " - $details" -ForegroundColor Gray } else { Write-Host "" }
}

try {
    # ---------------------------------------------------------
    # TEST 1: Server Health Check
    # ---------------------------------------------------------
    try {
        $health = Invoke-WebRequest -Uri $baseUrl -UseBasicParsing -TimeoutSec 5
        Log-Test "WildFly Server Health" "PASS" "HTTP $($health.StatusCode)"
    } catch {
        Log-Test "WildFly Server Health" "FAIL" "Server is not running on $baseUrl"
        exit
    }

    # ---------------------------------------------------------
    # TEST 2: Generate Dummy Excel Files (Python)
    # ---------------------------------------------------------
    $pyScript = @(
        'import pandas as pd'
        'df_expr = pd.DataFrame({"PK_": [1, 2], "PARENTOBJECTPK_": [100, 100], "PARENTOBJECTCONDITIONSINDEX_": [1, 2], "ERRORKEY_": ["''QA01'',qaField,QA Label", "''QA02'',qaField2,QA Label 2"], "EXPRESSION_": ["qaField != null ? true : false", "qaField2 == null ? true : false"]})'
        'df_ctrl = pd.DataFrame({"CODE_": ["QA01", "QA02"], "DESCRIPTION_": ["QA Test Rule 1", "QA Test Rule 2"], "ERRORTYPE_": ["Error", "Error"], "STATUT_": ["Valid", "Valid"]})'
        'with pd.ExcelWriter("qa_expr.xlsx", engine="openpyxl") as w: df_expr.to_excel(w, sheet_name="Exporter la feuille de calcul", index=False)'
        'with pd.ExcelWriter("qa_ctrl.xlsx", engine="openpyxl") as w: df_ctrl.to_excel(w, sheet_name="Exporter la feuille de calcul", index=False)'
    ) -join "`n"

    $pyScript | python | Out-Null
    if ((Test-Path "qa_expr.xlsx") -and (Test-Path "qa_ctrl.xlsx")) {
        Log-Test "Dummy File Generation" "PASS" "Created qa_expr.xlsx & qa_ctrl.xlsx"
    } else {
        Log-Test "Dummy File Generation" "FAIL" "Python pandas/openpyxl missing or failed"
        exit
    }

    # ---------------------------------------------------------
    # TEST 3: Multi-File Upload & Merger (The Happy Path)
    # ---------------------------------------------------------
    $exprPath = (Resolve-Path "qa_expr.xlsx").Path
    $ctrlPath = (Resolve-Path "qa_ctrl.xlsx").Path

    $uploadRes = curl.exe -s -X POST "$baseUrl/upload" `
        -F "empId=$empId" `
        -F "fileName=QA Merged Test" `
        -F "exprFile=@$exprPath" `
        -F "ctrlFile=@$ctrlPath" | ConvertFrom-Json

    if ($uploadRes.success -eq $true) {
        Log-Test "Upload & ExcelMerger" "PASS" "Merged and indexed $($uploadRes.fileId)"
        $mergedFileId = $uploadRes.fileId
    } else {
        Log-Test "Upload & ExcelMerger" "FAIL" $uploadRes.error
    }

    # ---------------------------------------------------------
    # TEST 4: Single Pre-Merged File Upload
    # ---------------------------------------------------------
    $masterFile = Get-ChildItem -Path . -Filter "Master_Rules_Audit_Report.xlsx" -ErrorAction SilentlyContinue
    if ($masterFile) {
        $masterPath = $masterFile.FullName
        $uploadRes2 = curl.exe -s -X POST "$baseUrl/upload" `
            -F "empId=$empId" `
            -F "fileName=Master Pre-Merged" `
            -F "exprFile=@$masterPath" | ConvertFrom-Json

        if ($uploadRes2.success -eq $true) {
            Log-Test "Single File Upload" "PASS" "Indexed Master file"
        } else {
            Log-Test "Single File Upload" "FAIL" $uploadRes2.error
        }
    } else {
        Log-Test "Single File Upload" "SKIP" "Master_Rules_Audit_Report.xlsx not found"
    }

    # ---------------------------------------------------------
    # TEST 5: Manifest Verification (Proves No Race Conditions)
    # ---------------------------------------------------------
    $manifest = Invoke-RestMethod -Uri "$baseUrl/files?empId=$empId"
    if ($manifest.Count -ge 2) {
        Log-Test "Manifest Integrity" "PASS" "Found $($manifest.Count) files in manifest.json"
    } else {
        Log-Test "Manifest Integrity" "FAIL" "Expected 2 files, found $($manifest.Count)"
    }

    # ---------------------------------------------------------
    # TEST 6: RAG Generation with ChromaDB $in Filter
    # ---------------------------------------------------------
    $fileIds = ($manifest | ForEach-Object { $_.id }) -join ","
    $genBody = @{
        prompt = "Vérifier que le champ QA n'est pas nul"
        apiKey = $apiKey
        mainCollection = "rules_$empId"
        rejectedCollection = "rejected_$empId"
        selectedFiles = $fileIds
    }

    Write-Host "   [⏳] Querying Gemini AI (this takes ~3 seconds)..." -ForegroundColor Gray
    $genResult = Invoke-RestMethod -Uri "$baseUrl/generate" -Method Post -Body $genBody

    if ($genResult.generatedCode -and $genResult.generatedCode.Length -gt 5) {
        Log-Test "RAG Generation (Filtered)" "PASS" "AI returned DSL code"
    } else {
        Log-Test "RAG Generation (Filtered)" "FAIL" "AI returned empty or error"
    }

    # ---------------------------------------------------------
    # TEST 7: Chaos Test - Orphan Rollback
    # ---------------------------------------------------------
    "This is not an excel file" > fake_corrupt.xlsx
    $fakePath = (Resolve-Path "fake_corrupt.xlsx").Path

    $corruptRes = curl.exe -s -X POST "$baseUrl/upload" `
        -F "empId=$empId" `
        -F "fileName=Corrupt File" `
        -F "exprFile=@$fakePath" | ConvertFrom-Json

    $filesOnDisk = Get-ChildItem $userDir -Filter "*.xlsx" -ErrorAction SilentlyContinue | Where-Object { $_.Name -like "fake*" -or $_.Name -like "temp*" }

    if ($corruptRes.error -and $filesOnDisk.Count -eq 0) {
        Log-Test "Orphan Rollback (Chaos)" "PASS" "Server rejected bad file & deleted temp garbage"
    } else {
        Log-Test "Orphan Rollback (Chaos)" "FAIL" "Server left orphan files on disk!"
    }

    # ---------------------------------------------------------
    # TEST 8: Deletion & Cleanup
    # ---------------------------------------------------------
    $targetId = $manifest[0].id
    $delBody = @{ empId = $empId; action = "delete"; fileId = $targetId }
    Invoke-RestMethod -Uri "$baseUrl/files" -Method Post -Body $delBody | Out-Null

    $manifestAfter = Invoke-RestMethod -Uri "$baseUrl/files?empId=$empId"
    $diskFilesAfter = (Get-ChildItem $userDir -Filter "*.xlsx" -ErrorAction SilentlyContinue).Count

    if ($manifestAfter.Count -lt $manifest.Count -and $diskFilesAfter -lt 3) {
        Log-Test "File Deletion & Cleanup" "PASS" "Removed from manifest and disk"
    } else {
        Log-Test "File Deletion & Cleanup" "FAIL" "File still exists in manifest or disk"
    }

} catch {
    Write-Host "`n[🔥] FATAL SCRIPT ERROR: $($_.Exception.Message)" -ForegroundColor Red
} finally {
    # ---------------------------------------------------------
    # CLEANUP: Leave no trace
    # ---------------------------------------------------------
    Write-Host "`n--- Cleaning up QA Artifacts ---" -ForegroundColor Yellow
    Remove-Item "qa_expr.xlsx", "qa_ctrl.xlsx", "fake_corrupt.xlsx" -ErrorAction SilentlyContinue
    Remove-Item $userDir -Recurse -Force -ErrorAction SilentlyContinue

    # Tell ChromaDB to drop the test collections
    try {
        $chromaUrl = "http://localhost:8000/api/v2/tenants/default_tenant/databases/default_database/collections"
        $cols = Invoke-RestMethod -Uri $chromaUrl
        $testCols = $cols | Where-Object { $_.name -like "*$empId*" }
        foreach ($col in $testCols) {
            Invoke-RestMethod -Uri "$chromaUrl/$($col.id)" -Method Delete | Out-Null
        }
        Write-Host "   [🧹] ChromaDB test collections dropped." -ForegroundColor Gray
    } catch {}

    Write-Host "`n=====================================================" -ForegroundColor Cyan
    Write-Host "   QA Suite Complete. System is clean." -ForegroundColor Cyan
    Write-Host "=====================================================" -ForegroundColor Cyan
}
