#!/usr/bin/env powershell
# UserService API - Comprehensive Testing Script
# Purpose: Test all endpoints and measure performance metrics
# Usage: .\test-endpoints.ps1

param(
    [string]$BaseUrl = "http://localhost:8081",
    [int]$TestDelay = 500  # Milliseconds between requests
)

Write-Host "═════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "  UserService API - Comprehensive Testing Script  " -ForegroundColor Cyan
Write-Host "═════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host ""
Write-Host "Base URL: $BaseUrl" -ForegroundColor Yellow
Write-Host ""

# Store JWT token for authenticated requests
$global:JwtToken = ""
$global:TestResults = @()

# Test counter
$testNumber = 1

function Test-Endpoint {
    param(
        [string]$TestName,
        [string]$Method,
        [string]$Endpoint,
        [hashtable]$Headers = @{},
        [string]$Body = ""
    )

    Write-Host "[$testNumber] Testing: $TestName" -ForegroundColor Green

    $url = "$BaseUrl$Endpoint"
    $startTime = Get-Date

    try {
        $params = @{
            Uri     = $url
            Method  = $Method
            Headers = $Headers
        }

        if ($Body) {
            $params['Body'] = $Body
            $params['ContentType'] = 'application/json'
        }

        $response = Invoke-WebRequest @params -ErrorAction Stop
        $duration = (Get-Date) - $startTime

        Write-Host "  ✅ Status: $($response.StatusCode)" -ForegroundColor Green
        Write-Host "  ⏱️  Duration: $($duration.TotalMilliseconds)ms" -ForegroundColor Cyan

        if ($response.Content) {
            $content = $response.Content | ConvertFrom-Json
            Write-Host "  📦 Response: $($content | ConvertTo-Json -Depth 2)" -ForegroundColor Gray

            # Store JWT if login response
            if ($content.PSObject.Properties.Name -contains "token" -or ($content -is [string] -and $content.StartsWith("eyJ"))) {
                $global:JwtToken = if ($content -is [string]) { $content } else { $content.token }
                Write-Host "  🔐 JWT Token Stored: $($global:JwtToken.Substring(0, 30))..." -ForegroundColor Magenta
            }
        }

        $global:TestResults += @{
            Name     = $TestName
            Status   = "✅ PASS"
            Duration = $duration.TotalMilliseconds
        }

    } catch {
        $duration = (Get-Date) - $startTime
        Write-Host "  ❌ Status: $($_.Exception.Response.StatusCode)" -ForegroundColor Red
        Write-Host "  ⏱️  Duration: $($duration.TotalMilliseconds)ms" -ForegroundColor Cyan

        try {
            $errorContent = $_.Exception.Response.Content.ReadAsStream() | ForEach-Object { [System.IO.StreamReader]::new($_).ReadToEnd() }
            Write-Host "  📦 Error: $errorContent" -ForegroundColor Red
        } catch {
            Write-Host "  📦 Error: $($_.Exception.Message)" -ForegroundColor Red
        }

        $global:TestResults += @{
            Name     = $TestName
            Status   = "❌ FAIL"
            Duration = $duration.TotalMilliseconds
        }
    }

    $global:testNumber++
    Write-Host ""
    Start-Sleep -Milliseconds $TestDelay
}

# ═════════════════════════════════════════════════
# SECTION 1: USER CREATION TESTS
# ═════════════════════════════════════════════════

Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Yellow
Write-Host "SECTION 1: USER CREATION TESTS" -ForegroundColor Yellow
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Yellow
Write-Host ""

Test-Endpoint -TestName "Create User - Valid" `
    -Method "POST" `
    -Endpoint "/api/users" `
    -Body '{"name":"John Doe","email":"john.doe@example.com","password":"SecurePass123!"}'

Test-Endpoint -TestName "Create User - Valid #2" `
    -Method "POST" `
    -Endpoint "/api/users" `
    -Body '{"name":"Jane Smith","email":"jane.smith@example.com","password":"SecurePass456!"}'

Test-Endpoint -TestName "Create User - Invalid Email" `
    -Method "POST" `
    -Endpoint "/api/users" `
    -Body '{"name":"Invalid User","email":"not-an-email","password":"SecurePass123!"}'

Test-Endpoint -TestName "Create User - Weak Password" `
    -Method "POST" `
    -Endpoint "/api/users" `
    -Body '{"name":"Weak Password User","email":"weak@example.com","password":"weak"}'

Test-Endpoint -TestName "Create User - Duplicate Email" `
    -Method "POST" `
    -Endpoint "/api/users" `
    -Body '{"name":"Duplicate User","email":"john.doe@example.com","password":"AnotherPass123!"}'

# ═════════════════════════════════════════════════
# SECTION 2: AUTHENTICATION TESTS
# ═════════════════════════════════════════════════

Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Yellow
Write-Host "SECTION 2: AUTHENTICATION TESTS" -ForegroundColor Yellow
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Yellow
Write-Host ""

Test-Endpoint -TestName "Login - Valid Credentials" `
    -Method "POST" `
    -Endpoint "/api/auth/login" `
    -Body '{"email":"john.doe@example.com","password":"SecurePass123!"}'

Test-Endpoint -TestName "Login - Invalid Email" `
    -Method "POST" `
    -Endpoint "/api/auth/login" `
    -Body '{"email":"nonexistent@example.com","password":"AnyPassword123!"}'

Test-Endpoint -TestName "Login - Invalid Password" `
    -Method "POST" `
    -Endpoint "/api/auth/login" `
    -Body '{"email":"john.doe@example.com","password":"WrongPassword123!"}'

# ═════════════════════════════════════════════════
# SECTION 3: USER UPDATE TESTS
# ═════════════════════════════════════════════════

Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Yellow
Write-Host "SECTION 3: USER UPDATE TESTS" -ForegroundColor Yellow
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Yellow
Write-Host ""

Test-Endpoint -TestName "Update User - Valid (ID: 1)" `
    -Method "PUT" `
    -Endpoint "/api/users/1" `
    -Body '{"name":"John Updated","email":"john.updated@example.com","password":"NewPass123!"}'

Test-Endpoint -TestName "Update User - Invalid Email" `
    -Method "PUT" `
    -Endpoint "/api/users/1" `
    -Body '{"name":"John Doe","email":"invalid-email","password":"Pass123!"}'

Test-Endpoint -TestName "Update User - Non-existent User" `
    -Method "PUT" `
    -Endpoint "/api/users/99999" `
    -Body '{"name":"Ghost User","email":"ghost@example.com","password":"Pass123!"}'

# ═════════════════════════════════════════════════
# SECTION 4: USER DELETION TESTS
# ═════════════════════════════════════════════════

Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Yellow
Write-Host "SECTION 4: USER DELETION TESTS" -ForegroundColor Yellow
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Yellow
Write-Host ""

Test-Endpoint -TestName "Delete User - Non-existent User" `
    -Method "DELETE" `
    -Endpoint "/api/users/99999"

# ═════════════════════════════════════════════════
# SECTION 5: CSV UPLOAD TESTS
# ═════════════════════════════════════════════════

Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Yellow
Write-Host "SECTION 5: CSV UPLOAD TESTS" -ForegroundColor Yellow
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Yellow
Write-Host ""

Write-Host "⏭️  Note: CSV upload requires actual file. Use Postman for this test." -ForegroundColor Cyan
Write-Host ""

# ═════════════════════════════════════════════════
# SECTION 6: TEST SUMMARY
# ═════════════════════════════════════════════════

Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Yellow
Write-Host "TEST SUMMARY" -ForegroundColor Yellow
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Yellow
Write-Host ""

$passed = ($global:TestResults | Where-Object { $_.Status -eq "✅ PASS" }).Count
$failed = ($global:TestResults | Where-Object { $_.Status -eq "❌ FAIL" }).Count
$totalDuration = ($global:TestResults | Measure-Object -Property Duration -Sum).Sum

Write-Host "Total Tests: $($global:TestResults.Count)" -ForegroundColor Cyan
Write-Host "✅ Passed: $passed" -ForegroundColor Green
Write-Host "❌ Failed: $failed" -ForegroundColor Red
Write-Host "⏱️  Total Duration: $($totalDuration)ms" -ForegroundColor Cyan
Write-Host ""

Write-Host "Test Details:" -ForegroundColor Yellow
$global:TestResults | Format-Table -Property Name, Status, Duration -AutoSize

Write-Host ""
Write-Host "═════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "  Testing Complete!" -ForegroundColor Cyan
Write-Host "═════════════════════════════════════════════════" -ForegroundColor Cyan

