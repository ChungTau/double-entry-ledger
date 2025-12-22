#!/usr/bin/env bash
#
# dev-deploy.sh - Development deployment script for Double-Entry-Ledger
# Platform: Linux/macOS
#
# Usage: ./scripts/dev-deploy.sh [OPTIONS]
#   --force       Force rebuild Docker images even if they exist
#   --with-kibana Include Kibana in infrastructure deployment
#   --help        Show this help message
#

set -euo pipefail

# =============================================================================
# CONFIGURATION
# =============================================================================
readonly NAMESPACE="ledger-dev"
readonly MINIKUBE_CPUS=4
readonly MINIKUBE_MEMORY=12288
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
readonly INGRESS_HOSTS="api.ledger.local kafka.ledger.local kibana.ledger.local prometheus.ledger.local"

# Color codes
readonly RED='\033[0;31m'
readonly GREEN='\033[0;32m'
readonly YELLOW='\033[1;33m'
readonly BLUE='\033[0;34m'
readonly CYAN='\033[0;36m'
readonly NC='\033[0m' # No Color

# Flags (set via command line)
FORCE_REBUILD=false
WITH_KIBANA=false

# =============================================================================
# UTILITY FUNCTIONS
# =============================================================================
print_header() {
    echo ""
    echo -e "${BLUE}================================================================================${NC}"
    echo -e "${BLUE}    $1${NC}"
    echo -e "${BLUE}================================================================================${NC}"
    echo ""
}

print_success() {
    echo -e "  ${GREEN}[OK]${NC} $1"
}

print_error() {
    echo -e "  ${RED}[ERROR]${NC} $1" >&2
}

print_warning() {
    echo -e "  ${YELLOW}[SKIP]${NC} $1"
}

print_info() {
    echo -e "  ${CYAN}[INFO]${NC} $1"
}

confirm_action() {
    local prompt="$1"
    read -p "  $prompt (y/N): " response
    [[ "$response" =~ ^[Yy]$ ]]
}

check_command_exists() {
    local cmd="$1"
    if command -v "$cmd" &>/dev/null; then
        print_success "$cmd: $(command -v "$cmd")"
        return 0
    else
        print_error "$cmd: not found"
        return 1
    fi
}

show_help() {
    cat << EOF
Double-Entry-Ledger Development Deployment Script (Linux/macOS)

Usage: $0 [OPTIONS]

Options:
  --force         Force rebuild Docker images even if they exist
  --with-kibana   Include Kibana in infrastructure deployment
  --help          Show this help message

Examples:
  $0                    # Interactive menu
  $0 --force            # Force image rebuild
  $0 --with-kibana      # Include Kibana deployment

EOF
    exit 0
}

# =============================================================================
# PREREQUISITE CHECKS
# =============================================================================
check_prerequisites() {
    print_header "Checking Prerequisites"

    local failed=false

    check_command_exists "minikube" || failed=true
    check_command_exists "kubectl" || failed=true
    check_command_exists "helm" || failed=true
    check_command_exists "docker" || failed=true

    if $failed; then
        print_error "Missing required tools. Please install them and try again."
        exit 1
    fi

    print_success "All prerequisites satisfied"
}

# =============================================================================
# IDEMPOTENCY CHECK FUNCTIONS
# =============================================================================
minikube_is_running() {
    local status
    status=$(minikube status --format='{{.Host}}' 2>/dev/null || echo "Stopped")
    [[ "$status" == "Running" ]]
}

namespace_exists() {
    kubectl get namespace "$NAMESPACE" &>/dev/null
}

helm_release_exists() {
    local release_name="$1"
    helm status "$release_name" -n "$NAMESPACE" &>/dev/null
}

deployment_exists() {
    local deployment_name="$1"
    kubectl get deployment "$deployment_name" -n "$NAMESPACE" &>/dev/null
}

addon_is_enabled() {
    local addon_name="$1"
    minikube addons list -o json 2>/dev/null | grep -q "\"$addon_name\".*\"enabled\""
}

docker_image_exists() {
    local image_name="$1"
    docker images -q "$image_name" 2>/dev/null | grep -q .
}

hosts_entry_exists() {
    grep -q "api.ledger.local" /etc/hosts 2>/dev/null
}

# =============================================================================
# MINIKUBE FUNCTIONS
# =============================================================================
start_minikube() {
    print_header "Starting Minikube"

    if minikube_is_running; then
        print_warning "Minikube is already running"
    else
        print_info "Starting Minikube with $MINIKUBE_CPUS CPUs and ${MINIKUBE_MEMORY}MB memory..."
        minikube start --cpus "$MINIKUBE_CPUS" --memory "$MINIKUBE_MEMORY" --driver=docker
        print_success "Minikube started"
    fi

    # Enable addons
    print_info "Enabling required addons..."

    if minikube addons list | grep -q "ingress.*enabled"; then
        print_warning "ingress addon already enabled"
    else
        minikube addons enable ingress
        print_success "ingress addon enabled"
    fi

    if minikube addons list | grep -q "metrics-server.*enabled"; then
        print_warning "metrics-server addon already enabled"
    else
        minikube addons enable metrics-server
        print_success "metrics-server addon enabled"
    fi

    # Configure Docker environment
    print_info "Configuring Docker environment for Minikube..."
    eval $(minikube docker-env)
    print_success "Docker environment configured"
}

# =============================================================================
# INFRASTRUCTURE FUNCTIONS
# =============================================================================
add_helm_repos() {
    print_header "Adding Helm Repositories"

    local repos_added=false

    if ! helm repo list 2>/dev/null | grep -q "bitnami"; then
        helm repo add bitnami https://charts.bitnami.com/bitnami
        repos_added=true
        print_success "Added bitnami repo"
    else
        print_warning "bitnami repo already exists"
    fi

    if ! helm repo list 2>/dev/null | grep -q "elastic"; then
        helm repo add elastic https://helm.elastic.co
        repos_added=true
        print_success "Added elastic repo"
    else
        print_warning "elastic repo already exists"
    fi

    if ! helm repo list 2>/dev/null | grep -q "prometheus-community"; then
        helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
        repos_added=true
        print_success "Added prometheus-community repo"
    else
        print_warning "prometheus-community repo already exists"
    fi

    print_info "Updating Helm repositories..."
    helm repo update
    print_success "Helm repositories updated"
}

create_namespace_and_secrets() {
    print_header "Creating Namespace and Secrets"

    if namespace_exists; then
        print_warning "Namespace '$NAMESPACE' already exists"
    else
        kubectl apply -f "$PROJECT_ROOT/deploy/k8s/infra/namespace.yaml"
        print_success "Namespace '$NAMESPACE' created"
    fi

    kubectl apply -f "$PROJECT_ROOT/deploy/k8s/secrets/secrets.yaml"
    print_success "Secrets applied"
}

wait_for_pods() {
    local label="$1"
    local timeout="${2:-300}"
    local namespace="${3:-$NAMESPACE}"

    print_info "Waiting for pods with label '$label' (timeout: ${timeout}s)..."
    if kubectl wait --for=condition=ready pod -l "$label" -n "$namespace" --timeout="${timeout}s" 2>/dev/null; then
        print_success "Pods with label '$label' are ready"
    else
        print_warning "Timeout waiting for pods (may still be starting)"
    fi
}

deploy_prometheus() {
    print_info "Deploying Prometheus..."

    if helm_release_exists "prometheus"; then
        print_warning "Helm release 'prometheus' already exists"
    else
        helm install prometheus prometheus-community/kube-prometheus-stack \
            -f "$PROJECT_ROOT/deploy/k8s/infra/helm-values/prometheus-values.yaml" \
            -n "$NAMESPACE" \
            --wait --timeout 5m
        print_success "Prometheus installed"
    fi
}

deploy_postgresql() {
    print_info "Deploying PostgreSQL..."

    if helm_release_exists "postgresql"; then
        print_warning "Helm release 'postgresql' already exists"
    else
        helm install postgresql oci://registry-1.docker.io/bitnamicharts/postgresql \
            -f "$PROJECT_ROOT/deploy/k8s/infra/helm-values/postgresql-values.yaml" \
            -n "$NAMESPACE" \
            --wait --timeout 5m
        print_success "PostgreSQL installed"
    fi
}

deploy_redis() {
    print_info "Deploying Redis..."

    if helm_release_exists "redis"; then
        print_warning "Helm release 'redis' already exists"
    else
        helm install redis oci://registry-1.docker.io/bitnamicharts/redis \
            -f "$PROJECT_ROOT/deploy/k8s/infra/helm-values/redis-values.yaml" \
            -n "$NAMESPACE" \
            --wait --timeout 5m
        print_success "Redis installed"
    fi
}

deploy_kafka() {
    print_info "Deploying Kafka..."

    kubectl apply -k "$PROJECT_ROOT/deploy/k8s/infra/kafka"
    print_success "Kafka manifests applied"

    wait_for_pods "app=kafka" 300
}

deploy_elasticsearch() {
    print_info "Deploying Elasticsearch..."

    if helm_release_exists "elasticsearch"; then
        print_warning "Helm release 'elasticsearch' already exists"
    else
        helm install elasticsearch elastic/elasticsearch \
            -f "$PROJECT_ROOT/deploy/k8s/infra/helm-values/es-dev-values.yaml" \
            -n "$NAMESPACE" \
            --wait --timeout 10m
        print_success "Elasticsearch installed"
    fi
}

deploy_kibana() {
    print_info "Deploying Kibana..."

    if helm_release_exists "kibana"; then
        print_warning "Helm release 'kibana' already exists"
    else
        helm install kibana elastic/kibana \
            -f "$PROJECT_ROOT/deploy/k8s/infra/helm-values/kibana-values.yaml" \
            -n "$NAMESPACE" \
            --wait --timeout 5m
        print_success "Kibana installed"
    fi
}

deploy_infrastructure() {
    print_header "Deploying Infrastructure"

    add_helm_repos
    create_namespace_and_secrets

    deploy_prometheus
    deploy_postgresql
    deploy_redis
    deploy_kafka
    deploy_elasticsearch

    if $WITH_KIBANA; then
        deploy_kibana
    else
        print_info "Skipping Kibana (use --with-kibana to include)"
    fi

    print_success "Infrastructure deployment complete"
}

# =============================================================================
# APPLICATION FUNCTIONS
# =============================================================================
build_docker_images() {
    print_header "Building Docker Images"

    # Ensure Docker env is set
    eval $(minikube docker-env)

    cd "$PROJECT_ROOT"

    local images=("ledger-core" "ledger-gateway" "ledger-audit")
    local dockerfiles=("apps/core/Dockerfile" "apps/gateway/Dockerfile" "apps/audit/Dockerfile")

    for i in "${!images[@]}"; do
        local image="${images[$i]}"
        local dockerfile="${dockerfiles[$i]}"

        if docker_image_exists "${image}:local" && ! $FORCE_REBUILD; then
            print_warning "Image '${image}:local' already exists (use --force to rebuild)"
        else
            print_info "Building ${image}..."
            docker build -t "${image}:local" -f "$dockerfile" .
            print_success "Built ${image}:local"
        fi
    done
}

deploy_applications() {
    print_header "Deploying Applications"

    local apps=("ledger-core" "ledger-gateway" "ledger-audit" "kafka-ui")

    for app in "${apps[@]}"; do
        print_info "Deploying $app..."
        kubectl apply -k "$PROJECT_ROOT/deploy/k8s/apps/$app/overlays/dev"
        print_success "Applied $app manifests"
    done

    # Patch microservices to use local images with IfNotPresent policy
    print_info "Patching deployments to use local images..."
    for app in ledger-core ledger-gateway ledger-audit; do
        kubectl set image "deployment/$app" "$app=${app}:local" -n "$NAMESPACE"
        kubectl patch deployment "$app" -n "$NAMESPACE" --type=json \
            -p='[{"op": "replace", "path": "/spec/template/spec/containers/0/imagePullPolicy", "value": "IfNotPresent"}]'
        print_success "Patched $app deployment"
    done

    # Deploy Ingress
    print_info "Deploying Ingress..."
    kubectl apply -f "$PROJECT_ROOT/deploy/k8s/ingress/ingress.yaml"
    print_success "Ingress applied"

    # Wait for deployments
    print_info "Waiting for deployments to be ready..."
    for app in ledger-core ledger-gateway ledger-audit; do
        kubectl rollout status "deployment/$app" -n "$NAMESPACE" --timeout=300s || true
    done

    print_success "Application deployment complete"
}

# =============================================================================
# NETWORKING FUNCTIONS
# =============================================================================
configure_networking() {
    print_header "Configuring Networking"

    local minikube_ip
    minikube_ip=$(minikube ip)

    print_info "Minikube IP: $minikube_ip"

    if hosts_entry_exists; then
        print_warning "Hosts file entry already exists for ledger services"
        print_info "Current entry:"
        grep "ledger.local" /etc/hosts | head -1
    else
        print_info "Adding hosts file entry (requires sudo)..."
        echo "$minikube_ip $INGRESS_HOSTS" | sudo tee -a /etc/hosts
        print_success "Hosts file updated"
    fi
}

# =============================================================================
# DATABASE FUNCTIONS
# =============================================================================
wait_for_postgresql() {
    print_info "Waiting for PostgreSQL to be ready..."

    local retries=30
    local count=0

    while [[ $count -lt $retries ]]; do
        if kubectl get pod postgresql-0 -n "$NAMESPACE" -o jsonpath='{.status.phase}' 2>/dev/null | grep -q "Running"; then
            # Check if postgres is accepting connections
            if kubectl exec postgresql-0 -n "$NAMESPACE" -- pg_isready -U user -d ledger_db &>/dev/null; then
                print_success "PostgreSQL is ready"
                return 0
            fi
        fi
        sleep 5
        count=$((count + 1))
        print_info "  Waiting... ($count/$retries)"
    done

    print_error "PostgreSQL did not become ready in time"
    return 1
}

seed_database() {
    print_header "Seeding Database"

    wait_for_postgresql

    print_info "Inserting test accounts..."

    local sql="INSERT INTO accounts (id, user_id, balance, currency, version) VALUES
('11111111-1111-1111-1111-111111111111', 'user-a', 1000.00, 'USD', 0),
('22222222-2222-2222-2222-222222222222', 'user-b', 1000.00, 'USD', 0)
ON CONFLICT DO NOTHING;"

    kubectl exec -i postgresql-0 -n "$NAMESPACE" -- \
        env PGPASSWORD=password psql -U user -d ledger_db -c "$sql"

    print_success "Database seeded with test accounts"
    print_info "  Account 1: 11111111-1111-1111-1111-111111111111 (user-a, \$1000 USD)"
    print_info "  Account 2: 22222222-2222-2222-2222-222222222222 (user-b, \$1000 USD)"
}

# =============================================================================
# STATUS FUNCTIONS
# =============================================================================
show_status() {
    print_header "Cluster Status"

    print_info "Minikube Status:"
    minikube status

    echo ""
    print_info "Pods in $NAMESPACE:"
    kubectl get pods -n "$NAMESPACE" -o wide

    echo ""
    print_info "Services in $NAMESPACE:"
    kubectl get svc -n "$NAMESPACE"

    echo ""
    print_info "Helm Releases in $NAMESPACE:"
    helm list -n "$NAMESPACE"
}

show_service_urls() {
    print_header "Service URLs"

    local minikube_ip
    minikube_ip=$(minikube ip)

    echo ""
    echo -e "  ${GREEN}API Gateway:${NC}  http://api.ledger.local"
    echo -e "  ${GREEN}Kafka UI:${NC}     http://kafka.ledger.local"
    echo -e "  ${GREEN}Kibana:${NC}       http://kibana.ledger.local"
    echo -e "  ${GREEN}Prometheus:${NC}   http://prometheus.ledger.local"
    echo ""
    echo -e "  ${CYAN}Minikube IP:${NC}  $minikube_ip"
    echo ""
}

generate_and_show_token() {
    print_header "Sample JWT Token"

    print_info "Generating JWT token for user-a..."

    local token
    token=$(curl -s -X POST "http://api.ledger.local/api/v1/auth/token" \
        -H "Content-Type: application/json" \
        -d '{"user_id": "user-a", "roles": ["user"]}' 2>/dev/null | \
        grep -o '"token":"[^"]*"' | cut -d'"' -f4) || true

    if [[ -n "$token" ]]; then
        echo ""
        echo -e "  ${GREEN}Token:${NC}"
        echo "  $token"
        echo ""
    else
        print_warning "Could not generate token. Gateway may not be ready yet."
        print_info "Try manually: curl -X POST http://api.ledger.local/api/v1/auth/token -H 'Content-Type: application/json' -d '{\"user_id\": \"user-a\", \"roles\": [\"user\"]}'"
    fi
}

show_final_summary() {
    print_header "DEPLOYMENT COMPLETE"

    show_service_urls

    echo -e "${BLUE}Test Commands:${NC}"
    echo ""
    echo "  # Health check:"
    echo "  curl http://api.ledger.local/health"
    echo ""
    echo "  # Generate token:"
    echo "  curl -X POST http://api.ledger.local/api/v1/auth/token \\"
    echo "    -H 'Content-Type: application/json' \\"
    echo "    -d '{\"user_id\": \"user-a\", \"roles\": [\"user\"]}'"
    echo ""
    echo -e "${BLUE}================================================================================${NC}"
}

# =============================================================================
# CLEANUP FUNCTIONS
# =============================================================================
cleanup_apps_only() {
    print_header "Cleaning Up Applications"

    print_info "Deleting application deployments..."

    local apps=("ledger-core" "ledger-gateway" "ledger-audit" "kafka-ui")

    for app in "${apps[@]}"; do
        if deployment_exists "$app"; then
            kubectl delete -k "$PROJECT_ROOT/deploy/k8s/apps/$app/overlays/dev" 2>/dev/null || true
            print_success "Deleted $app"
        else
            print_warning "$app not found"
        fi
    done

    kubectl delete -f "$PROJECT_ROOT/deploy/k8s/ingress/ingress.yaml" 2>/dev/null || true
    print_success "Deleted Ingress"

    print_success "Application cleanup complete"
}

cleanup_full() {
    print_header "Full Cleanup"

    if ! confirm_action "This will delete ALL resources including infrastructure. Continue?"; then
        print_info "Cleanup cancelled"
        return
    fi

    cleanup_apps_only

    print_info "Uninstalling Helm releases..."

    local releases=("kibana" "elasticsearch" "redis" "postgresql" "prometheus")
    for release in "${releases[@]}"; do
        if helm_release_exists "$release"; then
            helm uninstall "$release" -n "$NAMESPACE" || true
            print_success "Uninstalled $release"
        fi
    done

    print_info "Deleting Kafka..."
    kubectl delete -k "$PROJECT_ROOT/deploy/k8s/infra/kafka" 2>/dev/null || true

    print_info "Deleting namespace..."
    kubectl delete namespace "$NAMESPACE" 2>/dev/null || true
    print_success "Namespace deleted"

    if confirm_action "Stop Minikube?"; then
        minikube stop
        print_success "Minikube stopped"
    fi

    print_success "Full cleanup complete"
}

show_cleanup_menu() {
    echo ""
    echo "  Cleanup Options:"
    echo "  [1] Apps only - Remove application deployments, keep infrastructure"
    echo "  [2] Full teardown - Delete namespace, Helm releases, stop Minikube"
    echo "  [b] Back to main menu"
    echo ""
    read -p "  Select option: " choice

    case "$choice" in
        1) cleanup_apps_only ;;
        2) cleanup_full ;;
        b|B) return ;;
        *) print_error "Invalid option" ;;
    esac
}

# =============================================================================
# FULL SETUP
# =============================================================================
full_setup() {
    print_header "Full Development Environment Setup"

    check_prerequisites
    start_minikube
    deploy_infrastructure
    build_docker_images
    deploy_applications
    configure_networking
    seed_database
    show_final_summary
    generate_and_show_token
}

# =============================================================================
# REBUILD & REDEPLOY
# =============================================================================
rebuild_and_redeploy() {
    print_header "Rebuild & Redeploy Applications"

    FORCE_REBUILD=true
    build_docker_images

    # Restart deployments to pick up new images
    print_info "Restarting deployments..."
    for app in ledger-core ledger-gateway ledger-audit; do
        kubectl rollout restart "deployment/$app" -n "$NAMESPACE"
        print_success "Restarted $app"
    done

    # Wait for rollout
    for app in ledger-core ledger-gateway ledger-audit; do
        kubectl rollout status "deployment/$app" -n "$NAMESPACE" --timeout=300s || true
    done

    print_success "Rebuild and redeploy complete"
}

# =============================================================================
# MENU
# =============================================================================
show_menu() {
    echo ""
    echo -e "${BLUE}================================================================================${NC}"
    echo -e "${BLUE}    Double-Entry-Ledger Development Deployment (Linux/macOS)${NC}"
    echo -e "${BLUE}================================================================================${NC}"
    echo ""
    echo "  [0] Full Setup (all steps)"
    echo ""
    echo "  Individual Steps:"
    echo "  [1] Start/Configure Minikube"
    echo "  [2] Deploy Infrastructure (Prometheus, PostgreSQL, Redis, Kafka, ES)"
    echo "  [3] Build Docker Images"
    echo "  [4] Deploy Applications (Kustomize)"
    echo "  [5] Configure Networking (hosts file)"
    echo "  [6] Seed Database"
    echo "  [7] Show Status & URLs"
    echo "  [8] Rebuild & Redeploy Apps"
    echo "  [9] Cleanup"
    echo ""
    echo "  [q] Exit"
    echo ""
}

# =============================================================================
# MAIN
# =============================================================================
main() {
    # Parse command line arguments
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --force)
                FORCE_REBUILD=true
                shift
                ;;
            --with-kibana)
                WITH_KIBANA=true
                shift
                ;;
            --help|-h)
                show_help
                ;;
            *)
                print_error "Unknown option: $1"
                show_help
                ;;
        esac
    done

    while true; do
        show_menu
        read -p "  Select option: " choice

        case "$choice" in
            0) full_setup ;;
            1) start_minikube ;;
            2) deploy_infrastructure ;;
            3) build_docker_images ;;
            4) deploy_applications ;;
            5) configure_networking ;;
            6) seed_database ;;
            7)
                show_status
                show_service_urls
                generate_and_show_token
                ;;
            8) rebuild_and_redeploy ;;
            9) show_cleanup_menu ;;
            q|Q)
                echo ""
                print_info "Goodbye!"
                exit 0
                ;;
            *)
                print_error "Invalid option: $choice"
                ;;
        esac

        echo ""
        read -p "  Press Enter to continue..."
    done
}

main "$@"
