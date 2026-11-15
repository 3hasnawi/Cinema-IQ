param([string]$Output = "$PSScriptRoot/../src/CinemaIQ.Windows/appsettings.json")
$ErrorActionPreference = 'Stop'
$config = [ordered]@{ TmdbApiKey = "$env:TMDB_API_KEY"; OmdbApiKey = "$env:OMDB_API_KEY"; YoutubeApiKey = "$env:YOUTUBE_API_KEY"; FirebaseApiKey = "$env:FIREBASE_WINDOWS_API_KEY"; FirebaseProjectId = "$env:FIREBASE_PROJECT_ID" }
$existing = Join-Path $PSScriptRoot '../../app/src/main/assets/firebase-config.json'
if (Test-Path $existing) {
  $firebase = Get-Content -Raw $existing | ConvertFrom-Json
  if (-not $config.FirebaseApiKey) { $config.FirebaseApiKey = $firebase.client[0].api_key[0].current_key }
  if (-not $config.FirebaseProjectId) { $config.FirebaseProjectId = $firebase.project_info.project_id }
}
$config | ConvertTo-Json | Set-Content -Encoding utf8 $Output
Write-Host 'Windows client configuration generated (values are not logged).'
