# Starts StockPulse with private LiteLLM settings from a local .env file.
# Usage: powershell -ExecutionPolicy Bypass -File .\start-ai.ps1
$ErrorActionPreference = 'Stop'
$javaCandidates = @('C:\Program Files\Java\jdk-21.0.12','C:\Program Files\Java\jdk-17.0.10')
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
  $javaHome = $javaCandidates | Where-Object { Test-Path -LiteralPath (Join-Path $_ 'bin\java.exe') } | Select-Object -First 1
  if ($javaHome) { $env:JAVA_HOME = $javaHome; $env:Path = "$env:JAVA_HOME\bin;$env:Path" }
}
$envFile = Join-Path $PSScriptRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
  throw 'Missing .env. Copy .env.example to .env and add the private gateway values.'
}
Get-Content -LiteralPath $envFile | ForEach-Object {
  $line = $_.Trim()
  if ($line -and -not $line.StartsWith('#') -and $line.Contains('=')) {
    $parts = $line.Split('=', 2)
    Set-Item -Path "Env:$($parts[0].Trim())" -Value $parts[1].Trim()
  }
}
if (-not $env:LLM_API_KEY -or -not $env:LLM_COOKIE) {
  throw 'LLM_API_KEY and LLM_COOKIE must be present in .env.'
}
$env:STRATEGY = 'ai'
$backendDir = Join-Path $PSScriptRoot 'backend'
if (-not (Test-Path -LiteralPath (Join-Path $backendDir 'pom.xml'))) {
  # Supports the earlier archive layout where pom.xml and src were at the root.
  $backendDir = $PSScriptRoot
}
if (-not (Test-Path -LiteralPath (Join-Path $backendDir 'pom.xml'))) {
  throw 'Cannot find backend/pom.xml or pom.xml. Extract the complete StockPulse ZIP before starting.'
}
Push-Location $backendDir
try { mvn "-Dmaven.repo.local=$PSScriptRoot\.m2" spring-boot:run } finally { Pop-Location }
