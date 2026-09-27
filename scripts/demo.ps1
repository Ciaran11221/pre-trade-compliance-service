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
      5. Runs the scenes from docs/DEMO.md (1, 1b, 2, 6, 8 and 9) and checks each response against
         what that file says should happen. Each scene prints a plain-English caption ("What this
         shows" before, "So what" after), so a recording with no voice still explains itself.
      6. Stops the service it started (only that one) and leaves the database running.

    Usage:
        .\scripts\demo.ps1
        .\scripts\demo.ps1 -AutoAdvance 15
        .\scripts\demo.ps1 -NoPause

    By default the script pauses for Enter after each scene, so a live audience can read each
    result before the next one runs. -AutoAdvance <seconds> waits that long instead, with a
    countdown, for a recording made without touching the keyboard. -NoPause runs straight through.
#>

[CmdletBinding()]
param(
    [switch]$NoPause,
    [ValidateRange(0, 600)]
    [int]$AutoAdvance = 0
)

if ($NoPause -and $AutoAdvance -gt 0) {
    Write-Host 'Use -NoPause or -AutoAdvance, not both.' -ForegroundColor Red
    exit 1
}

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot

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

function Write-Caption {
    param(
        [string]$Label,
        [string]$Text
    )
    Write-Host "  $($Label): $Text" -ForegroundColor Yellow
}

function Wait-ForEnter {
    param([string]$Next = 'the next scene')
    if ($NoPause) {
        return
    }
    if ($AutoAdvance -gt 0) {
        for ($remaining = $AutoAdvance; $remaining -gt 0; $remaining--) {
            Write-Host -NoNewline ("`r{0} in {1,3}s " -f $Next, $remaining) -ForegroundColor DarkGray
            Start-Sleep -Seconds 1
        }
        # Blank the countdown line so the recording keeps only the scene output.
        Write-Host -NoNewline ("`r" + (' ' * ($Next.Length + 10)) + "`r")
        return
    }
    Write-Host "Press Enter for $Next" -ForegroundColor DarkGray
    [void](Read-Host)
}

# The diversification rule reports the post-trade over-5% total and the limit as percentages;
# read them from the response so a caption never states a number the run did not produce.
function Format-Pct {
    param($Value)
    if ($null -eq $Value) {
        return '?'
    }
    return ([decimal]$Value).ToString('0.##', [System.Globalization.CultureInfo]::InvariantCulture)
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
        # Windows PowerShell 5.1 has usually read the error body already by the time it throws, and
        # keeps it in ErrorDetails.Message; the response stream is then empty. Read that first.
        $bodyText = $null
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
            $bodyText = $_.ErrorDetails.Message
        }
        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
        }
        if (-not $bodyText -and $_.Exception.Response) {
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

# Runs one SQL statement through psql inside the demo database container, as someone with
# direct database access would. Windows PowerShell 5.1 turns a native command's stderr into
# error records when it is redirected, which 'Stop' would throw on; psql's refusal is the
# expected result here, so this relaxes that for the one call and returns the text instead.
function Invoke-DemoSql {
    param([string]$Sql)
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & docker compose exec -T postgres psql -U compliance -d compliance -v ON_ERROR_STOP=1 -c $Sql 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
    $firstLine = @($output | ForEach-Object { "$_" } | Where-Object { $_ -notmatch '^\s*$' }) | Select-Object -First 1
    return [pscustomobject]@{ ExitCode = $exitCode; FirstLine = $firstLine }
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
Push-Location $RepoRoot

try {
    # -----------------------------------------------------------------------
    # 2. Reset the demo data
    # -----------------------------------------------------------------------

    Write-Host 'Resetting the demo database...'
    & docker compose --progress quiet down -v
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'docker compose down -v failed; see the lines above.' -ForegroundColor Red
        exit 1
    }
    & docker compose --progress quiet up -d
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'docker compose up -d failed; see the lines above.' -ForegroundColor Red
        exit 1
    }

    $postgresReady = $false
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline) {
        # -h 127.0.0.1 asks over TCP: on a fresh volume Postgres first runs a setup-only server
        # with TCP switched off, which would answer a socket check before the real one is up.
        try {
            & docker compose exec -T postgres pg_isready -h 127.0.0.1 -U compliance 2>$null 1>$null
            if ($LASTEXITCODE -eq 0) {
                $postgresReady = $true
                break
            }
        }
        catch {
            # Container still starting; keep polling.
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
        if ($serviceProcess.HasExited) {
            break
        }
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
        Write-Host "The service did not become healthy within 120 seconds. Last 20 lines of $($LogPath):" -ForegroundColor Red
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

    Write-Host ''
    Write-Caption 'About this demo' 'a service that checks a fund''s orders before they reach a broker.'
    Write-Caption 'About this demo' 'each scene sends it real requests. The order''s result is the "status" on each HTTP line.'
    Write-Caption 'About this demo' 'green PASS lines are this script checking each answer is the expected one.'
    Write-Caption 'About this demo' 'two funds, HGF and WVF, each $1,000M. KSTL is a stock at $100 a share.'
    Wait-ForEnter -Next 'scene 1'

    # -----------------------------------------------------------------------
    # 5. Scenes
    # -----------------------------------------------------------------------

    Write-Scene 'Scene 1: anne buys KSTL for HGF'
    Write-Caption 'What this shows' 'US law (the 1940 Act) caps a fund''s positions of over 5% each at 25% of the fund in total.'
    Write-Caption 'What this shows' 'anne, a trader, buys $10M of KSTL for HGF.'
    $scene1Body = @{ clientOrderId = 'demo-hgf-1'; fundId = 1; side = 'BUY'; ticker = 'KSTL'; quantity = 100000 }
    $scene1 = Invoke-DemoApi -Method POST -Path '/api/orders' -Token $tokens['anne'] -Body $scene1Body
    $scene1OrderId = $scene1.Body.id
    $scene1SentAt = Get-Date
    $scene1Diversification = $scene1.Body.decision.ruleResults | Where-Object { $_.ruleName -eq 'diversification' } | Select-Object -First 1
    Write-Host "HTTP $($scene1.StatusCode), order id $scene1OrderId, status $($scene1.Body.status)"
    Write-Host "Diversification: $($scene1Diversification.reason)"
    Add-Check -Passed ($scene1.StatusCode -eq 201 -and $scene1.Body.status -eq 'PASS') -Description 'Scene 1: the buy passes'
    Write-Caption 'So what' "after the buy HGF's over-5% positions total $(Format-Pct $scene1Diversification.measuredValue)%, under the $(Format-Pct $scene1Diversification.limitValue)% limit, so the order is allowed."
    Wait-ForEnter -Next 'scene 1b'

    Write-Scene 'Scene 1b: anne sends the exact same request again'
    Write-Caption 'What this shows' 'networks drop replies, so a trading system may send the same order twice.'
    $scene1b = Invoke-DemoApi -Method POST -Path '/api/orders' -Token $tokens['anne'] -Body $scene1Body
    Write-Host "First order id $scene1OrderId, second response order id $($scene1b.Body.id)"
    Add-Check -Passed ($scene1b.Body.id -eq $scene1OrderId) -Description 'Scene 1b: the retry returns the same order, not a new one'
    Write-Caption 'So what' "order $scene1OrderId both times: the retry did not create a second order."
    Wait-ForEnter -Next 'scene 2'

    Write-Scene 'Scene 2: the same buy for WVF, already near the limit'
    Write-Caption 'What this shows' 'the exact same buy, for WVF, a fund that already has more money in big positions.'
    $scene2Body = @{ clientOrderId = 'demo-wvf-1'; fundId = 2; side = 'BUY'; ticker = 'KSTL'; quantity = 100000 }
    $scene2 = Invoke-DemoApi -Method POST -Path '/api/orders' -Token $tokens['anne'] -Body $scene2Body
    $scene2Diversification = $scene2.Body.decision.ruleResults | Where-Object { $_.ruleName -eq 'diversification' } | Select-Object -First 1
    $scene2OrderId = $scene2.Body.id
    Write-Host "HTTP $($scene2.StatusCode), order id $scene2OrderId, status $($scene2.Body.status)"
    Write-Host "Diversification: $($scene2Diversification.reason)"
    Add-Check -Passed ($scene2.Body.status -eq 'BLOCK') -Description 'Scene 2: the same buy blocks for WVF'
    Write-Caption 'So what' "WVF would reach $(Format-Pct $scene2Diversification.measuredValue)%, over the $(Format-Pct $scene2Diversification.limitValue)% limit, so it is blocked."
    Write-Caption 'So what' 'same trade, different fund, different answer: the rule looks at the whole fund.'
    Wait-ForEnter -Next 'scene 6'

    Write-Scene 'Scene 6: brian sends anne''s scene 1 order under a new id'
    if (((Get-Date) - $scene1SentAt).TotalMinutes -ge 5) {
        Write-Host 'WARNING: more than 5 minutes since scene 1, so this will not be held as a duplicate. Run the demo again to see it.' -ForegroundColor Red
    }
    Write-Caption 'What this shows' 'brian, a second trader, sends the same buy anne sent a moment ago.'
    Write-Caption 'What this shows' 'two people filling one request is a common and costly mistake.'
    $scene6Body = @{ clientOrderId = 'demo-brian-1'; fundId = 1; side = 'BUY'; ticker = 'KSTL'; quantity = 100000 }
    $scene6 = Invoke-DemoApi -Method POST -Path '/api/orders' -Token $tokens['brian'] -Body $scene6Body
    $brianOrderId = $scene6.Body.id
    Write-Host "HTTP $($scene6.StatusCode), order id $brianOrderId, status $($scene6.Body.status)"
    Write-Host "Quarantine reason $($scene6.Body.quarantine.reason), matched order id $($scene6.Body.quarantine.matchedOrderId)"
    Add-Check -Passed ($scene6.Body.status -eq 'QUARANTINED' -and $scene6.Body.quarantine.reason -eq 'POSSIBLE_DUPLICATE' -and $scene6.Body.quarantine.matchedOrderId -eq $scene1OrderId) `
        -Description 'Scene 6: quarantined as a possible duplicate, matched to scene 1'

    $releaseByAnne = Invoke-DemoApi -Method POST -Path "/api/quarantine/$brianOrderId/release" -Token $tokens['anne']
    $releaseDetail = $releaseByAnne.Body.detail
    Write-Host "anne tries to release: HTTP $($releaseByAnne.StatusCode), $releaseDetail"
    Add-Check -Passed ($releaseByAnne.StatusCode -eq 403) -Description 'Scene 6: a trader cannot release a quarantine'

    $reject = Invoke-DemoApi -Method POST -Path "/api/quarantine/$brianOrderId/reject" -Token $tokens['sup-1']
    Write-Host "sup-1 rejects: HTTP $($reject.StatusCode), status $($reject.Body.status)"
    Add-Check -Passed ($reject.StatusCode -eq 200 -and $reject.Body.status -eq 'REJECTED') -Description 'Scene 6: a supervisor rejects the quarantine'
    Write-Caption 'So what' "brian's order was held, not sent, and matched to anne's order $scene1OrderId."
    Write-Caption 'So what' 'a trader cannot release it; a supervisor decides, and here sup-1 rejects it.'
    Wait-ForEnter -Next 'scene 8'

    Write-Scene 'Scene 8: changing a limit'
    Write-Caption 'What this shows' 'the firm sets its own limits at or below the law''s, and every change needs a second person''s approval.'
    Write-Caption 'What this shows' 'sup-1, a supervisor, first asks for 40%, then for 20%.'
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
    Write-Caption 'So what' 'no approval can pass the legal limit: that lives in code, so 40% is refused outright.'
    Write-Caption 'So what' "tightening needs a second supervisor: sup-1 cannot approve their own request; sup-2's approval makes $(Format-Pct $overLimitNow)% active."
    Wait-ForEnter -Next 'scene 9'

    Write-Scene 'Scene 9: someone with database access tries to rewrite history'
    Write-Caption 'What this shows' "scene 2's order $scene2OrderId was blocked. Someone who can type SQL straight into the database"
    Write-Caption 'What this shows' 'tries to change that decision to PASS, then tries to delete it.'

    $tamperSql = "UPDATE decision SET outcome = 'PASS' WHERE order_id = $scene2OrderId"
    $tamper = Invoke-DemoSql -Sql $tamperSql
    Write-Host "$($tamperSql): $($tamper.FirstLine)"
    Add-Check -Passed ($tamper.ExitCode -ne 0 -and $tamper.FirstLine -match 'insert-only') -Description 'Scene 9: the database refuses to change the decision'

    $eraseSql = "DELETE FROM decision WHERE order_id = $scene2OrderId"
    $erase = Invoke-DemoSql -Sql $eraseSql
    Write-Host "$($eraseSql): $($erase.FirstLine)"
    Add-Check -Passed ($erase.ExitCode -ne 0 -and $erase.FirstLine -match 'insert-only') -Description 'Scene 9: the database refuses to delete the decision'

    $scene2Now = Invoke-DemoApi -Method GET -Path "/api/orders/$scene2OrderId" -Token $tokens['anne']
    Write-Host "GET /api/orders/$($scene2OrderId): HTTP $($scene2Now.StatusCode), status $($scene2Now.Body.status)"
    Add-Check -Passed ($scene2Now.StatusCode -eq 200 -and $scene2Now.Body.status -eq 'BLOCK') -Description 'Scene 9: the order still reads BLOCK'
    Write-Caption 'So what' 'orders, decisions, approvals and events can only be added to, never changed or removed.'
    Write-Caption 'So what' 'the database itself refuses, so a bug in the service or a hand-typed fix cannot rewrite them.'

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
    Pop-Location
}

exit $ExitCode
