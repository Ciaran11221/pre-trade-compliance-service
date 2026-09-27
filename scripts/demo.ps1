<#
    scripts/demo.ps1

    Runs the pre-trade compliance demo end to end, for Windows PowerShell 5.1:

      1. Checks Docker is running and port 8080 is free, changing nothing if either fails.
      2. Resets the local database (docker compose down -v, then up -d), so the demo data is
         always the seed data, never whatever an earlier run left behind.
      3. Starts the service (mvnw.cmd spring-boot:run, local profile) and waits for it to
         report healthy.
      4. Signs a token for each demo person in PowerShell itself, using the same secret and
         algorithm TokenTool uses, and checks each one against GET /api/me.
      5. Runs the scenes from docs/DEMO.md (1, 1b, 2, 6 and 8) and checks each response against
         what that file says should happen.
      6. Stops the service it started (only that one) and leaves the database running.

    Usage:
        .\scripts\demo.ps1
        .\scripts\demo.ps1 -NoPause

    Without -NoPause the script pauses for Enter after each scene, so a live audience can read
    each result before the next one runs. -NoPause runs straight through.
#>

[CmdletBinding()]
param(
    [switch]$NoPause
)

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$LogPath = Join-Path $RepoRoot 'target\demo-service.log'
$BaseUrl = 'http://localhost:8080'

$Script:ServiceProcessId = $null
$Script:CheckResults = New-Object System.Collections.ArrayList

# ---------------------------------------------------------------------------
# Small helpers
# ---------------------------------------------------------------------------

function Write-Scene {
    param([string]$Text)
    Write-Host ''
    Write-Host "=== $Text ===" -ForegroundColor Cyan
}

function Add-Check {
    param(
        [bool]$Passed,
        [string]$Description
    )
    $null = $Script:CheckResults.Add([pscustomobject]@{ Passed = $Passed; Description = $Description })
    if ($Passed) {
        Write-Host "PASS  $Description" -ForegroundColor Green
    }
    else {
        Write-Host "FAIL  $Description" -ForegroundColor Red
    }
}

function Wait-ForEnter {
    param([string]$Message = 'Press Enter for the next scene')
    if (-not $NoPause) {
        Write-Host $Message -ForegroundColor DarkGray
        [void](Read-Host)
    }
}

function Stop-DemoService {
    if ($null -ne $Script:ServiceProcessId) {
        $targetId = $Script:ServiceProcessId
        $Script:ServiceProcessId = $null
        try {
            & taskkill /PID $targetId /T /F 2>&1 | Out-Null
        }
        catch {
            # Already gone; nothing further to do.
        }
    }
}

# Ctrl+C during a Read-Host pause raises this event before the process exits, so the service
# still gets stopped rather than left running in the background.
Register-EngineEvent -SourceIdentifier PowerShell.Exiting -Action { Stop-DemoService } | Out-Null

function Test-PortOpen {
    param(
        [string]$HostName,
        [int]$Port
    )
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($HostName, $Port, $null, $null)
        $connected = $async.AsyncWaitHandle.WaitOne(500)
        return ($connected -and $client.Connected)
    }
    catch {
        return $false
    }
    finally {
        $client.Close()
    }
}


# Windows PowerShell 5.1's Invoke-WebRequest only decodes .Content to a string for a short list
# of content types it recognises; anything else (Spring Boot actuator's own
# "application/vnd.spring-boot.actuator.v3+json", and this service's own
# "application/problem+json" error bodies) comes back as a raw byte array instead. Every read of
# .Content in this script goes through here so that is never missed silently.
function ConvertFrom-ResponseContent {
    param($Content)
    if ($null -eq $Content) {
        return $null
    }
    if ($Content -is [byte[]]) {
        return [System.Text.Encoding]::UTF8.GetString($Content)
    }
    return $Content
}

function ConvertTo-Base64Url {
    param([byte[]]$Bytes)
    $b64 = [Convert]::ToBase64String($Bytes)
    return $b64.Replace('+', '-').Replace('/', '_').TrimEnd('=')
}

function Get-JwtSecret {
    $ymlPath = Join-Path $RepoRoot 'src\main\resources\application-local.yml'
    foreach ($line in Get-Content -Path $ymlPath) {
        if ($line -match 'jwt-secret:\s*(\S+)') {
            return $Matches[1].Trim()
        }
    }
    throw "Could not find compliance.security.jwt-secret in $ymlPath"
}

function New-DemoToken {
    param(
        [string]$StaffId,
        [string[]]$Roles,
        [string]$Secret,
        [int]$MinutesValid = 120
    )
    $iat = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $exp = $iat + ($MinutesValid * 60)
    $rolesJson = ($Roles | ForEach-Object { '"' + $_ + '"' }) -join ','
    $headerJson = '{"alg":"HS256"}'
    $payloadJson = '{"sub":"' + $StaffId + '","roles":[' + $rolesJson + '],"iat":' + $iat + ',"exp":' + $exp + '}'

    $headerB64 = ConvertTo-Base64Url([System.Text.Encoding]::UTF8.GetBytes($headerJson))
    $payloadB64 = ConvertTo-Base64Url([System.Text.Encoding]::UTF8.GetBytes($payloadJson))
    $signingInput = "$headerB64.$payloadB64"

    $hmac = New-Object System.Security.Cryptography.HMACSHA256
    $hmac.Key = [System.Text.Encoding]::UTF8.GetBytes($Secret)
    try {
        $signatureBytes = $hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($signingInput))
    }
    finally {
        $hmac.Dispose()
    }
    $signatureB64 = ConvertTo-Base64Url($signatureBytes)

    return "$signingInput.$signatureB64"
}

# Calls the API and returns the HTTP status code and parsed JSON body either way, since several
# scenes here expect a 4xx/422 response and Invoke-WebRequest throws on those in Windows
# PowerShell 5.1 rather than returning them.
function Invoke-DemoApi {
    param(
        [string]$Method,
        [string]$Path,
        [string]$Token,
        $Body
    )
    $uri = "$BaseUrl$Path"
    $headers = @{ Authorization = "Bearer $Token" }
    try {
        if ($null -ne $Body) {
            $jsonBody = $Body | ConvertTo-Json -Depth 10 -Compress
            $response = Invoke-WebRequest -Uri $uri -Method $Method -Headers $headers -Body $jsonBody `
                -ContentType 'application/json' -UseBasicParsing
        }
        else {
            $response = Invoke-WebRequest -Uri $uri -Method $Method -Headers $headers -UseBasicParsing
        }
        $parsedBody = $null
        $responseText = ConvertFrom-ResponseContent $response.Content
        if ($responseText) {
            $parsedBody = $responseText | ConvertFrom-Json
        }
        return [pscustomobject]@{ StatusCode = [int]$response.StatusCode; Body = $parsedBody }
    }
    catch {
        $statusCode = 0
        $bodyText = $null
        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
            try {
                $stream = $_.Exception.Response.GetResponseStream()
                $reader = New-Object System.IO.StreamReader($stream)
                $bodyText = $reader.ReadToEnd()
                $reader.Close()
            }
            catch {
                $bodyText = $null
            }
        }
        $parsedBody = $bodyText
        if ($bodyText) {
            try {
                $parsedBody = $bodyText | ConvertFrom-Json
            }
            catch {
                $parsedBody = $bodyText
            }
        }
        return [pscustomobject]@{ StatusCode = $statusCode; Body = $parsedBody }
    }
}

# ---------------------------------------------------------------------------
# 1. Pre-checks
# ---------------------------------------------------------------------------

$serverVersion = $null
try {
    $serverVersion = & docker info --format '{{.ServerVersion}}' 2>$null
}
catch {
    $serverVersion = $null
}
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($serverVersion)) {
    Write-Host 'Start Docker Desktop from the Start menu, then run this again.' -ForegroundColor Red
    exit 1
}

if (Test-PortOpen -HostName 'localhost' -Port 8080) {
    Write-Host 'Something is already running on port 8080. Stop it (Ctrl+C in its window), then run this again.' -ForegroundColor Red
    exit 1
}

$ExitCode = 0

try {
    # -----------------------------------------------------------------------
    # 2. Reset the demo data
    # -----------------------------------------------------------------------

    Write-Host 'Resetting the demo database...'
    & docker compose down -v
    & docker compose up -d

    $postgresReady = $false
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline) {
        & docker compose exec -T postgres pg_isready -U compliance 2>$null 1>$null
        if ($LASTEXITCODE -eq 0) {
            $postgresReady = $true
            break
        }
        Start-Sleep -Seconds 1
    }
    if (-not $postgresReady) {
        Write-Host 'Postgres did not report ready within 30 seconds.' -ForegroundColor Red
        exit 1
    }

    # -----------------------------------------------------------------------
    # 3. Start the service
    # -----------------------------------------------------------------------

    Write-Host 'Starting the service (this can take a minute)...'
    $targetDir = Join-Path $RepoRoot 'target'
    if (-not (Test-Path $targetDir)) {
        New-Item -ItemType Directory -Path $targetDir | Out-Null
    }
    if (Test-Path $LogPath) {
        Remove-Item $LogPath -Force
    }

    # cmd.exe's own quote-stripping rule for "/c <string>" only cleanly survives a SINGLE quoted
    # token: with two separate quoted pieces (the mvnw.cmd path and the log path) on one /c line,
    # cmd strips the first and last quote character of the WHOLE string, not each pair, which
    # corrupts everything in between and made the service fail to start silently, with no log
    # file at all. Writing the real command into a wrapper .cmd file sidesteps that: the wrapper
    # is invoked as the one quoted argument to /c, and its own lines are parsed normally.
    $mvnwPath = Join-Path $RepoRoot 'mvnw.cmd'
    $wrapperPath = Join-Path $RepoRoot 'target\demo-run.cmd'
    $wrapperContent = '@echo off' + "`r`n" + '"' + $mvnwPath + '" spring-boot:run "-Dspring-boot.run.profiles=local" > "' + $LogPath + '" 2>&1'
    Set-Content -Path $wrapperPath -Value $wrapperContent -Encoding ASCII

    $serviceProcess = Start-Process -FilePath 'cmd.exe' -ArgumentList "/c `"$wrapperPath`"" `
        -WorkingDirectory $RepoRoot -WindowStyle Hidden -PassThru
    $Script:ServiceProcessId = $serviceProcess.Id

    $healthy = $false
    $deadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $deadline) {
        try {
            $health = Invoke-WebRequest -Uri "$BaseUrl/actuator/health" -UseBasicParsing -TimeoutSec 3
            if ($health.StatusCode -eq 200) {
                $healthText = ConvertFrom-ResponseContent $health.Content
                $healthBody = $healthText | ConvertFrom-Json
                if ($healthBody.status -eq 'UP') {
                    $healthy = $true
                    break
                }
            }
        }
        catch {
            # Not up yet; keep polling.
        }
        Start-Sleep -Seconds 2
    }
    if (-not $healthy) {
        Write-Host 'The service did not become healthy within 120 seconds. Last 20 log lines:' -ForegroundColor Red
        if (Test-Path $LogPath) {
            Get-Content -Path $LogPath -Tail 20
        }
        exit 1
    }
    Write-Host 'Service is up.' -ForegroundColor Green

    # -----------------------------------------------------------------------
    # 4. Sign and check tokens
    # -----------------------------------------------------------------------

    $secret = Get-JwtSecret
    $people = @(
        [pscustomobject]@{ Id = 'anne'; Roles = @('TRADER') },
        [pscustomobject]@{ Id = 'brian'; Roles = @('TRADER') },
        [pscustomobject]@{ Id = 'sup-1'; Roles = @('SUPERVISOR') },
        [pscustomobject]@{ Id = 'sup-2'; Roles = @('SUPERVISOR') }
    )
    $tokens = @{}
    foreach ($person in $people) {
        $tokens[$person.Id] = New-DemoToken -StaffId $person.Id -Roles $person.Roles -Secret $secret
    }

    Write-Host 'Checking every token is accepted...'
    foreach ($person in $people) {
        $me = Invoke-DemoApi -Method GET -Path '/api/me' -Token $tokens[$person.Id]
        if ($me.StatusCode -ne 200 -or $me.Body.staffId -ne $person.Id) {
            Write-Host "Token for $($person.Id) was not accepted (HTTP $($me.StatusCode))." -ForegroundColor Red
            exit 1
        }
    }
    Write-Host 'All 4 tokens accepted.' -ForegroundColor Green

    # -----------------------------------------------------------------------
    # 5. Scenes
    # -----------------------------------------------------------------------

    Write-Scene 'Scene 1: anne buys KSTL for HGF'
    $scene1Body = @{ clientOrderId = 'demo-hgf-1'; fundId = 1; side = 'BUY'; ticker = 'KSTL'; quantity = 100000 }
    $scene1 = Invoke-DemoApi -Method POST -Path '/api/orders' -Token $tokens['anne'] -Body $scene1Body
    $scene1OrderId = $scene1.Body.id
    $scene1Diversification = $scene1.Body.decision.ruleResults | Where-Object { $_.ruleName -eq 'diversification' } | Select-Object -First 1
    Write-Host "HTTP $($scene1.StatusCode), order id $scene1OrderId, status $($scene1.Body.status)"
    Write-Host "Diversification: $($scene1Diversification.reason)"
    Add-Check -Passed ($scene1.StatusCode -eq 201 -and $scene1.Body.status -eq 'PASS') -Description 'Scene 1: the buy passes'
    Wait-ForEnter

    Write-Scene 'Scene 1b: anne sends the exact same request again'
    $scene1b = Invoke-DemoApi -Method POST -Path '/api/orders' -Token $tokens['anne'] -Body $scene1Body
    Write-Host "First order id $scene1OrderId, second response order id $($scene1b.Body.id)"
    Add-Check -Passed ($scene1b.Body.id -eq $scene1OrderId) -Description 'Scene 1b: the retry returns the same order, not a new one'
    Wait-ForEnter

    Write-Scene 'Scene 2: the same buy for WVF, already near the limit'
    $scene2Body = @{ clientOrderId = 'demo-wvf-1'; fundId = 2; side = 'BUY'; ticker = 'KSTL'; quantity = 100000 }
    $scene2 = Invoke-DemoApi -Method POST -Path '/api/orders' -Token $tokens['anne'] -Body $scene2Body
    $scene2Diversification = $scene2.Body.decision.ruleResults | Where-Object { $_.ruleName -eq 'diversification' } | Select-Object -First 1
    Write-Host "HTTP $($scene2.StatusCode), order id $($scene2.Body.id), status $($scene2.Body.status)"
    Write-Host "Diversification: $($scene2Diversification.reason)"
    Add-Check -Passed ($scene2.Body.status -eq 'BLOCK') -Description 'Scene 2: the same buy blocks for WVF'
    Wait-ForEnter

    Write-Scene 'Scene 6: brian sends anne''s scene 1 order under a new id'
    $scene6Body = @{ clientOrderId = 'demo-brian-1'; fundId = 1; side = 'BUY'; ticker = 'KSTL'; quantity = 100000 }
    $scene6 = Invoke-DemoApi -Method POST -Path '/api/orders' -Token $tokens['brian'] -Body $scene6Body
    $brianOrderId = $scene6.Body.id
    Write-Host "HTTP $($scene6.StatusCode), order id $brianOrderId, status $($scene6.Body.status)"
    Write-Host "Quarantine reason $($scene6.Body.quarantine.reason), matched order id $($scene6.Body.quarantine.matchedOrderId)"
    Add-Check -Passed ($scene6.Body.status -eq 'QUARANTINED' -and $scene6.Body.quarantine.reason -eq 'POSSIBLE_DUPLICATE' -and $scene6.Body.quarantine.matchedOrderId -eq $scene1OrderId) `
        -Description 'Scene 6: quarantined as a possible duplicate, matched to scene 1'

    $releaseByAnne = Invoke-DemoApi -Method POST -Path "/api/quarantine/$brianOrderId/release" -Token $tokens['anne']
    $releaseDetail = $releaseByAnne.Body.detail
    if ([string]::IsNullOrWhiteSpace($releaseDetail)) {
        # This 403 comes from @PreAuthorize, not from the service layer, and this build sends it
        # with an empty body despite the ProblemDetail content type: nothing this script can fix,
        # noted in the PR description as a real service finding.
        $releaseDetail = '(no detail body on this 403)'
    }
    Write-Host "anne tries to release: HTTP $($releaseByAnne.StatusCode), $releaseDetail"
    Add-Check -Passed ($releaseByAnne.StatusCode -eq 403) -Description 'Scene 6: a trader cannot release a quarantine'

    $reject = Invoke-DemoApi -Method POST -Path "/api/quarantine/$brianOrderId/reject" -Token $tokens['sup-1']
    Write-Host "sup-1 rejects: HTTP $($reject.StatusCode), status $($reject.Body.status)"
    Add-Check -Passed ($reject.StatusCode -eq 200 -and $reject.Body.status -eq 'REJECTED') -Description 'Scene 6: a supervisor rejects the quarantine'
    Wait-ForEnter

    Write-Scene 'Scene 8: changing a limit'
    $overLegalMax = @{ key = 'OVER_LIMIT_BUCKET_PCT'; newValue = 40; reason = 'demo' }
    $overLegalMaxResult = Invoke-DemoApi -Method POST -Path '/api/limit-changes' -Token $tokens['sup-1'] -Body $overLegalMax
    Write-Host "HTTP $($overLegalMaxResult.StatusCode): $($overLegalMaxResult.Body.detail)"
    Add-Check -Passed ($overLegalMaxResult.StatusCode -eq 422 -and $overLegalMaxResult.Body.detail -eq 'OVER_LIMIT_BUCKET_PCT must be <= 25') `
        -Description 'Scene 8: a change past the legal maximum is refused'

    $tighten = @{ key = 'OVER_LIMIT_BUCKET_PCT'; newValue = 20; reason = 'demo tighten' }
    $tightenResult = Invoke-DemoApi -Method POST -Path '/api/limit-changes' -Token $tokens['sup-1'] -Body $tighten
    $limitChangeId = $tightenResult.Body.id
    Write-Host "HTTP $($tightenResult.StatusCode), direction $($tightenResult.Body.direction), required approvals $($tightenResult.Body.preview.requiredApprovals), status $($tightenResult.Body.status)"
    Add-Check -Passed ($tightenResult.StatusCode -eq 201 -and $tightenResult.Body.direction -eq 'TIGHTEN' -and $tightenResult.Body.preview.requiredApprovals -eq 1 -and $tightenResult.Body.status -eq 'PENDING') `
        -Description 'Scene 8: tightening to 20 needs 1 approval and starts PENDING'

    $selfApprove = Invoke-DemoApi -Method POST -Path "/api/limit-changes/$limitChangeId/approvals" -Token $tokens['sup-1']
    Write-Host "sup-1 approves own request: HTTP $($selfApprove.StatusCode), $($selfApprove.Body.detail)"
    Add-Check -Passed ($selfApprove.StatusCode -eq 403 -and $selfApprove.Body.detail -eq 'the requester may not approve their own request.') `
        -Description 'Scene 8: the requester cannot approve their own request'

    $secondApprove = Invoke-DemoApi -Method POST -Path "/api/limit-changes/$limitChangeId/approvals" -Token $tokens['sup-2']
    Write-Host "sup-2 approves: HTTP $($secondApprove.StatusCode), status $($secondApprove.Body.status)"
    Add-Check -Passed ($secondApprove.StatusCode -eq 200 -and $secondApprove.Body.status -eq 'ACTIVE') -Description 'Scene 8: the second approval activates the change'

    $limits = Invoke-DemoApi -Method GET -Path '/api/limits' -Token $tokens['sup-1']
    $overLimitNow = $limits.Body.OVER_LIMIT_BUCKET_PCT
    Write-Host "GET /api/limits: OVER_LIMIT_BUCKET_PCT is now $overLimitNow"
    Add-Check -Passed ([decimal]$overLimitNow -eq 20) -Description 'Scene 8: the active limit now reads 20'

    # -----------------------------------------------------------------------
    # 6. Summary
    # -----------------------------------------------------------------------

    Write-Host ''
    $failedChecks = @($Script:CheckResults | Where-Object { -not $_.Passed })
    if ($failedChecks.Count -eq 0) {
        Write-Host "All $($Script:CheckResults.Count) checks matched" -ForegroundColor Green
    }
    else {
        Write-Host "$($failedChecks.Count) of $($Script:CheckResults.Count) checks did not match:" -ForegroundColor Red
        foreach ($failedCheck in $failedChecks) {
            Write-Host "  - $($failedCheck.Description)" -ForegroundColor Red
        }
        $ExitCode = 1
    }
}
finally {
    Stop-DemoService
}

exit $ExitCode
