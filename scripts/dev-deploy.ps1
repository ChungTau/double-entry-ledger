#Requires -Version 5.1
<#
.SYNOPSIS
    Development deployment script for Double-Entry-Ledger on Windows 11.

.DESCRIPTION
    Automates the deployment of microservices and infrastructure to Minikube.
    Requires Administrator privileges for hosts file modification and minikube tunnel.

.PARAMETER Force
    Force rebuild Docker images even if they exist.

.PARAMETER WithKibana
    Include Kibana in infrastructure deployment.

.PARAMETER Help
    Show this help message.

.EXAMPLE
    .\dev-deploy.ps1
    Run interactive menu.

.EXAMPLE
    .\dev-deploy.ps1 -Force -WithKibana
    Full setup with forced image rebuild and Kibana.
#>

[CmdletBinding()]
param(
    [switch]$Force,
    [switch]$WithKibana,
    [switch]$Help
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

# =============================================================================
# CONFIGURATION
# =============================================================================
$Script:Namespace = "ledger-dev"
$Script:MinikubeCpus = 4
$Script:MinikubeMemory = 12288
$Script:ProjectRoot = Split-Path -Parent $PSScriptRoot
$Script:IngressHosts = @("api.ledger.local", "kafka.ledger.local", "kibana.ledger.local", "prometheus.ledger.local")
$Script:HostsFilePath = "C:\Windows\System32\drivers\etc\hosts"
$Script:ForceRebuild = $Force
$Script:IncludeKibana = $WithKibana

# =============================================================================
# UTILITY FUNCTIONS
# =============================================================================
function Write-Header {
    param([string]$Message)
    Write-Host ""
    Write-Host ("=" * 80) -ForegroundColor Blue
    Write-Host "    $Message" -ForegroundColor Blue
    Write-Host ("=" * 80) -ForegroundColor Blue
    Write-Host ""
}

function Write-Success {
    param([string]$Message)
    Write-Host "  [OK] " -ForegroundColor Green -NoNewline
    Write-Host $Message
}

function Write-ErrorMessage {
    param([string]$Message)
    Write-Host "  [ERROR] " -ForegroundColor Red -NoNewline
    Write-Host $Message -ForegroundColor Red
}

function Write-WarningMessage {
    param([string]$Message)
    Write-Host "  [SKIP] " -ForegroundColor Yellow -NoNewline
    Write-Host $Message
}

function Write-InfoMessage {
    param([string]$Message)
    Write-Host "  [INFO] " -ForegroundColor Cyan -NoNewline
    Write-Host $Message
}

function Test-Administrator {
    $currentPrincipal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    return $currentPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Test-CommandExists {
    param([string]$Command)
    $cmd = Get-Command $Command -ErrorAction SilentlyContinue
    if ($cmd) {
        Write-Success "$Command`: $($cmd.Source)"
        return $true
    } else {
        Write-ErrorMessage "$Command`: not found"
        return $false
    }
}

function Show-Help {
    Get-Help $MyInvocation.PSCommandPath -Detailed
    exit 0
}

function Confirm-Action {
    param([string]$Prompt)
    $response = Read-Host "  $Prompt (y/N)"
    return $response -match '^[Yy]$'
}

# =============================================================================
# PREREQUISITE CHECKS
# =============================================================================
function Test-Prerequisites {
    Write-Header "Checking Prerequisites"

    # Check Administrator privileges
    if (-not (Test-Administrator)) {
        Write-ErrorMessage "This script requires Administrator privileges."
        Write-Host ""
        Write-Host "  Please right-click PowerShell and select 'Run as administrator'" -ForegroundColor Yellow
        Write-Host ""
        exit 1
    }
    Write-Success "Running as Administrator"

    $failed = $false

    if (-not (Test-CommandExists "minikube")) { $failed = $true }
    if (-not (Test-CommandExists "kubectl")) { $failed = $true }
    if (-not (Test-CommandExists "helm")) { $failed = $true }
    if (-not (Test-CommandExists "docker")) { $failed = $true }

    if ($failed) {
        Write-ErrorMessage "Missing required tools. Please install them and try again."
        exit 1
    }

    Write-Success "All prerequisites satisfied"
}

# =============================================================================
# IDEMPOTENCY CHECK FUNCTIONS
# =============================================================================
function Test-MinikubeRunning {
    try {
        $status = minikube status --format='{{.Host}}' 2>$null
        return $status -eq "Running"
    } catch {
        return $false
    }
}

function Test-NamespaceExists {
    $result = kubectl get namespace $Script:Namespace 2>$null
    return $LASTEXITCODE -eq 0
}

function Test-HelmReleaseExists {
    param([string]$ReleaseName)
    $result = helm status $ReleaseName -n $Script:Namespace 2>$null
    return $LASTEXITCODE -eq 0
}

function Test-DeploymentExists {
    param([string]$DeploymentName)
    $result = kubectl get deployment $DeploymentName -n $Script:Namespace 2>$null
    return $LASTEXITCODE -eq 0
}

function Test-DockerImageExists {
    param([string]$ImageName)
    $imageId = docker images -q $ImageName 2>$null
    return -not [string]::IsNullOrEmpty($imageId)
}

function Test-HostsEntryExists {
    if (Test-Path $Script:HostsFilePath) {
        $content = Get-Content $Script:HostsFilePath -ErrorAction SilentlyContinue
        return $content -match "api\.ledger\.local"
    }
    return $false
}

function Test-IngressLoadBalancerPatched {
    $serviceType = kubectl get svc ingress-nginx-controller -n ingress-nginx -o jsonpath='{.spec.type}' 2>$null
    return $serviceType -eq "LoadBalancer"
}

# =============================================================================
# MINIKUBE FUNCTIONS
# =============================================================================
function Start-MinikubeCluster {
    Write-Header "Starting Minikube"

    if (Test-MinikubeRunning) {
        Write-WarningMessage "Minikube is already running"
    } else {
        Write-InfoMessage "Starting Minikube with $Script:MinikubeCpus CPUs and $Script:MinikubeMemory MB memory..."
        minikube start --cpus $Script:MinikubeCpus --memory $Script:MinikubeMemory --driver=docker
        if ($LASTEXITCODE -ne 0) { throw "Failed to start Minikube" }
        Write-Success "Minikube started"
    }

    # Enable addons
    Write-InfoMessage "Enabling required addons..."

    $addonList = minikube addons list 2>$null
    if ($addonList -match "ingress.*enabled") {
        Write-WarningMessage "ingress addon already enabled"
    } else {
        minikube addons enable ingress
        Write-Success "ingress addon enabled"
    }

    if ($addonList -match "metrics-server.*enabled") {
        Write-WarningMessage "metrics-server addon already enabled"
    } else {
        minikube addons enable metrics-server
        Write-Success "metrics-server addon enabled"
    }

    # Wait for ingress controller to be ready
    Write-InfoMessage "Waiting for ingress controller to be ready..."
    Start-Sleep -Seconds 10

    # Patch ingress controller to LoadBalancer (Windows requirement)
    Set-IngressLoadBalancer

    # Configure Docker environment
    Write-InfoMessage "Configuring Docker environment for Minikube..."
    minikube docker-env | Invoke-Expression
    Write-Success "Docker environment configured"
}

function Set-IngressLoadBalancer {
    Write-InfoMessage "Checking ingress controller service type..."

    # Wait for the service to exist
    $retries = 0
    while ($retries -lt 30) {
        $svc = kubectl get svc ingress-nginx-controller -n ingress-nginx 2>$null
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Seconds 5
        $retries++
    }

    if (Test-IngressLoadBalancerPatched) {
        Write-WarningMessage "Ingress controller already patched to LoadBalancer"
        return
    }

    Write-InfoMessage "Patching ingress-nginx-controller to LoadBalancer type..."
    $patchJson = '[{"op": "replace", "path": "/spec/type", "value":"LoadBalancer"}]'
    kubectl patch svc ingress-nginx-controller -n ingress-nginx --type='json' -p=$patchJson

    if ($LASTEXITCODE -ne 0) {
        Write-WarningMessage "Could not patch ingress controller (may need to wait for it to be ready)"
    } else {
        Write-Success "Ingress controller patched to LoadBalancer"
    }
}

# =============================================================================
# INFRASTRUCTURE FUNCTIONS
# =============================================================================
function Add-HelmRepos {
    Write-Header "Adding Helm Repositories"

    $repoList = helm repo list 2>$null

    if ($repoList -notmatch "bitnami") {
        helm repo add bitnami https://charts.bitnami.com/bitnami
        Write-Success "Added bitnami repo"
    } else {
        Write-WarningMessage "bitnami repo already exists"
    }

    if ($repoList -notmatch "elastic") {
        helm repo add elastic https://helm.elastic.co
        Write-Success "Added elastic repo"
    } else {
        Write-WarningMessage "elastic repo already exists"
    }

    if ($repoList -notmatch "prometheus-community") {
        helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
        Write-Success "Added prometheus-community repo"
    } else {
        Write-WarningMessage "prometheus-community repo already exists"
    }

    Write-InfoMessage "Updating Helm repositories..."
    helm repo update
    Write-Success "Helm repositories updated"
}

function New-NamespaceAndSecrets {
    Write-Header "Creating Namespace and Secrets"

    if (Test-NamespaceExists) {
        Write-WarningMessage "Namespace '$Script:Namespace' already exists"
    } else {
        kubectl apply -f "$Script:ProjectRoot\deploy\k8s\infra\namespace.yaml"
        Write-Success "Namespace '$Script:Namespace' created"
    }

    kubectl apply -f "$Script:ProjectRoot\deploy\k8s\secrets\secrets.yaml"
    Write-Success "Secrets applied"
}

function Wait-ForPods {
    param(
        [string]$Label,
        [int]$TimeoutSeconds = 300
    )

    Write-InfoMessage "Waiting for pods with label '$Label' (timeout: ${TimeoutSeconds}s)..."
    $result = kubectl wait --for=condition=ready pod -l $Label -n $Script:Namespace --timeout="${TimeoutSeconds}s" 2>$null

    if ($LASTEXITCODE -eq 0) {
        Write-Success "Pods with label '$Label' are ready"
    } else {
        Write-WarningMessage "Timeout waiting for pods (may still be starting)"
    }
}

function Install-Prometheus {
    Write-InfoMessage "Deploying Prometheus..."

    if (Test-HelmReleaseExists "prometheus") {
        Write-WarningMessage "Helm release 'prometheus' already exists"
    } else {
        helm install prometheus prometheus-community/kube-prometheus-stack `
            -f "$Script:ProjectRoot\deploy\k8s\infra\helm-values\prometheus-values.yaml" `
            -n $Script:Namespace `
            --wait --timeout 5m

        if ($LASTEXITCODE -ne 0) { throw "Failed to install Prometheus" }
        Write-Success "Prometheus installed"
    }
}

function Install-PostgreSQL {
    Write-InfoMessage "Deploying PostgreSQL..."

    if (Test-HelmReleaseExists "postgresql") {
        Write-WarningMessage "Helm release 'postgresql' already exists"
    } else {
        helm install postgresql oci://registry-1.docker.io/bitnamicharts/postgresql `
            -f "$Script:ProjectRoot\deploy\k8s\infra\helm-values\postgresql-values.yaml" `
            -n $Script:Namespace `
            --wait --timeout 5m

        if ($LASTEXITCODE -ne 0) { throw "Failed to install PostgreSQL" }
        Write-Success "PostgreSQL installed"
    }
}

function Install-Redis {
    Write-InfoMessage "Deploying Redis..."

    if (Test-HelmReleaseExists "redis") {
        Write-WarningMessage "Helm release 'redis' already exists"
    } else {
        helm install redis oci://registry-1.docker.io/bitnamicharts/redis `
            -f "$Script:ProjectRoot\deploy\k8s\infra\helm-values\redis-values.yaml" `
            -n $Script:Namespace `
            --wait --timeout 5m

        if ($LASTEXITCODE -ne 0) { throw "Failed to install Redis" }
        Write-Success "Redis installed"
    }
}

function Install-Kafka {
    Write-InfoMessage "Deploying Kafka..."

    kubectl apply -k "$Script:ProjectRoot\deploy\k8s\infra\kafka"
    Write-Success "Kafka manifests applied"

    Wait-ForPods -Label "app=kafka" -TimeoutSeconds 300
}

function Install-Elasticsearch {
    Write-InfoMessage "Deploying Elasticsearch..."

    if (Test-HelmReleaseExists "elasticsearch") {
        Write-WarningMessage "Helm release 'elasticsearch' already exists"
    } else {
        helm install elasticsearch elastic/elasticsearch `
            -f "$Script:ProjectRoot\deploy\k8s\infra\helm-values\es-dev-values.yaml" `
            -n $Script:Namespace `
            --wait --timeout 10m

        if ($LASTEXITCODE -ne 0) { throw "Failed to install Elasticsearch" }
        Write-Success "Elasticsearch installed"
    }
}

function Install-Kibana {
    Write-InfoMessage "Deploying Kibana..."

    if (Test-HelmReleaseExists "kibana") {
        Write-WarningMessage "Helm release 'kibana' already exists"
    } else {
        helm install kibana elastic/kibana `
            -f "$Script:ProjectRoot\deploy\k8s\infra\helm-values\kibana-values.yaml" `
            -n $Script:Namespace `
            --wait --timeout 5m

        if ($LASTEXITCODE -ne 0) { throw "Failed to install Kibana" }
        Write-Success "Kibana installed"
    }
}

function Install-Infrastructure {
    Write-Header "Deploying Infrastructure"

    Add-HelmRepos
    New-NamespaceAndSecrets

    Install-Prometheus
    Install-PostgreSQL
    Install-Redis
    Install-Kafka
    Install-Elasticsearch

    if ($Script:IncludeKibana) {
        Install-Kibana
    } else {
        Write-InfoMessage "Skipping Kibana (use -WithKibana to include)"
    }

    Write-Success "Infrastructure deployment complete"
}

# =============================================================================
# APPLICATION FUNCTIONS
# =============================================================================
function Build-DockerImages {
    Write-Header "Building Docker Images"

    # Ensure Docker env is set
    minikube docker-env | Invoke-Expression

    Push-Location $Script:ProjectRoot

    try {
        $images = @(
            @{ Name = "ledger-core"; Dockerfile = "apps\core\Dockerfile" },
            @{ Name = "ledger-gateway"; Dockerfile = "apps\gateway\Dockerfile" },
            @{ Name = "ledger-audit"; Dockerfile = "apps\audit\Dockerfile" }
        )

        foreach ($img in $images) {
            $imageName = "$($img.Name):local"

            if ((Test-DockerImageExists $imageName) -and (-not $Script:ForceRebuild)) {
                Write-WarningMessage "Image '$imageName' already exists (use -Force to rebuild)"
            } else {
                Write-InfoMessage "Building $($img.Name)..."
                docker build -t $imageName -f $img.Dockerfile .

                if ($LASTEXITCODE -ne 0) { throw "Failed to build $($img.Name)" }
                Write-Success "Built $imageName"
            }
        }
    } finally {
        Pop-Location
    }
}

function Deploy-Applications {
    Write-Header "Deploying Applications"

    $apps = @("ledger-core", "ledger-gateway", "ledger-audit", "kafka-ui")

    foreach ($app in $apps) {
        Write-InfoMessage "Deploying $app..."
        kubectl apply -k "$Script:ProjectRoot\deploy\k8s\apps\$app\overlays\dev"
        Write-Success "Applied $app manifests"
    }

    # Patch microservices to use local images with IfNotPresent policy
    Write-InfoMessage "Patching deployments to use local images..."
    $patchJson = '[{"op": "replace", "path": "/spec/template/spec/containers/0/imagePullPolicy", "value": "IfNotPresent"}]'

    foreach ($app in @("ledger-core", "ledger-gateway", "ledger-audit")) {
        kubectl set image "deployment/$app" "$app=${app}:local" -n $Script:Namespace
        kubectl patch deployment $app -n $Script:Namespace --type=json -p=$patchJson
        Write-Success "Patched $app deployment"
    }

    # Deploy Ingress
    Write-InfoMessage "Deploying Ingress..."
    kubectl apply -f "$Script:ProjectRoot\deploy\k8s\ingress\ingress.yaml"
    Write-Success "Ingress applied"

    # Wait for deployments
    Write-InfoMessage "Waiting for deployments to be ready..."
    foreach ($app in @("ledger-core", "ledger-gateway", "ledger-audit")) {
        kubectl rollout status "deployment/$app" -n $Script:Namespace --timeout=300s 2>$null
    }

    Write-Success "Application deployment complete"
}

# =============================================================================
# NETWORKING FUNCTIONS
# =============================================================================
function Update-HostsFile {
    Write-Header "Configuring Networking"

    Write-InfoMessage "Windows uses 127.0.0.1 with minikube tunnel"

    if (Test-HostsEntryExists) {
        Write-WarningMessage "Hosts file entry already exists for ledger services"
        $existingEntry = Get-Content $Script:HostsFilePath | Where-Object { $_ -match "ledger.local" } | Select-Object -First 1
        Write-InfoMessage "Current entry: $existingEntry"
    } else {
        Write-InfoMessage "Adding hosts file entry..."

        try {
            $hostEntry = "127.0.0.1 " + ($Script:IngressHosts -join " ")
            $content = @"

# Double-Entry-Ledger Development
$hostEntry
"@
            Add-Content -Path $Script:HostsFilePath -Value $content -Encoding ASCII
            Write-Success "Hosts file updated"
        } catch {
            Write-ErrorMessage "Failed to update hosts file: $_"
            Write-Host ""
            Write-Host "  Please manually add the following line to:" -ForegroundColor Yellow
            Write-Host "  $Script:HostsFilePath" -ForegroundColor Cyan
            Write-Host ""
            Write-Host "  127.0.0.1 $($Script:IngressHosts -join ' ')" -ForegroundColor White
            Write-Host ""
        }
    }

    Show-TunnelPrompt
}

function Show-TunnelPrompt {
    Write-Host ""
    Write-Host ("=" * 70) -ForegroundColor Yellow
    Write-Host " IMPORTANT: MINIKUBE TUNNEL REQUIRED" -ForegroundColor Yellow
    Write-Host ("=" * 70) -ForegroundColor Yellow
    Write-Host ""
    Write-Host "  To access services via Ingress on Windows, you MUST run:" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "  1. Open a NEW PowerShell terminal as Administrator" -ForegroundColor White
    Write-Host "  2. Run: " -ForegroundColor White -NoNewline
    Write-Host "minikube tunnel" -ForegroundColor Green
    Write-Host "  3. Keep that terminal running" -ForegroundColor White
    Write-Host ""
    Write-Host "  The tunnel provides network connectivity to the LoadBalancer service." -ForegroundColor Gray
    Write-Host "  Without it, you cannot access http://api.ledger.local" -ForegroundColor Gray
    Write-Host ""
    Write-Host ("=" * 70) -ForegroundColor Yellow
    Write-Host ""

    Read-Host "  Press Enter after starting minikube tunnel in a separate terminal"
}

# =============================================================================
# DATABASE FUNCTIONS
# =============================================================================
function Wait-ForPostgreSQL {
    Write-InfoMessage "Waiting for PostgreSQL to be ready..."

    $retries = 30
    $count = 0

    while ($count -lt $retries) {
        $phase = kubectl get pod postgresql-0 -n $Script:Namespace -o jsonpath='{.status.phase}' 2>$null

        if ($phase -eq "Running") {
            # Check if postgres is accepting connections
            $result = kubectl exec postgresql-0 -n $Script:Namespace -- pg_isready -U user -d ledger_db 2>$null
            if ($LASTEXITCODE -eq 0) {
                Write-Success "PostgreSQL is ready"
                return $true
            }
        }

        Start-Sleep -Seconds 5
        $count++
        Write-InfoMessage "  Waiting... ($count/$retries)"
    }

    Write-ErrorMessage "PostgreSQL did not become ready in time"
    return $false
}

function Initialize-Database {
    Write-Header "Seeding Database"

    if (-not (Wait-ForPostgreSQL)) {
        throw "PostgreSQL not ready"
    }

    Write-InfoMessage "Inserting test accounts..."

    $sql = @"
INSERT INTO accounts (id, user_id, balance, currency, version) VALUES
('11111111-1111-1111-1111-111111111111', 'user-a', 1000.00, 'USD', 0),
('22222222-2222-2222-2222-222222222222', 'user-b', 1000.00, 'USD', 0)
ON CONFLICT DO NOTHING;
"@

    $sql | kubectl exec -i postgresql-0 -n $Script:Namespace -- env PGPASSWORD=password psql -U user -d ledger_db

    Write-Success "Database seeded with test accounts"
    Write-InfoMessage "  Account 1: 11111111-1111-1111-1111-111111111111 (user-a, `$1000 USD)"
    Write-InfoMessage "  Account 2: 22222222-2222-2222-2222-222222222222 (user-b, `$1000 USD)"
}

# =============================================================================
# STATUS FUNCTIONS
# =============================================================================
function Show-Status {
    Write-Header "Cluster Status"

    Write-InfoMessage "Minikube Status:"
    minikube status

    Write-Host ""
    Write-InfoMessage "Pods in $Script:Namespace`:"
    kubectl get pods -n $Script:Namespace -o wide

    Write-Host ""
    Write-InfoMessage "Services in $Script:Namespace`:"
    kubectl get svc -n $Script:Namespace

    Write-Host ""
    Write-InfoMessage "Helm Releases in $Script:Namespace`:"
    helm list -n $Script:Namespace
}

function Show-ServiceUrls {
    Write-Header "Service URLs"

    Write-Host ""
    Write-Host "  API Gateway:  " -ForegroundColor Green -NoNewline
    Write-Host "http://api.ledger.local"
    Write-Host "  Kafka UI:     " -ForegroundColor Green -NoNewline
    Write-Host "http://kafka.ledger.local"
    Write-Host "  Kibana:       " -ForegroundColor Green -NoNewline
    Write-Host "http://kibana.ledger.local"
    Write-Host "  Prometheus:   " -ForegroundColor Green -NoNewline
    Write-Host "http://prometheus.ledger.local"
    Write-Host ""
    Write-Host "  Note: Requires 'minikube tunnel' running in separate Admin terminal" -ForegroundColor Yellow
    Write-Host ""
}

function Get-JwtToken {
    Write-Header "Sample JWT Token"

    Write-InfoMessage "Generating JWT token for user-a..."

    try {
        $body = @{
            user_id = "user-a"
            roles = @("user")
        } | ConvertTo-Json

        $response = Invoke-RestMethod -Uri "http://api.ledger.local/api/v1/auth/token" `
            -Method POST `
            -ContentType "application/json" `
            -Body $body `
            -ErrorAction Stop

        Write-Host ""
        Write-Host "  Token:" -ForegroundColor Green
        Write-Host "  $($response.token)"
        Write-Host ""

        return $response.token
    } catch {
        Write-WarningMessage "Could not generate token. Gateway may not be ready yet."
        Write-InfoMessage "Make sure minikube tunnel is running!"
        Write-Host ""
        Write-Host "  Try manually:" -ForegroundColor Cyan
        Write-Host '  $body = @{ user_id = "user-a"; roles = @("user") } | ConvertTo-Json'
        Write-Host '  Invoke-RestMethod -Uri "http://api.ledger.local/api/v1/auth/token" -Method POST -ContentType "application/json" -Body $body'
        Write-Host ""
        return $null
    }
}

function Show-FinalSummary {
    Write-Header "DEPLOYMENT COMPLETE"

    Show-ServiceUrls

    Write-Host "Test Commands:" -ForegroundColor Blue
    Write-Host ""
    Write-Host "  # Health check:"
    Write-Host '  Invoke-RestMethod http://api.ledger.local/health'
    Write-Host ""
    Write-Host "  # Generate token:"
    Write-Host '  $body = @{ user_id = "user-a"; roles = @("user") } | ConvertTo-Json'
    Write-Host '  $token = (Invoke-RestMethod -Uri "http://api.ledger.local/api/v1/auth/token" -Method POST -ContentType "application/json" -Body $body).token'
    Write-Host ""
    Write-Host ("=" * 80) -ForegroundColor Blue
}

# =============================================================================
# CLEANUP FUNCTIONS
# =============================================================================
function Remove-AppsOnly {
    Write-Header "Cleaning Up Applications"

    Write-InfoMessage "Deleting application deployments..."

    $apps = @("ledger-core", "ledger-gateway", "ledger-audit", "kafka-ui")

    foreach ($app in $apps) {
        if (Test-DeploymentExists $app) {
            kubectl delete -k "$Script:ProjectRoot\deploy\k8s\apps\$app\overlays\dev" 2>$null
            Write-Success "Deleted $app"
        } else {
            Write-WarningMessage "$app not found"
        }
    }

    kubectl delete -f "$Script:ProjectRoot\deploy\k8s\ingress\ingress.yaml" 2>$null
    Write-Success "Deleted Ingress"

    Write-Success "Application cleanup complete"
}

function Remove-Everything {
    Write-Header "Full Cleanup"

    if (-not (Confirm-Action "This will delete ALL resources including infrastructure. Continue?")) {
        Write-InfoMessage "Cleanup cancelled"
        return
    }

    Remove-AppsOnly

    Write-InfoMessage "Uninstalling Helm releases..."

    $releases = @("kibana", "elasticsearch", "redis", "postgresql", "prometheus")
    foreach ($release in $releases) {
        if (Test-HelmReleaseExists $release) {
            helm uninstall $release -n $Script:Namespace 2>$null
            Write-Success "Uninstalled $release"
        }
    }

    Write-InfoMessage "Deleting Kafka..."
    kubectl delete -k "$Script:ProjectRoot\deploy\k8s\infra\kafka" 2>$null

    Write-InfoMessage "Deleting namespace..."
    kubectl delete namespace $Script:Namespace 2>$null
    Write-Success "Namespace deleted"

    if (Confirm-Action "Stop Minikube?") {
        minikube stop
        Write-Success "Minikube stopped"
    }

    Write-Success "Full cleanup complete"
}

function Show-CleanupMenu {
    Write-Host ""
    Write-Host "  Cleanup Options:"
    Write-Host "  [1] Apps only - Remove application deployments, keep infrastructure"
    Write-Host "  [2] Full teardown - Delete namespace, Helm releases, stop Minikube"
    Write-Host "  [b] Back to main menu"
    Write-Host ""

    $choice = Read-Host "  Select option"

    switch ($choice) {
        "1" { Remove-AppsOnly }
        "2" { Remove-Everything }
        "b" { return }
        "B" { return }
        default { Write-ErrorMessage "Invalid option" }
    }
}

# =============================================================================
# FULL SETUP
# =============================================================================
function Start-FullSetup {
    Write-Header "Full Development Environment Setup"

    Test-Prerequisites
    Start-MinikubeCluster
    Install-Infrastructure
    Build-DockerImages
    Deploy-Applications
    Update-HostsFile
    Initialize-Database
    Show-FinalSummary
    Get-JwtToken
}

# =============================================================================
# REBUILD & REDEPLOY
# =============================================================================
function Invoke-RebuildAndRedeploy {
    Write-Header "Rebuild & Redeploy Applications"

    $Script:ForceRebuild = $true
    Build-DockerImages

    # Restart deployments to pick up new images
    Write-InfoMessage "Restarting deployments..."
    foreach ($app in @("ledger-core", "ledger-gateway", "ledger-audit")) {
        kubectl rollout restart "deployment/$app" -n $Script:Namespace
        Write-Success "Restarted $app"
    }

    # Wait for rollout
    foreach ($app in @("ledger-core", "ledger-gateway", "ledger-audit")) {
        kubectl rollout status "deployment/$app" -n $Script:Namespace --timeout=300s 2>$null
    }

    Write-Success "Rebuild and redeploy complete"
}

# =============================================================================
# MENU
# =============================================================================
function Show-Menu {
    Write-Host ""
    Write-Host ("=" * 80) -ForegroundColor Blue
    Write-Host "    Double-Entry-Ledger Development Deployment (Windows 11)" -ForegroundColor Blue
    Write-Host ("=" * 80) -ForegroundColor Blue
    Write-Host ""
    Write-Host "  [0] Full Setup (all steps)"
    Write-Host ""
    Write-Host "  Individual Steps:"
    Write-Host "  [1] Start/Configure Minikube"
    Write-Host "  [2] Deploy Infrastructure (Prometheus, PostgreSQL, Redis, Kafka, ES)"
    Write-Host "  [3] Build Docker Images"
    Write-Host "  [4] Deploy Applications (Kustomize)"
    Write-Host "  [5] Configure Networking (hosts file)"
    Write-Host "  [6] Seed Database"
    Write-Host "  [7] Show Status & URLs"
    Write-Host "  [8] Rebuild & Redeploy Apps"
    Write-Host "  [9] Cleanup"
    Write-Host ""
    Write-Host "  [q] Exit"
    Write-Host ""
}

# =============================================================================
# MAIN
# =============================================================================
function Main {
    if ($Help) {
        Show-Help
    }

    while ($true) {
        Show-Menu
        $choice = Read-Host "  Select option"

        switch ($choice) {
            "0" { Start-FullSetup }
            "1" { Start-MinikubeCluster }
            "2" { Install-Infrastructure }
            "3" { Build-DockerImages }
            "4" { Deploy-Applications }
            "5" { Update-HostsFile }
            "6" { Initialize-Database }
            "7" {
                Show-Status
                Show-ServiceUrls
                Get-JwtToken
            }
            "8" { Invoke-RebuildAndRedeploy }
            "9" { Show-CleanupMenu }
            "q" {
                Write-Host ""
                Write-InfoMessage "Goodbye!"
                exit 0
            }
            "Q" {
                Write-Host ""
                Write-InfoMessage "Goodbye!"
                exit 0
            }
            default {
                Write-ErrorMessage "Invalid option: $choice"
            }
        }

        Write-Host ""
        Read-Host "  Press Enter to continue..."
    }
}

# Run main
Main
