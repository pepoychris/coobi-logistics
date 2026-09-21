#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Verifies the deployment contract of MVP-10: the Compose stack, the Dockerfiles and the
    addresses the stack sets inside its network.

.DESCRIPTION
    The default run reads `compose.yml` through `docker compose config` and checks the
    Dockerfiles as text, so it starts nothing and needs no running daemon - only the docker CLI
    with the Compose plugin. `-Stack` additionally builds and starts the whole stack, waits for
    every service to report `healthy` and probes the endpoints a reader would open.

    docs/deployment.md is the document this script checks; every assertion below is a sentence
    of it.

.EXAMPLE
    pwsh -File infrastructure/scripts/verify-deployment.ps1

.EXAMPLE
    pwsh -File infrastructure/scripts/verify-deployment.ps1 -Stack
#>
[CmdletBinding()]
param(
    # Build and start the stack, wait for it to become healthy and probe it over HTTP.
    [switch] $Stack,

    # How long to wait, in seconds, for the stack to become healthy and for the two scrape
    # targets to turn `up`. A first `--build` run of the three Maven images can take minutes.
    [int] $TimeoutSeconds = 600
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$script:failures = @()
$script:compose = $null

function Write-Ok { param([string] $Message) Write-Host "ok    $Message" }
function Write-Skip { param([string] $Message) Write-Host "skip  $Message" -ForegroundColor Yellow }
function Write-Fail {
    param([string] $Message)
    Write-Host "FAIL  $Message" -ForegroundColor Red
    $script:failures += $Message
}

function Assert-True { param([bool] $Condition, [string] $Message) if (-not $Condition) { throw $Message } }
function Assert-Equal { param($Actual, $Expected, [string] $What) if ($Actual -ne $Expected) { throw "$What is '$Actual', expected '$Expected'" } }
function Assert-Match { param([string] $Actual, [string] $Pattern, [string] $What) if ($Actual -notmatch $Pattern) { throw "$What does not match '$Pattern'" } }

function Invoke-Check {
    param([string] $Name, [scriptblock] $Body)
    try {
        & $Body
        Write-Ok $Name
    }
    catch {
        Write-Fail "$Name - $($_.Exception.Message)"
    }
}

function Get-FileText { param([string] $Path) (Get-Content -LiteralPath $Path -Raw) }

Write-Host "Repository: $repoRoot`n"

# ---------------------------------------------------------------------------------------------
# The Dockerfiles (MVP-10.1)
# ---------------------------------------------------------------------------------------------

$javaImages = @(
    @{ Name = 'event-generator'; Path = 'services/event-generator/Dockerfile'; Port = '8080' }
    @{ Name = 'stream-processor'; Path = 'services/stream-processor/Dockerfile'; Port = '8081' }
    @{ Name = 'logistics-api'; Path = 'services/logistics-api/Dockerfile'; Port = '8082' }
)

foreach ($image in $javaImages) {
    $path = Join-Path $repoRoot $image.Path
    $name = "services/$($image.Name)/Dockerfile"

    Invoke-Check "$name exists" {
        Assert-True (Test-Path -LiteralPath $path) 'the file is missing'
        $dockerignore = Join-Path (Split-Path -Parent $path) '.dockerignore'
        Assert-True (Test-Path -LiteralPath $dockerignore) '.dockerignore is missing'
        Assert-True ((Get-FileText $dockerignore) -match '(?m)^target/') '.dockerignore does not exclude the build output'
    }

    Invoke-Check "$name is a multi-stage build with build tooling only in the builder" {
        $text = Get-FileText $path
        $stages = [regex]::Matches($text, '(?m)^FROM\s+(\S+)')
        Assert-True ($stages.Count -ge 2) "it declares $($stages.Count) stage(s)"
        Assert-Match $stages[0].Groups[1].Value '^maven:' 'the builder stage is not a Maven image'
        Assert-Match $stages[$stages.Count - 1].Groups[1].Value 'jre' 'the runtime stage is not a JRE image'
        Assert-True ($text -match '(?m)^USER\s+app\s*$') 'it does not run as the unprivileged user `app`'
        Assert-True ($text -match 'adduser') 'it does not create the unprivileged user'
        Assert-True ($text -match '(?m)^HEALTHCHECK') 'it declares no HEALTHCHECK'
        Assert-True ($text -match 'actuator/health') 'its health check does not use the Actuator health endpoint'
        Assert-True ($text -match '(?m)^ENTRYPOINT\s+\[\s*"java"') 'its entry point is not an exec form of java, so it would not be PID 1'
    }

    Invoke-Check "$name listens on and publishes $($image.Port)" {
        $text = Get-FileText $path
        Assert-Match $text "(?m)^ENV SERVER_PORT=$($image.Port)\s*$" 'the declared server port is not the one of the service'
        Assert-Match $text "(?m)^EXPOSE $($image.Port)\s*$" 'the exposed port is not the one of the service'
    }
}

Invoke-Check 'frontend/Dockerfile builds the bundle and serves it without root' {
    $path = Join-Path $repoRoot 'frontend/Dockerfile'
    Assert-True (Test-Path -LiteralPath $path) 'the file is missing'
    $text = Get-FileText $path
    $stages = [regex]::Matches($text, '(?m)^FROM\s+(\S+)')
    Assert-True ($stages.Count -ge 2) "it declares $($stages.Count) stage(s)"
    Assert-Match $stages[0].Groups[1].Value '^node:' 'the builder stage is not a Node image'
    Assert-Match $stages[$stages.Count - 1].Groups[1].Value 'nginx-unprivileged' 'the runtime stage is not the unprivileged nginx image'
    Assert-True ($text -match 'npm ci') 'it does not install from the lockfile'
    Assert-True ($text -match 'npm run build') 'it does not build the bundle'
    Assert-True ($text -match '(?m)^USER\s+nginx\s*$') 'it does not run as the unprivileged nginx user'
    Assert-True ($text -match '(?m)^HEALTHCHECK') 'it declares no HEALTHCHECK'
    Assert-True ($text -match 'default\.conf\.template') 'it does not install the nginx template'
}

Invoke-Check 'frontend nginx template proxies the API and streams it' {
    $path = Join-Path $repoRoot 'frontend/nginx/default.conf.template'
    Assert-True (Test-Path -LiteralPath $path) 'the template is missing'
    $text = Get-FileText $path
    Assert-True ($text -match '\$\{API_PROXY_TARGET\}') 'it does not read API_PROXY_TARGET'
    Assert-True ($text -match 'proxy_buffering\s+off') 'it buffers the Server-Sent Events responses'
    Assert-True ($text -match 'resolver\s+127\.0\.0\.11') 'it does not resolve the API through the DNS of the network'
}

# ---------------------------------------------------------------------------------------------
# The Compose stack (MVP-10.2 and MVP-10.3)
# ---------------------------------------------------------------------------------------------

$compose = $null

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Skip 'docker compose config - the docker CLI is not on the PATH'
}
else {
    # The stack fails fast without the database credentials, which is its documented behaviour.
    # The check does not need the values of a reader, so it supplies throwaway ones when there
    # is no `.env` to read them from; a shell variable wins over the file, exactly as Compose
    # documents, and none of the checks below depends on the value.
    if (-not (Test-Path -LiteralPath (Join-Path $repoRoot '.env'))) {
        $env:POSTGRES_DB = 'logistics'
        $env:POSTGRES_USER = 'logistics'
        $env:POSTGRES_PASSWORD = 'verify-deployment'
    }

    $json = $null
    Push-Location $repoRoot
    try {
        $json = (& docker compose config --format json) | Out-String
        $configExitCode = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }

    Invoke-Check 'docker compose config resolves the stack' {
        Assert-True ($configExitCode -eq 0) 'the command failed; run it by hand to see the error'
        $script:compose = $json | ConvertFrom-Json -Depth 40
    }
}

$compose = $script:compose

if (-not $compose) {
    Write-Skip 'the declared services, dependencies and addresses - the Compose file did not resolve'
}
else {
    $expectedServices = @('kafka', 'postgres', 'prometheus', 'event-generator', 'stream-processor', 'logistics-api', 'frontend')

    Invoke-Check 'the stack declares exactly the seven services of the milestone' {
        $declared = @($compose.services.PSObject.Properties.Name)
        $missing = @($expectedServices | Where-Object { $declared -notcontains $_ })
        $extra = @($declared | Where-Object { $expectedServices -notcontains $_ })
        Assert-True ($missing.Count -eq 0) "missing: $($missing -join ', ')"
        Assert-True ($extra.Count -eq 0) "unexpected: $($extra -join ', ')"
    }

    Invoke-Check 'the four application services build from their own directory' {
        $expectedContext = @{
            'event-generator' = 'services/event-generator'
            'stream-processor' = 'services/stream-processor'
            'logistics-api' = 'services/logistics-api'
            'frontend' = 'frontend'
        }
        foreach ($service in $expectedContext.Keys) {
            $context = $compose.services.$service.build.context
            Assert-True ($null -ne $context) "$service declares no build context"
            $resolved = (Resolve-Path -LiteralPath $context).Path
            Assert-True (Test-Path -LiteralPath (Join-Path $resolved 'Dockerfile')) "$service has no Dockerfile in its context"
            $expected = (Resolve-Path -LiteralPath (Join-Path $repoRoot $expectedContext[$service])).Path
            Assert-Equal $resolved $expected "$service builds from"
        }
    }

    Invoke-Check 'every application service is restarted unless it is stopped' {
        foreach ($service in @('event-generator', 'stream-processor', 'logistics-api', 'frontend')) {
            Assert-Equal $compose.services.$service.restart 'unless-stopped' "$service restart policy"
        }
    }

    Invoke-Check 'every service of the pipeline is published on the loopback interface only' {
        $expectedPorts = @{
            'kafka' = 9092; 'postgres' = 5432; 'prometheus' = 9090
            'event-generator' = 8080; 'stream-processor' = 8081; 'logistics-api' = 8082; 'frontend' = 5173
        }
        foreach ($service in $expectedPorts.Keys) {
            $ports = @($compose.services.$service.ports)
            Assert-True ($ports.Count -eq 1) "$service publishes $($ports.Count) ports"
            Assert-Equal $ports[0].host_ip '127.0.0.1' "$service host interface"
            Assert-Equal "$($ports[0].published)" "$($expectedPorts[$service])" "$service published port"
        }
    }

    Invoke-Check 'the dependencies of the services are health conditions' {
        $expected = @{
            'event-generator' = @{ 'kafka' = 'service_healthy' }
            'stream-processor' = @{ 'kafka' = 'service_healthy'; 'postgres' = 'service_healthy' }
            'logistics-api' = @{ 'postgres' = 'service_healthy'; 'stream-processor' = 'service_healthy' }
            'frontend' = @{ 'logistics-api' = 'service_healthy' }
        }
        foreach ($service in $expected.Keys) {
            $declared = @($compose.services.$service.depends_on.PSObject.Properties.Name)
            Assert-Equal $declared.Count $expected[$service].Count "$service number of dependencies"
            foreach ($dependency in $expected[$service].Keys) {
                Assert-True ($declared -contains $dependency) "$service does not depend on $dependency"
                Assert-Equal $compose.services.$service.depends_on.$dependency.condition $expected[$service][$dependency] "$service -> $dependency condition"
            }
        }
    }

    Invoke-Check 'the services are given the addresses of the network, not those of the host' {
        Assert-Equal $compose.services.'event-generator'.environment.KAFKA_BOOTSTRAP_SERVERS 'kafka:29092' 'event-generator KAFKA_BOOTSTRAP_SERVERS'
        Assert-Equal $compose.services.'stream-processor'.environment.KAFKA_BOOTSTRAP_SERVERS 'kafka:29092' 'stream-processor KAFKA_BOOTSTRAP_SERVERS'
        Assert-Match $compose.services.'stream-processor'.environment.SPRING_DATASOURCE_URL '^jdbc:postgresql://postgres:5432/' 'stream-processor SPRING_DATASOURCE_URL'
        Assert-Match $compose.services.'logistics-api'.environment.SPRING_DATASOURCE_URL '^jdbc:postgresql://postgres:5432/' 'logistics-api SPRING_DATASOURCE_URL'
        Assert-Equal $compose.services.'logistics-api'.environment.STREAM_PROCESSOR_METRICS_URL 'http://stream-processor:8081/actuator/metrics' 'logistics-api STREAM_PROCESSOR_METRICS_URL'
        Assert-Equal $compose.services.frontend.environment.API_PROXY_TARGET 'http://logistics-api:8082' 'frontend API_PROXY_TARGET'
    }

    Invoke-Check 'Prometheus scrapes the two services by name' {
        # The names are written out in the scrape configuration on purpose: Prometheus expands
        # `${VAR}` references in `external_labels` and nowhere else, so a target that held one
        # would be scraped as its own name rather than resolved.
        $scrape = Get-FileText (Join-Path $repoRoot 'infrastructure/prometheus/prometheus.yml')
        Assert-True ($scrape -match 'targets:\s*\["event-generator:8080"\]') 'the scrape configuration does not target the generator by its service name'
        Assert-True ($scrape -match 'targets:\s*\["stream-processor:8081"\]') 'the scrape configuration does not target the processor by its service name'
        Assert-True ($scrape -notmatch 'targets:\s*\["\$') 'a scrape target still holds a variable reference, which Prometheus does not expand there'
    }

    Invoke-Check 'the shutdown of the services is not cut short by the default grace period' {
        foreach ($service in @('event-generator', 'stream-processor', 'logistics-api')) {
            Assert-Equal $compose.services.$service.stop_grace_period '30s' "$service stop grace period"
        }
    }
}

# ---------------------------------------------------------------------------------------------
# The running stack (-Stack)
# ---------------------------------------------------------------------------------------------

if ($Stack) {
    Write-Host ''
    Write-Host 'Starting the stack, this can take minutes on a first build...'

    Push-Location $repoRoot
    try {
        & docker compose up --build -d
        if ($LASTEXITCODE -ne 0) {
            Write-Fail 'docker compose up --build -d'
            Write-Host "`nLogs:" -ForegroundColor Yellow
            & docker compose logs --tail 50
        }
        else {
            $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
            $services = @('kafka', 'postgres', 'prometheus', 'event-generator', 'stream-processor', 'logistics-api', 'frontend')

            $health = @{}
            do {
                $health = @{}
                foreach ($service in $services) {
                    $container = @(& docker compose ps -q $service) | Select-Object -First 1
                    if (-not $container) {
                        $health[$service] = 'missing'
                        continue
                    }
                    $status = (& docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $container) | Select-Object -First 1
                    $health[$service] = "$status".Trim()
                }
                $pending = @($services | Where-Object { $health[$_] -ne 'healthy' })
                if ($pending.Count -gt 0 -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 5 }
            } while ($pending.Count -gt 0 -and (Get-Date) -lt $deadline)

            Invoke-Check 'every service of the stack reports healthy' {
                foreach ($service in $services) {
                    Assert-Equal $health[$service] 'healthy' "$service health"
                }
            }

            Invoke-Check 'the Actuator health of the three services answers UP' {
                foreach ($port in 8080, 8081, 8082) {
                    $response = Invoke-RestMethod -Uri "http://localhost:$port/actuator/health" -TimeoutSec 10
                    Assert-Equal $response.status 'UP' "the health of the service on $port"
                }
            }

            Invoke-Check 'the dashboard serves its page and proxies the API' {
                $page = Invoke-WebRequest -Uri 'http://localhost:5173/' -UseBasicParsing -TimeoutSec 10
                Assert-Equal $page.StatusCode 200 'the status of the dashboard'
                $proxied = Invoke-WebRequest -Uri 'http://localhost:5173/api/v1/vehicles' -UseBasicParsing -TimeoutSec 10
                Assert-Equal $proxied.StatusCode 200 'the status of the API read through the dashboard'
            }

            Invoke-Check 'Prometheus scrapes both services' {
                $targetDeadline = (Get-Date).AddSeconds(60)
                $down = @()
                do {
                    $targets = (Invoke-RestMethod -Uri 'http://localhost:9090/api/v1/targets' -TimeoutSec 10).data.activeTargets
                    $down = @($targets | Where-Object { $_.labels.job -in @('event-generator', 'stream-processor') -and $_.health -ne 'up' })
                    if ($down.Count -gt 0 -and (Get-Date) -lt $targetDeadline) { Start-Sleep -Seconds 5 }
                } while ($down.Count -gt 0 -and (Get-Date) -lt $targetDeadline)
                Assert-True ($down.Count -eq 0) "down: $(($down | ForEach-Object { $_.scrapeUrl }) -join ', ')"
            }
        }
    }
    finally {
        Pop-Location
    }
}

Write-Host ''
if ($script:failures.Count -eq 0) {
    Write-Host 'Deployment contract verified.' -ForegroundColor Green
    exit 0
}

Write-Host "$($script:failures.Count) check(s) failed:" -ForegroundColor Red
foreach ($failure in $script:failures) { Write-Host "  - $failure" -ForegroundColor Red }
exit 1
