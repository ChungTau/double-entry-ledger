# Kubernetes Deployment Guide

本指南說明如何將 Double-Entry-Ledger 微服務部署到 Minikube (本地 Kubernetes 集群)。

## 目錄

- [前置需求](#前置需求)
- [快速開始](#快速開始)
- [詳細部署步驟](#詳細部署步驟)
- [服務存取](#服務存取)
- [ArgoCD GitOps](#argocd-gitops)
- [故障排除](#故障排除)

---

## 前置需求

### 硬體需求
- **RAM**: 最少 16GB (建議 20GB+)
- **CPU**: 4 cores 以上
- **硬碟**: 50GB 可用空間

### 軟體需求
- [Docker Desktop](https://www.docker.com/products/docker-desktop/)
- [Minikube](https://minikube.sigs.k8s.io/docs/start/)
- [kubectl](https://kubernetes.io/docs/tasks/tools/)
- [Helm](https://helm.sh/docs/intro/install/) (v3+)
- [kustomize](https://kubectl.docs.kubernetes.io/installation/kustomize/) (可選，kubectl 內建)

---

## 快速開始

```bash
# 1. 啟動 Minikube (配置足夠資源)
minikube start --cpus 4 --memory 12288 --driver=docker

# 2. 啟用 Ingress addon
minikube addons enable ingress

# 3. 指向 Minikube Docker daemon (重要！)
eval $(minikube docker-env)

# 4. 建立 Namespace
kubectl apply -f deploy/k8s/infra/namespace.yaml

# 5. 部署 Secrets
kubectl apply -f deploy/k8s/secrets/secrets.yaml

# 6. 安裝基礎設施 (Helm)
./deploy/k8s/scripts/install-infra.sh  # 或手動執行下方指令

# 7. Build 並部署應用程式
./deploy/k8s/scripts/deploy-apps.sh    # 或手動執行下方指令
```

---

## 詳細部署步驟

### Step 1: 啟動 Minikube

```bash
# 啟動 Minikube 並配置資源
minikube start --cpus 4 --memory 12288 --driver=docker

# 驗證 Minikube 狀態
minikube status

# 啟用 Ingress Controller
minikube addons enable ingress
```

### Step 2: 配置 Docker 環境

```bash
# 指向 Minikube 的 Docker daemon (每次開新 terminal 都需要執行)
eval $(minikube docker-env)

# 驗證設定
docker info | grep "Name:"
```

### Step 3: Build Docker Images

```bash
# 從專案根目錄執行
cd /path/to/double-entry-ledger

# Build ledger-core
docker build -t ledger-core:local -f apps/core/Dockerfile .

# Build ledger-gateway
docker build -t ledger-gateway:local -f apps/gateway/Dockerfile .

# Build ledger-audit
docker build -t ledger-audit:local -f apps/audit/Dockerfile .

# 驗證 images
docker images | grep ledger
```

### Step 4: 建立 Namespace 和 Secrets

```bash
# 建立 namespace
kubectl apply -f deploy/k8s/infra/namespace.yaml

# 建立 secrets
kubectl apply -f deploy/k8s/secrets/secrets.yaml

# 驗證
kubectl get ns ledger-dev
kubectl get secrets -n ledger-dev
```

### Step 5: 安裝基礎設施服務 (Helm)

```bash
# 添加 Helm repos
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo add elastic https://helm.elastic.co
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update

# 安裝 PostgreSQL
helm install postgresql oci://registry-1.docker.io/bitnamicharts/postgresql \
  -f deploy/k8s/infra/helm-values/postgresql-values.yaml \
  -n ledger-dev

# 安裝 Redis
helm install redis oci://registry-1.docker.io/bitnamicharts/redis \
  -f deploy/k8s/infra/helm-values/redis-values.yaml \
  -n ledger-dev

# 安裝 Kafka
helm install kafka oci://registry-1.docker.io/bitnamicharts/kafka \
  -f deploy/k8s/infra/helm-values/kafka-values.yaml \
  -n ledger-dev

# 安裝 Elasticsearch
helm install elasticsearch elastic/elasticsearch \
  -f deploy/k8s/infra/helm-values/elasticsearch-values.yaml \
  -n ledger-dev

# 安裝 Kibana
helm install kibana elastic/kibana \
  -f deploy/k8s/infra/helm-values/kibana-values.yaml \
  -n ledger-dev

# 安裝 Prometheus (精簡版)
helm install prometheus prometheus-community/kube-prometheus-stack \
  -f deploy/k8s/infra/helm-values/prometheus-values.yaml \
  -n ledger-dev

# 等待所有 pods 就緒
kubectl wait --for=condition=ready pod -l app.kubernetes.io/instance=postgresql -n ledger-dev --timeout=300s
kubectl wait --for=condition=ready pod -l app.kubernetes.io/instance=redis -n ledger-dev --timeout=300s
kubectl wait --for=condition=ready pod -l app.kubernetes.io/instance=kafka -n ledger-dev --timeout=300s
```

### Step 6: 部署微服務 (Kustomize)

```bash
# 部署 ledger-core
kubectl apply -k deploy/k8s/apps/ledger-core/overlays/dev

# 部署 ledger-gateway
kubectl apply -k deploy/k8s/apps/ledger-gateway/overlays/dev

# 部署 ledger-audit
kubectl apply -k deploy/k8s/apps/ledger-audit/overlays/dev

# 部署 Kafka UI
kubectl apply -k deploy/k8s/apps/kafka-ui/overlays/dev

# 部署 Ingress
kubectl apply -f deploy/k8s/ingress/ingress.yaml
```

### Step 7: 設定本機 DNS

```bash
# 取得 Minikube IP
minikube ip

# 編輯 hosts 檔案 (Windows: C:\Windows\System32\drivers\etc\hosts)
# 添加以下內容 (替換 <MINIKUBE_IP> 為實際 IP):
# <MINIKUBE_IP> api.ledger.local kafka.ledger.local kibana.ledger.local prometheus.ledger.local
```

---

## 服務存取

### 透過 Ingress 存取

| 服務 | URL |
|-----|-----|
| API Gateway | http://api.ledger.local |
| Kafka UI | http://kafka.ledger.local |
| Kibana | http://kibana.ledger.local |
| Prometheus | http://prometheus.ledger.local |

### 透過 Port Forward 存取

```bash
# API Gateway
kubectl port-forward svc/ledger-gateway 8081:8081 -n ledger-dev

# Kafka UI
kubectl port-forward svc/kafka-ui 8090:8080 -n ledger-dev

# Kibana
kubectl port-forward svc/kibana-kibana 5601:5601 -n ledger-dev

# Prometheus
kubectl port-forward svc/prometheus-kube-prometheus-prometheus 9090:9090 -n ledger-dev

# PostgreSQL (for debugging)
kubectl port-forward svc/postgresql 5432:5432 -n ledger-dev
```

---

## ArgoCD GitOps

### 安裝 ArgoCD

```bash
# 建立 argocd namespace
kubectl create namespace argocd

# 安裝 ArgoCD
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

# 等待安裝完成
kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=argocd-server -n argocd --timeout=300s

# 取得初始密碼
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d

# Port forward ArgoCD UI
kubectl port-forward svc/argocd-server 8443:443 -n argocd
# 訪問 https://localhost:8443，用戶名: admin
```

### 部署 ArgoCD Applications

```bash
# 建立 ArgoCD Project
kubectl apply -f deploy/k8s/argocd/project.yaml

# 部署應用程式
kubectl apply -f deploy/k8s/argocd/applications/
```

### GitOps 工作流程

1. 修改程式碼並 push 到 GitHub
2. 更新 image tag 在 Kustomize overlay
3. ArgoCD 自動檢測變更並同步

---

## 故障排除

### 常見問題

#### 1. Pod 處於 Pending 狀態
```bash
# 檢查事件
kubectl describe pod <pod-name> -n ledger-dev

# 常見原因: 資源不足
# 解決方案: 增加 Minikube 資源或降低服務資源限制
```

#### 2. ImagePullBackOff 錯誤
```bash
# 確認已執行 eval $(minikube docker-env)
# 確認 imagePullPolicy: Never

# 重新 build image
docker build -t <image>:local -f <Dockerfile> .
```

#### 3. CrashLoopBackOff 錯誤
```bash
# 查看 logs
kubectl logs <pod-name> -n ledger-dev

# 查看之前的容器 logs
kubectl logs <pod-name> -n ledger-dev --previous
```

#### 4. 服務無法連接
```bash
# 檢查 service endpoints
kubectl get endpoints -n ledger-dev

# 檢查 DNS 解析
kubectl run -it --rm debug --image=busybox --restart=Never -- nslookup postgresql.ledger-dev.svc.cluster.local
```

### 有用的指令

```bash
# 查看所有資源
kubectl get all -n ledger-dev

# 查看 pod logs
kubectl logs -f deployment/ledger-core -n ledger-dev

# 進入 pod shell
kubectl exec -it deployment/ledger-core -n ledger-dev -- /bin/sh

# 刪除並重新部署
kubectl delete -k deploy/k8s/apps/ledger-core/overlays/dev
kubectl apply -k deploy/k8s/apps/ledger-core/overlays/dev

# 清理所有資源
kubectl delete ns ledger-dev
helm uninstall postgresql redis kafka elasticsearch kibana prometheus -n ledger-dev
```

---

## K8s DNS 服務對照表

| 服務 | Kubernetes DNS | Port |
|-----|----------------|------|
| PostgreSQL | postgresql.ledger-dev.svc.cluster.local | 5432 |
| Redis | redis-master.ledger-dev.svc.cluster.local | 6379 |
| Kafka | kafka.ledger-dev.svc.cluster.local | 9092 |
| Elasticsearch | elasticsearch-master.ledger-dev.svc.cluster.local | 9200 |
| ledger-core | ledger-core.ledger-dev.svc.cluster.local | 8080 (HTTP), 9098 (gRPC) |
| ledger-gateway | ledger-gateway.ledger-dev.svc.cluster.local | 8081 |

**簡短形式** (同一 namespace 內):
- `postgresql:5432`
- `redis-master:6379`
- `kafka:9092`
- `elasticsearch-master:9200`
- `ledger-core:9098`
