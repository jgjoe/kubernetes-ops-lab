# Kubernetes Operations Lab — Kubernetes 운영 실습

**kind 기반 로컬 Kubernetes에 Spring Boot 서비스를 배포하고 장애·업데이트·복구 과정을 직접 재현한 운영 실습**

작은 시스템의 상태를 바꾸고, 실제 Kubernetes 상태를 관찰하고, 원인을 진단하고, 정상 상태로 복구하는 흐름을 검증했습니다.
명령, 실제 출력, 해석, 복구, 정상화 확인을 런북에 순서대로 남겼습니다.

---

## 주요 기능

- **Pod 자가 복구** — replicas=2에서 Pod 하나를 지우면 ReplicaSet이 새 이름·UID·IP의 Pod를 만들어 원하는 상태를 되찾는 과정 확인
- **readiness 실패** — 컨테이너는 계속 실행되지만 Ready=false가 되어 Service EndpointSlice 트래픽 대상에서 빠지고, 재시작은 일어나지 않음을 확인
- **liveness 실패** — 같은 Pod 이름·UID·IP를 유지한 채 컨테이너 ID가 바뀌고 RestartCount가 늘어나는 재시작 확인
- **Rolling Update** — v1 → v2에서 새 ReplicaSet과 Ready Pod가 먼저 뜬 뒤 기존 ReplicaSet이 점진적으로 줄어드는 흐름 확인
- **실패한 배포 진단** — 잘못된 readiness 경로로 `ProgressDeadlineExceeded`를 재현하고 get·describe·Events·logs로 HTTP 404 probe 오류 확인
- **롤백** — 마지막 정상 v2 템플릿으로 되돌린 뒤 Deployment 2/2, EndpointSlice, HTTP 응답 정상화 확인
- **스케일링·리소스** — replicas 2 → 4 → 2 변경과 requests 100m/128Mi·limits 500m/256Mi 적용 확인

## 설계 판단

### 실패를 의도적으로 만들어 원인을 구분했다

```text
잘못된 v3 readiness 경로
→ 새 v3 Pod Running / NotReady
→ rollout ProgressDeadlineExceeded
→ 기존 v2 replica가 계속 트래픽 처리
→ describe / Events에서 readiness HTTP 404 확인
→ 애플리케이션 로그는 정상 기동
→ 마지막 정상 v2 템플릿으로 롤백
→ Deployment 2/2 Ready + EndpointSlice + HTTP 정상화
```

새 릴리스가 실패해도 기존 Ready replica가 가용성을 지키는 것을 확인했고, 배포 실패의 원인을 컨테이너 장애가 아니라 probe 설정 오류로 구분했습니다.

### 릴리스를 이미지 태그와 ConfigMap 버전으로 나눴다

같은 이미지에 태그와 버전별 ConfigMap을 달리 적용해 v1·v2·v3 릴리스를 만들고 롤아웃 동작을 관찰했습니다. v3에서는 readiness 경로를 의도적으로 잘못 설정해 실패한 배포를 만듭니다.

```text
v1 = kubernetes-ops-lab:v1 + kubernetes-ops-lab-config-v1
v2 = kubernetes-ops-lab:v2 + kubernetes-ops-lab-config-v2
v3 = kubernetes-ops-lab:v3 + kubernetes-ops-lab-config-v3
```

### 상태를 바꿀 수 있는 최소 API

애플리케이션은 실습에 필요한 엔드포인트만 제공합니다. probe 상태는 프로세스 메모리에 있어 컨테이너가 재시작되면 정상 기본값으로 돌아옵니다.

| Method | Path | 용도 |
| --- | --- | --- |
| GET | / | 서비스 식별 |
| GET | /version | 릴리스 버전 확인 |
| GET / POST | /health/ready | readiness 상태 조회·전환 |
| GET / POST | /health/live | liveness 상태 조회·전환 |

## 검증 결과

2026-09-19 기준:

- Spring 테스트 **8/8 통과**
- kind control-plane **Ready**
- 최종 Deployment **v2 / 2 of 2 Ready**
- 최종 EndpointSlice Ready endpoint **2개**
- 자가 복구·readiness·liveness·롤아웃·롤백·스케일링 시나리오 전체 실행

실제 명령·상태·Events·복구 기록은 [런북](docs/runbook.md)에, 범위와 설계 결정은 [Mini PRD](docs/mini-prd-v1.0.md)에 있습니다.

## 기술 스택

| 영역 | 기술 |
|---|---|
| 애플리케이션 | Spring Boot 3.5, Java 17 |
| 컨테이너 | Docker |
| 클러스터 | kind, kubectl |
| Kubernetes 객체 | Namespace, ConfigMap, Deployment, ReplicaSet, Service, EndpointSlice |

## 실행

Windows PowerShell, Docker Desktop, Java 17, kubectl, kind가 필요합니다.

```powershell
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
```

별도 창에서 port-forward 후 응답을 확인합니다.

```powershell
kubectl port-forward -n kubernetes-ops-lab service/kubernetes-ops-lab 18080:80
curl.exe -i http://localhost:18080/version
```

## 만든 사람

**Jigwan Joe** — Backend · Ops

- GitHub: [@jgjoe](https://github.com/jgjoe)
- Email: jigwan.joe@gmail.com
