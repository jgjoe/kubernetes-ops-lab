# Kubernetes Operations Lab

kind 기반 로컬 Kubernetes에서 최소 Spring Boot 서비스를 배포하고, 정상 상태뿐 아니라 장애·업데이트·복구 과정을 직접 재현한 운영 실습 프로젝트입니다.

이 프로젝트의 목적은 많은 Kubernetes 도구를 나열하는 것이 아니라 **작은 시스템의 상태를 변경하고, 실제 Kubernetes 상태를 관찰하고, 원인을 진단하고, 정상 상태로 복구하는 흐름**을 검증하는 것입니다.

> **범위:** local kind lab입니다. production cluster 운영, managed Kubernetes(EKS/GKE/AKS), Helm, GitOps, HPA, Ingress, CI/CD를 수행한 프로젝트가 아닙니다.

## What I verified

- **Pod self-healing** — replicas=2 상태에서 Pod 하나를 삭제하고 ReplicaSet이 새 이름·UID·IP의 replacement Pod를 생성해 다시 desired state를 만족시키는 과정 확인
- **Readiness failure** — Pod/container는 계속 실행되지만 Ready=false가 되고 Service EndpointSlice의 traffic 대상에서 제외되며 container restart는 발생하지 않는 것을 확인
- **Liveness failure** — 같은 Pod 이름·UID·IP를 유지한 채 container ID가 바뀌고 RestartCount가 증가하는 container restart 확인
- **Rolling Update** — v1 → v2에서 새 ReplicaSet과 Ready Pod가 먼저 만들어지고 기존 ReplicaSet이 점진적으로 축소되는 흐름 확인
- **Failed rollout diagnosis** — 잘못된 v3 readiness path로 ProgressDeadlineExceeded를 재현하고 get, describe, Events, logs로 HTTP 404 probe 오류를 확인
- **Rollback** — 마지막 정상 v2 Pod template으로 rollback한 뒤 Deployment 2/2, EndpointSlice, HTTP 응답 정상화 확인
- **Manual scaling** — replicas 2 → 4 → 2 변경에 따라 ReplicaSet/Pod/Endpoint가 desired state에 맞춰 변하는 과정 확인
- **Resource configuration** — 실제 Pod spec에서 requests 100m / 128Mi, limits 500m / 256Mi 적용 확인

## Representative failure scenario

~~~
broken v3 readiness path
→ new v3 Pod Running / NotReady
→ rollout ProgressDeadlineExceeded
→ existing v2 replicas keep serving traffic
→ describe / Events show readiness HTTP 404
→ application logs show normal startup
→ rollback to the last healthy v2 template
→ Deployment 2/2 Ready + EndpointSlice + HTTP recovered
~~~

이 실험에서는 신규 release가 실패해도 기존 Ready replica가 가용성을 유지할 수 있다는 점과, rollout 실패 원인을 container crash와 probe 설정 오류로 구분하는 과정을 확인했습니다.

## Architecture

~~~
Spring Boot 3.5 / Java 17
        ↓ Docker image
kind Kubernetes cluster
        ↓
Namespace
ConfigMap
Deployment (2 replicas)
ReplicaSet
Pods
Service / EndpointSlice
~~~

애플리케이션은 실습에 필요한 endpoint만 제공합니다.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | / | 서비스 식별 |
| GET | /version | release version 확인 |
| GET / POST | /health/ready | readiness 상태 조회·전환 |
| GET / POST | /health/live | liveness 상태 조회·전환 |

probe 상태는 process-local memory에 저장되므로 container restart 후 healthy 기본값으로 초기화됩니다.

## Release model used in the lab

이 프로젝트의 v1/v2/v3는 서로 다른 애플리케이션 binary가 아닙니다. 같은 image bits에 서로 다른 image tag와 versioned ConfigMap을 적용해 Kubernetes rollout mechanics를 관찰했습니다.

~~~
v1 = kubernetes-ops-lab:v1 + kubernetes-ops-lab-config-v1
v2 = kubernetes-ops-lab:v2 + kubernetes-ops-lab-config-v2
v3 = kubernetes-ops-lab:v3 + kubernetes-ops-lab-config-v3
~~~

v3에서는 readiness probe path를 의도적으로 잘못 설정해 실패 rollout을 만듭니다.

## Verification

2026-09-19 검증 기록:

- Spring tests: **8/8 PASS**
- kind control-plane: **Ready**
- 최종 Deployment: **v2 / 2 of 2 Ready**
- 최종 EndpointSlice: **Ready endpoints 2개**
- full runbook의 self-healing / readiness / liveness / rollout / rollback / scaling 시나리오 실행 완료

## Quick start — baseline deployment

전제: Windows PowerShell, Docker Desktop, Java 17, kubectl, kind.

~~~powershell
.\mvnw.cmd test
docker build -t kubernetes-ops-lab:v1 .

kind create cluster --name kubernetes-ops-lab
kind load docker-image kubernetes-ops-lab:v1 --name kubernetes-ops-lab

kubectl apply -f .\k8s\namespace.yaml
kubectl apply -f .\k8s\configmap.yaml
kubectl apply -f .\k8s\deployment.yaml
kubectl apply -f .\k8s\service.yaml

kubectl rollout status deployment/kubernetes-ops-lab -n kubernetes-ops-lab --timeout=120s
kubectl get deployment,replicaset,pod,service -n kubernetes-ops-lab -o wide
kubectl get endpointslice -n kubernetes-ops-lab -l kubernetes.io/service-name=kubernetes-ops-lab -o wide
~~~

Service 응답은 별도 PowerShell 창에서 port-forward 후 확인합니다.

~~~powershell
kubectl port-forward -n kubernetes-ops-lab service/kubernetes-ops-lab 18080:80
~~~

~~~powershell
curl.exe -i http://localhost:18080/
curl.exe -i http://localhost:18080/version
curl.exe -i http://localhost:18080/health/ready
curl.exe -i http://localhost:18080/health/live
~~~

## Full runbook & evidence

실제 실행 명령, 관찰 상태, Events, EndpointSlice, rollout history, 장애 유발·복구 절차는 다음 문서에 보존합니다.

- [Runbook & Evidence](docs/runbook.md) — 실제 명령·상태·Events·복구 기록
- [Project scope / Mini PRD v1.0 FINAL](docs/mini-prd-v1.0.md) — 범위·설계 결정·non-goals

runbook은 단순 명령 모음이 아니라 **명령 → 실제 출력/상태 → 해석 → 복구 → 정상화 확인** 순서로 기록했습니다.

## Scope and limits

의도적으로 다음 범위로 확장하지 않았습니다.

- production Kubernetes 운영
- managed Kubernetes / cloud cluster
- Helm / GitOps / Argo CD
- Prometheus / Grafana / Loki
- Ingress / TLS
- HPA / VPA
- persistent database / storage failure
- complex RBAC / NetworkPolicy
- CI/CD
- CPU throttling / OOM stress test

이 저장소는 위 기술을 많이 붙이는 것보다 Kubernetes의 **reconciliation, traffic eligibility, container restart, rollout/rollback, diagnosis**를 실제 상태 변화로 이해하는 데 초점을 둡니다.
