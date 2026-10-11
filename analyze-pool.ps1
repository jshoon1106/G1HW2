#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ServerLog,
    [ValidateRange(1,86400000)][int]$WarningThresholdMs = 6000
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
# Read-only diagnostic; never invokes LogVerifier or writes to the input log.
$samples = 0
$parseErrors = 0
$ambiguous = 0
$rollovers = 0
$previous = $null
$gaps = [Collections.Generic.List[long]]::new()
foreach ($line in [IO.File]::ReadLines((Resolve-Path -LiteralPath $ServerLog).Path)) {
    if ($line -notmatch '\|\s*POOL\s*\|') { continue }
    $samples++
    $parsed = [datetime]::MinValue
    if ($line -notmatch '^\[(\d{2}:\d{2}:\d{2}\.\d{3})\] SERVER \| POOL \|' -or
        -not [datetime]::TryParseExact($Matches[1], 'HH:mm:ss.fff', [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::None, [ref]$parsed)) {
        $parseErrors++
        $previous = $null
        continue
    }
    $current = [long]$parsed.TimeOfDay.TotalMilliseconds
    if ($null -ne $previous) {
        $gap = $current - $previous
        if ($gap -lt 0 -and $previous -ge 86340000 -and $current -lt 60000) {
            # Only the last/first minute boundary is treated as a midnight rollover.
            $gap += 86400000
            $rollovers++
        }
        if ($gap -le 0) { $ambiguous++ }
        $gaps.Add($gap)
    }
    $previous = $current
}
$longGaps = @($gaps | Where-Object { $_ -gt $WarningThresholdMs }).Count
$diagnostic = if ($parseErrors -gt 0) { 'FAIL' }
    elseif ($samples -lt 2) { 'INSUFFICIENT_DATA' }
    elseif ($gaps.Count -ne $samples - 1) { 'FAIL' }
    elseif ($ambiguous -gt 0 -or $longGaps -gt 0) { 'WARN' }
    else { 'PASS' }
$minimum = $maximum = $median = 'NA'
if ($gaps.Count -gt 0) {
    $sorted = @($gaps | Sort-Object)
    $minimum = $sorted[0]; $maximum = $sorted[-1]
    $mid = [int][math]::Floor($sorted.Count / 2)
    $median = if ($sorted.Count % 2) { $sorted[$mid] } else { ($sorted[$mid-1] + $sorted[$mid]) / 2.0 }
}
'POOL_ANALYSIS'
"  poolSamples=$samples"
"  poolIntervals=$($gaps.Count)"
"  poolMinGapMs=$minimum"
"  poolMaxGapMs=$maximum"
"  poolMedianGapMs=$median"
"  poolLongGaps=$longGaps"
"  warningThresholdMs=$WarningThresholdMs"
"  poolParseErrors=$parseErrors"
"  poolAmbiguousIntervals=$ambiguous"
"  poolMidnightRollovers=$rollovers"
"  poolDiagnostic=$diagnostic"
# Exit status reports diagnostic category only, independently of consistency PASS.
switch ($diagnostic) { 'FAIL' { exit 1 }; 'WARN' { exit 2 }; 'INSUFFICIENT_DATA' { exit 3 }; default { exit 0 } }
