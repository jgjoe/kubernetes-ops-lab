# Kubernetes 운영 실습 — Mini PRD v1.0 FINAL

**Status:** FINAL  
**Purpose:** 취업 포트폴리오용 Kubernetes 핵심 운영 실습  
**Scope principle:** 기술 나열이 아니라 배포·관찰·장애·업데이트·복구 동작을 직접 재현하고 설명할 수 있는 증거를 만든다.

---

## 1. 목표

작은 Spring Boot 서비스를 Kubernetes에 배포한 뒤 정상 상태 확인에서 끝나지 않고,

**배포 → 상태 관찰 → 장애 발생 → 원인 진단 → 복구 → 업데이트 → 실패한 업데이트 롤백**

과정을 직접 재현한다.

프로젝트의 핵심 질문은 다음과 같다.

- Kubernetes는 desired state를 어떻게 유지하는가?
- Pod 또는 container 장애가 발생하면 어떤 동작이 일어나는가?
- `Running`과 `Ready`는 왜 다른가?
- `readinessProbe`와 `livenessProbe`는 각각 무엇을 보호하는가?
- Rolling Update 동안 Deployment와 ReplicaSet은 어떻게 변하는가?
- 실패한 rollout을 어떻게 발견하고 복구하는가?
- 장애가 발생했을 때 어떤 Kubernetes 상태와 로그를 어떤 순서로 확인하는가?
- CPU/memory requests와 limits가 왜 필요한가?

프로젝트 완료 시 위 내용을 실제 실험 결과를 근거로 자신의 말로 설명할 수 있어야 한다.

---

## 2. 확정된 설계 결정

### Kubernetes runtime

**kind**

로컬에서 재현 가능한 Kubernetes cluster를 생성·삭제하며 실습한다.

### Application

**최소 Spring Boot 운영 실습 API**

비즈니스 기능을 추가하지 않고 Kubernetes 동작 검증에 필요한 endpoint만 제공한다.

- `/`
- `/version`
- `/health/live`
- `/health/ready`

readiness와 liveness failure를 의도적으로 재현할 수 있는 최소한의 실습 기능만 허용한다.

### Service access

**Kubernetes Service + `kubectl port-forward`**

Ingress는 사용하지 않는다.

### Observability

필수 관측 수단:

- `kubectl get`
- `kubectl describe`
- `kubectl logs`
- Kubernetes Events
- `kubectl rollout`

Prometheus/Grafana/Loki는 사용하지 않는다.

`metrics-server`는 필수가 아니며 Definition of Done에 포함하지 않는다.

---

## 3. 필요한 운영 역량과 범위

### 반드시 포함

- Pod / Deployment / ReplicaSet 관계
- Desired State / Reconciliation
- Service와 Pod selection
- 다중 replica
- Readiness Probe
- Liveness Probe
- Rolling Update
- Rollback
- Pod/container 장애 관찰
- `kubectl get / describe / logs / events`
- CPU/memory requests
- CPU/memory limits
- ConfigMap
- Namespace
- 수동 scaling

### 이번 프로젝트에서 제외

- Helm
- Argo CD / Flux
- Prometheus / Grafana / Loki
- Service Mesh
- Operator / CRD
- StatefulSet
- Persistent Volume 운영
- DB HA
- EKS / GKE / AKS
- Terraform
- 멀티클러스터
- HPA / VPA
- Ingress Controller
- CI/CD pipeline

새 기술은 기존 검증 시나리오를 수행하는 데 반드시 필요하지 않으면 추가하지 않는다.

---

## 4. MVP 시스템

```text
Local kind Cluster

        Service
           |
    +------+------+
    |             |
  Pod A         Pod B
    |             |
 minimal Spring Boot API
```

Deployment는 최소 2 replicas로 실행한다.

애플리케이션은 Kubernetes 운영 실습을 위한 최소 기능만 포함하며 별도 비즈니스 도메인은 구현하지 않는다.

---

# 5. Requirements

## R1. 재현 가능한 로컬 클러스터

문서화된 절차만으로 다음 작업이 가능해야 한다.

- kind cluster 생성
- Kubernetes 상태 확인
- 실습 종료 후 cluster 삭제
- 다시 생성하여 동일 실습 반복

숨겨진 수동 설정에 의존하지 않는다.

---

## R2. 선언적 애플리케이션 배포

최소 다음 Kubernetes resource를 사용한다.

- Namespace
- Deployment
- Service
- ConfigMap

Deployment에는 최소 다음을 명시한다.

- replicas ≥ 2
- readinessProbe
- livenessProbe
- CPU request
- memory request
- CPU limit
- memory limit

---

## R3. 정상 상태 확인

배포 후 다음 관계를 직접 관찰한다.

```text
Deployment
    ↓
ReplicaSet
    ↓
Pods
    ↓
Service Endpoints
```

단순히 Pod가 `Running`이라는 이유만으로 정상이라고 판단해서는 안 된다.

최소 다음을 확인한다.

- 원하는 replica 수 충족
- Pod Ready 상태
- Service endpoint 연결
- 실제 HTTP 요청 성공
- `/version` 응답 확인

---

## R4. Pod failure와 reconciliation

실행 중인 Pod 하나를 의도적으로 삭제한다.

다음을 관찰한다.

- 기존 Pod 삭제
- 일시적인 replica 부족
- replacement Pod 생성
- desired replica count 복구
- Service 정상 상태 유지 또는 복구

핵심 설명:

> Kubernetes가 삭제된 Pod 자체를 되살리는 것이 아니라 Deployment가 선언한 desired state를 다시 만족시키기 위해 새로운 Pod를 생성한다.

---

## R5. Readiness failure

readiness가 실패하는 상태를 의도적으로 만든다.

다음을 관찰한다.

- container가 실행 중일 수 있다.
- Pod가 `Ready` 상태가 되지 않는다.
- 해당 Pod가 Service traffic 대상에서 제외된다.
- readiness failure 자체는 container restart의 원인이 아니다.
- 정상 replica가 남아 있다면 요청은 정상 Pod로 처리될 수 있다.

핵심 설명:

> Readiness는 “이 container를 재시작해야 하는가?”가 아니라 “현재 이 Pod가 요청을 받아도 되는가?”를 판단한다.

---

## R6. Liveness failure

의도적인 **liveness failure → container restart → 정상화**를 재현한다.

다음을 실제로 관찰한다.

- liveness probe failure 발생
- Kubernetes event 또는 describe 결과에서 probe failure 확인
- kubelet에 의한 container restart
- Pod의 container restart count 증가
- restart 이후 probe 정상화
- Pod가 다시 Ready 상태가 됨
- Service 요청이 정상 처리됨

가능하면 Pod 자체가 교체된 것과 container restart를 혼동하지 않도록 동일 Pod에서 restart count 변화를 확인한다.

핵심 설명:

> Liveness는 실행 중인 container가 더 이상 정상적으로 동작할 수 없다고 판단될 때 container restart를 유도한다.

### Readiness와의 Acceptance Difference

반드시 다음 차이가 실제 증거로 확인되어야 한다.

| 상황 | Service traffic | Container restart |
|---|---|---|
| Readiness failure | 제외됨 | 발생하지 않음 |
| Liveness failure | 정상화될 때까지 영향 가능 | 발생함 |

두 probe의 차이를 단순 정의 암기가 아니라 **관찰된 Kubernetes 상태 변화**를 근거로 설명할 수 있어야 한다.

---

## R7. CPU / Memory requests와 limits

모든 application container에 CPU와 memory의 requests/limits를 명시한다.

배포 후 실제 Pod spec에서 해당 설정이 적용되었음을 확인한다.

최소 다음 의미를 설명할 수 있어야 한다.

- `requests`: scheduler가 Pod 배치 시 필요한 자원량을 판단하는 기준
- `limits`: container가 사용할 수 있는 자원의 상한을 정의하는 설정
- CPU limit과 memory limit은 runtime에서 동일한 방식으로 나타나는 것이 아님

이번 프로젝트에서는 다음 실험을 수행하지 않는다.

- CPU saturation 부하 테스트
- CPU throttling 측정
- memory stress test
- OOMKill 유도
- 자원 limit tuning

**설정 → 실제 Pod 적용 확인 → 의미 설명**까지가 완료 범위다.

---

## R8. 정상 Rolling Update

정상 v1을 v2로 업데이트한다.

다음을 관찰한다.

- 새로운 ReplicaSet 생성
- 신규 Pod 생성
- 신규 Pod readiness 확인
- 기존 Pod 점진적 감소
- rollout 완료
- `/version` 응답이 v2로 전환

`kubectl rollout status`와 ReplicaSet 상태를 통해 정상 rollout을 확인한다.

---

## R9. 실패한 rollout과 rollback

의도적으로 문제가 있는 v3를 배포한다.

실패 원인은 기존 실습 기능을 이용한 단순하고 재현 가능한 형태로 제한한다.

다음 순서로 진단한다.

```text
상태 이상 발견
→ kubectl get
→ kubectl describe / events
→ kubectl logs
→ rollout 상태 확인
→ 원인 판단
→ rollback
→ 정상 상태 재검증
```

rollback 후 다음을 확인한다.

- desired replica 수 복구
- Pod Ready 상태
- 정상 Service endpoint
- HTTP 요청 성공
- `/version`이 마지막 정상 revision으로 복구

---

## R10. Manual Scaling

Deployment replicas를 예를 들어

```text
2 → 4 → 2
```

로 변경한다.

Pod 생성·삭제를 관찰하고,

**desired state가 변경되면 controller가 실제 상태를 새로운 desired state에 맞춘다**

는 점을 설명한다.

Autoscaling은 구현하지 않는다.

---

# 6. 검증 시나리오

## Scenario A — Initial Deployment

### When

새 kind cluster에 manifest를 적용한다.

### Acceptance Criteria

- Deployment 생성
- desired replicas 모두 Ready
- Service 연결
- HTTP 요청 성공
- `/version = v1`
- requests/limits가 실제 Pod spec에 적용됨

### Evidence

- `kubectl get`
- `kubectl describe`
- Pod resource 설정
- HTTP 응답

---

## Scenario B — Pod Failure / Self-Healing

### When

정상 Pod 하나를 삭제한다.

### Acceptance Criteria

- Pod 삭제 확인
- replacement Pod 생성 확인
- desired replica count 복구
- 새 Pod Ready 확인
- 서비스 정상 상태 확인

---

## Scenario C — Readiness Failure

### When

한 Pod 또는 신규 revision에서 readiness failure를 유발한다.

### Acceptance Criteria

- container가 실행 중인 상태와 Ready 상태의 차이 확인
- readiness failure 확인
- Service traffic 대상에서 제외됨
- readiness failure 때문에 container restart가 발생하지 않음
- 정상 상태 복구 후 다시 Ready 및 Service 대상이 됨

---

## Scenario D — Liveness Failure / Container Restart

### When

의도적인 transient liveness failure를 발생시킨다.

### Acceptance Criteria

- liveness probe failure 관찰
- event 또는 describe에서 failure 근거 확인
- container restart 발생
- restart count 증가
- restart 이후 liveness 정상화
- Pod가 Ready 상태로 돌아옴
- HTTP 요청 정상화

### 반드시 설명할 차이

```text
Readiness failure
→ 요청을 받을 준비가 안 됨
→ Service traffic에서 제외
→ container restart는 하지 않음

Liveness failure
→ container 자체가 정상 상태로 회복할 수 없다고 판단
→ container restart
→ 정상화 후 다시 Ready
```

---

## Scenario E — Successful Rolling Update

### When

v1 → v2를 배포한다.

### Acceptance Criteria

- 새 ReplicaSet 생성
- 신규 Pod Ready
- 기존 Pod 점진적 종료
- rollout 완료
- `/version = v2`

---

## Scenario F — Failed Rollout / Rollback

### When

문제가 있는 v3를 배포한다.

### Acceptance Criteria

- rollout 이상 상태 발견
- `get / describe / events / logs` 중 필요한 정보를 이용해 원인 확인
- 이전 정상 revision 식별
- rollback 수행
- Ready replicas 복구
- HTTP 정상화
- 정상 version 복구

---

## Scenario G — Manual Scaling

### When

replicas를 2 → 4 → 2로 변경한다.

### Acceptance Criteria

- Pod 수가 desired state에 맞게 변화
- 새 Pod 생성 및 기존 Pod 제거 확인
- scaling 이후에도 Service 정상

---

# 7. Evidence 규칙

각 검증 시나리오는 최소 다음 네 항목을 남긴다.

1. 무엇을 변경하거나 실패시켰는가
2. Kubernetes에서 무엇이 관찰되었는가
3. 왜 해당 상태가 발생했는가
4. 어떻게 정상 상태를 확인했는가

장애 시나리오는 추가로 다음을 포함한다.

5. 무엇을 근거로 원인을 판단했는가
6. 어떤 복구 동작을 수행했는가

단순 screenshot 모음이 아니라

**명령 → 실제 출력 → 해석 → 복구 → 정상화 확인**

형태를 기본 evidence로 한다.

---

# 8. Non-goals

이번 프로젝트는 다음을 목표로 하지 않는다.

- production Kubernetes 운영 경험 주장
- Kubernetes 자격증 전체 범위 학습
- cloud managed Kubernetes 구축
- HA control plane
- cluster upgrade
- Stateful DB 운영
- Persistent Volume 장애 대응
- DB backup / restore
- Helm
- GitOps
- Argo CD / Flux
- Prometheus / Grafana / Loki
- Ingress / TLS / cert-manager
- Service Mesh
- Operator / CRD
- 복잡한 RBAC
- NetworkPolicy
- HPA / VPA
- CI/CD 자동화
- 멀티클러스터
- CPU 부하 테스트
- CPU throttling 성능 측정
- memory stress test
- OOMKill 실험
- requests/limits 튜닝

특히 requests/limits는 **설정과 의미 이해를 위한 요구사항**이며 별도의 성능·장애 실험으로 확장하지 않는다.

---

# 9. Definition of Done

다음 조건을 모두 만족해야 완료로 판단한다.

## Environment

- 문서화된 절차로 kind cluster를 생성할 수 있다.
- cluster를 삭제하고 다시 생성할 수 있다.
- 숨겨진 수동 환경에 의존하지 않는다.

## Deployment

- Namespace, Deployment, Service, ConfigMap 존재
- replicas ≥ 2
- readinessProbe 적용
- livenessProbe 적용
- CPU/memory requests 적용
- CPU/memory limits 적용
- Service를 통한 HTTP 요청 성공

## Resource Configuration

실제 Pod에서 CPU/memory requests와 limits가 적용된 것을 확인했다.

다음을 설명할 수 있다.

- requests가 scheduler 판단에 사용되는 이유
- limits의 의미
- CPU와 memory limit 동작이 완전히 동일하지 않다는 점

부하·OOM 실험은 완료 조건이 아니다.

## Operations

다음 실험을 모두 실제로 수행했다.

- 최초 배포
- Pod 삭제와 자동 복구
- readiness failure와 복구
- liveness failure와 container restart 및 정상화
- 정상 rolling update
- 실패 rollout
- rollback
- manual scaling

## Probe Acceptance

다음 차이를 실제 관찰 결과로 증명했다.

**Readiness failure**

- Pod가 Service 요청 대상에서 제외됨
- container restart는 발생하지 않음

**Liveness failure**

- probe failure 확인
- container restart 발생
- restart count 증가
- restart 후 정상 상태 복귀

두 상황을 혼동하지 않고 설명할 수 있어야 한다.

## Diagnosis

장애 원인에 따라 다음 정보를 읽을 수 있다.

- `kubectl get`
- `kubectl describe`
- `kubectl logs`
- Kubernetes events
- `kubectl rollout status`
- `kubectl rollout history`

명령어 자체를 외우는 것이 아니라 각각 어떤 질문에 답하기 위해 사용하는지 설명할 수 있어야 한다.

## Recovery

실패한 rollout에서

```text
이상 발견
→ 상태 확인
→ 원인 확인
→ 정상 revision 식별
→ rollback
→ Ready 상태 검증
→ HTTP 정상화 검증
```

흐름을 실제로 완료했다.

## Evidence

모든 필수 시나리오에 다음이 남아 있다.

- 실행 명령
- 실제 관찰 결과
- 상태 해석
- 필요한 경우 복구 명령
- 정상화 결과

## Interview Readiness

README 없이 다음 질문에 자신의 실험을 근거로 답할 수 있다.

1. Deployment와 Pod의 역할 차이는 무엇인가?
2. 삭제한 Pod가 왜 다시 생성되었는가?
3. `Running`인데 `Ready`가 아닐 수 있는 이유는?
4. readiness failure가 발생하면 Service에는 어떤 변화가 생기는가?
5. liveness failure가 발생하면 무엇이 restart되는가?
6. readiness와 liveness를 왜 따로 두는가?
7. Rolling Update 동안 Deployment와 ReplicaSet은 어떻게 변하는가?
8. 실패한 rollout을 어떤 증거로 발견했는가?
9. rollback 후 정상화를 무엇으로 확인했는가?
10. CPU/memory requests와 limits를 왜 설정했는가?
11. Kubernetes 장애 발생 시 어떤 순서로 상태를 조사했는가?

위 질문에 실제 실행 evidence와 연결해 설명할 수 있으면 프로젝트를 완료한다.

---

# 10. 최종 프로젝트 정의

> **kind 환경에서 최소 Spring Boot 서비스를 Kubernetes에 배포하고, Pod self-healing, readiness 기반 트래픽 제어, liveness 기반 container restart, rolling update, 실패 rollout과 rollback, scaling을 직접 재현하여 Kubernetes의 reconciliation과 운영 진단 과정을 증명하는 실습 프로젝트.**

이 프로젝트의 성공 기준은 Kubernetes 기술을 많이 사용하는 것이 아니다.

**작은 시스템의 정상 상태와 실패 상태를 직접 만들고, 관찰하고, 원인을 설명하고, 정상 상태로 복구할 수 있는 것**이 최종 완료 기준이다.
