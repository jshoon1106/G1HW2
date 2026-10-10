#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('Check','Build','Server','Clients','Collect','Verify')]
    [string]$Action = 'Check',
    [string]$RunId,
    [Alias('Profile')][ValidateSet('final','dev')][string]$RunProfile = 'final',
    [string]$Bind,
    [string]$ServerHost,
    [ValidateRange(1,65535)][int]$Port = 5000,
    [ValidateRange(1,30)][int]$ClientCount = 30,
    [ValidateRange(1,5000)][int]$Requests = 5000,
    [ValidateRange(0,1000)][int]$MinMs = 200,
    [ValidateRange(0,1000)][int]$MaxMs = 1000,
    [long]$Seed = 20261009,
    [string]$LogDir,
    [switch]$Standby
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$g1Root = $PSScriptRoot
$g1Jar = Join-Path $g1Root 'seat-booking.jar'
$g1JavaCommand = Get-Command java -CommandType Application -ErrorAction SilentlyContinue
$g1Java = if ($g1JavaCommand) { $g1JavaCommand.Source } else { $null }

function Invoke-Native {
    param([string]$Tool, [string[]]$Arguments)
    & $Tool @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Native command failed ($LASTEXITCODE): $Tool" }
}

function Require-Run {
    if ($RunId -notmatch '^[A-Za-z0-9_-]{1,64}$') { throw 'Specify -RunId (letters, digits, underscore, hyphen; maximum 64).' }
    if ($MaxMs -lt $MinMs) { throw '-MaxMs must be >= -MinMs.' }
    if ($RunProfile -eq 'final' -and ($ClientCount -ne 30 -or $Requests -ne 5000 -or $MinMs -ne 200 -or $MaxMs -ne 1000)) {
        throw 'Final profile requires 30 clients x 5000 requests with 200..1000 ms.'
    }
}

function Get-LogPath {
    if ($LogDir) { return [IO.Path]::GetFullPath($LogDir) }
    if ($Standby) { return Join-Path $g1Root 'logs' }
    return Join-Path $g1Root "logs/$RunId"
}

function Get-WorkloadArguments {
    param([string]$TargetLogDir)
    return @('--port',"$Port",'--run-id',$RunId,'--profile',$RunProfile,'--clients',"$ClientCount",'--requests',"$Requests",'--min-ms',"$MinMs",'--max-ms',"$MaxMs",'--log-dir',$TargetLogDir)
}

function Build-Project {
    $g1Build = Join-Path $g1Root ('out/build-' + [Guid]::NewGuid().ToString('N'))
    $g1Classes = Join-Path $g1Build 'classes'
    New-Item -ItemType Directory -Path $g1Classes -Force | Out-Null
    $g1Sources = @(Get-ChildItem -LiteralPath (Join-Path $g1Root 'src') -Filter '*.java' -File | ForEach-Object FullName)
    Invoke-Native -Tool (Get-Command javac -CommandType Application).Source -Arguments (@('--release','17','-encoding','UTF-8','-d',$g1Classes) + $g1Sources)
    Invoke-Native -Tool (Get-Command jar -CommandType Application).Source -Arguments @('--create','--file',$g1Jar,'-C',$g1Classes,'.')
    Write-Host "Built: $g1Jar"
}

function Verify-Logs {
    param([string]$Directory,[string]$VerifyProfile)
    Invoke-Native -Tool $g1Java -Arguments @('-cp',$g1Jar,'LogVerifier','--log-dir',$Directory,'--profile',$VerifyProfile)
}

function Read-LogLine {
    param([IO.Stream]$Stream)
    $g1Line = [Collections.Generic.List[byte]]::new()
    while ($g1Line.Count -lt 512) {
        $g1Byte = $Stream.ReadByte()
        if ($g1Byte -lt 0) { throw [IO.EndOfStreamException]::new('Server closed the log connection.') }
        if ($g1Byte -eq 10) { return [Text.Encoding]::UTF8.GetString($g1Line.ToArray()) }
        $g1Line.Add([byte]$g1Byte)
    }
    throw [IO.InvalidDataException]::new('Log header exceeds 512 bytes.')
}
function Receive-ServerLog {
    param([IO.Stream]$Stream,[string]$ExpectedRun,[string]$Directory,[DateTime]$Deadline)
    $g1Header = Read-LogLine -Stream $Stream
    if ($g1Header -match '^LOG_ERROR ([A-Z_]+)$') { throw [IO.IOException]::new($g1Header) }
    if ($g1Header -notmatch '^LOG_BEGIN ([A-Za-z0-9_-]{1,64}) ([0-9]{1,12})$' -or $Matches[1] -ne $ExpectedRun) {
        throw [IO.InvalidDataException]::new('Wrong log run or malformed header.')
    }
    $g1Length = [long]$Matches[2]
    if ($g1Length -le 0 -or $g1Length -gt 1GB) { throw [IO.InvalidDataException]::new('Invalid log size (maximum 1 GiB).') }
    $g1Destination = Join-Path $Directory 'Server.txt'
    if (Test-Path -LiteralPath $g1Destination) { throw 'Server.txt already exists; preserve the collected log.' }
    New-Item -ItemType Directory -Path $Directory -Force | Out-Null
    $g1Partial = Join-Path $Directory ('Server.'+[Guid]::NewGuid().ToString('N')+'.part')
    $g1File = $null; $g1Hash = [Security.Cryptography.SHA256]::Create()
    try {
        $g1File = [IO.File]::Open($g1Partial,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        $g1Buffer = [byte[]]::new(65536); $g1Remaining = $g1Length
        while ($g1Remaining -gt 0) {
            if ([DateTime]::UtcNow -ge $Deadline) { throw [IO.IOException]::new('Log collection exceeded 10 minutes.') }
            $g1Read = $Stream.Read($g1Buffer,0,[int][Math]::Min($g1Buffer.Length,$g1Remaining))
            if ($g1Read -le 0) { throw [IO.EndOfStreamException]::new('Incomplete Server.txt.') }
            $g1File.Write($g1Buffer,0,$g1Read)
            $null = $g1Hash.TransformBlock($g1Buffer,0,$g1Read,$g1Buffer,0)
            $g1Remaining -= $g1Read
        }
        $null = $g1Hash.TransformFinalBlock([byte[]]::new(0),0,0)
        $g1Digest = [BitConverter]::ToString($g1Hash.Hash).Replace('-','').ToLowerInvariant()
        $g1Trailer = Read-LogLine -Stream $Stream
        if ($g1Trailer -ne ('LOG_END '+$g1Digest)) { throw [IO.InvalidDataException]::new('Server.txt SHA-256 mismatch or missing end marker.') }
        $g1File.Flush(); $g1File.Dispose(); $g1File=$null
        [IO.File]::Move($g1Partial,$g1Destination)
        return [pscustomobject]@{Path=$g1Destination;Bytes=$g1Length;Sha256=$g1Digest}
    }
    finally {
        if ($g1File) { $g1File.Dispose() }
        $g1Hash.Dispose()
        if (Test-Path -LiteralPath $g1Partial) { Remove-Item -LiteralPath $g1Partial }
    }
}
function Collect-ServerLog {
    if (-not $ServerHost) { throw 'Specify -ServerHost.' }
    if ($RunId -notmatch '^[A-Za-z0-9_-]{1,64}$' -or $RunId -eq 'auto') { throw 'Specify a completed -RunId.' }
    $g1Directory = Get-LogPath
    if (Test-Path -LiteralPath (Join-Path $g1Directory 'Server.txt')) { throw 'Server.txt already exists; do not overwrite it.' }
    $g1RetryUntil=[DateTime]::UtcNow.AddSeconds(30); $g1Deadline=[DateTime]::UtcNow.AddMinutes(10)
    while ($true) {
        $g1Socket=[Net.Sockets.TcpClient]::new()
        try {
            $g1Socket.ReceiveTimeout=30000; $g1Socket.SendTimeout=5000
            $g1Connect=$g1Socket.ConnectAsync($ServerHost,$Port)
            if (-not $g1Connect.Wait(5000)) { throw [IO.IOException]::new('Log connection timeout.') }
            $g1Stream=$g1Socket.GetStream()
            $g1Request=[Text.Encoding]::UTF8.GetBytes("GET_LOG $RunId`n")
            $g1Stream.Write($g1Request,0,$g1Request.Length)
            $g1Result=Receive-ServerLog -Stream $g1Stream -ExpectedRun $RunId -Directory $g1Directory -Deadline $g1Deadline
            Write-Host "Server log collected: $($g1Result.Path) ($($g1Result.Bytes) bytes, SHA-256 verified)"
            return
        }
        catch {
            if ($_.Exception -is [IO.InvalidDataException] -or [DateTime]::UtcNow -ge $g1RetryUntil) { throw }
            Start-Sleep -Milliseconds 500
        }
        finally { $g1Socket.Dispose() }
    }
}
function Check-Environment {
    if (-not $g1Java) { throw 'Java 17 or newer is required; install Java and add its bin directory to PATH.' }
    $g1Version=(& $g1Java -version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $g1Version -notmatch 'version\s+"?(\d+)' -or [int]$Matches[1] -lt 17) { throw 'Java 17 or newer is required.' }
    if (-not (Test-Path -LiteralPath $g1Jar -PathType Leaf)) { throw 'Missing seat-booking.jar; build with JDK 17 or use the supplied JAR.' }
    $g1Probe=Join-Path $g1Root ('.write-check-'+[Guid]::NewGuid().ToString('N'))
    try { $g1ProbeFile=[IO.File]::Open($g1Probe,[IO.FileMode]::CreateNew); $g1ProbeFile.Dispose() }
    finally { if (Test-Path -LiteralPath $g1Probe) { Remove-Item -LiteralPath $g1Probe } }
    Write-Host "Execution requirements PASS: Java 17+, PowerShell 7, supplied JAR, writable project directory."
    $g1BuildTools=@('javac','jar' | Where-Object { -not (Get-Command $_ -CommandType Application -ErrorAction SilentlyContinue) })
    if ($g1BuildTools.Count) { Write-Host "Build only: missing $($g1BuildTools -join ', '); JDK installation required." }
    Write-Host 'No network connection or booking request was made.'
}

Push-Location $g1Root
try {
    if ($Action -ne 'Check' -and -not $g1Java) { throw 'Java 17 or newer is required on PATH.' }
    if ($Standby) {
        if ($Action -ne 'Server') { throw '-Standby applies only to Server.' }
        if ($RunId -and $RunId -ne 'auto') { throw 'Standby receives runId from Client; omit -RunId.' }
        $RunId='auto'
    }
    switch ($Action) {
        'Check' { Check-Environment }
        'Build' { Build-Project }
        'Server' { Require-Run; if (-not $Bind) { throw 'Specify -Bind.' }; Invoke-Native -Tool $g1Java -Arguments (@('-cp',$g1Jar,'BookingServer','--bind',$Bind) + (Get-WorkloadArguments (Get-LogPath))) }
        'Clients' {
            if (-not $RunId) { $RunId=$RunProfile+'-'+[DateTimeOffset]::UtcNow.ToOffset([TimeSpan]::FromHours(9)).ToString('yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,16) }
            Require-Run; if (-not $ServerHost) { throw 'Specify -ServerHost.' }; Write-Host "Client runId: $RunId"
            Invoke-Native -Tool $g1Java -Arguments (@('-cp',$g1Jar,'BookingClients','--host',$ServerHost,'--seed',"$Seed") + (Get-WorkloadArguments (Get-LogPath)))
            if ($RunProfile -eq 'final') {
                Write-Host 'Booking completed. Collecting the closed server log over TCP.'
                try { Collect-ServerLog; Verify-Logs -Directory (Get-LogPath) -VerifyProfile $RunProfile }
                catch { throw "Booking completed; collection/verification failed. Preserve Client logs and retry Collect/Verify with runId=$RunId. $($_.Exception.Message)" }
            }
        }
        'Collect' { Collect-ServerLog }
        'Verify' { Require-Run; Verify-Logs -Directory (Get-LogPath) -VerifyProfile $RunProfile }
    }
}
catch { Write-Error -Message $_ -ErrorAction Continue; exit 1 }
finally { Pop-Location }
