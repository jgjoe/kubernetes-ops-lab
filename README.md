# kubernetes-ops-lab

kind 환경에서 최소 Spring Boot 서비스를 사용해 Kubernetes의 배포, 관찰, 장애, 업데이트, 복구 동작을 재현하는 운영 실습 프로젝트다.

구현 범위와 완료 기준의 정본은 [Kubernetes 운영 실습 — Mini PRD v1.0 FINAL](docs/mini-prd-v1.0.md)이다.

## Slice 1 구현: 애플리케이션

- Java 17 + Spring Boot 3.5.16 + Maven Wrapper. 전역 Maven 설치 없이 `.\mvnw.cmd`로 build한다.
- Kubernetes 동작 검증에 필요한 endpoint만 제공한다. 비즈니스 API와 Actuator는 없다.
- readiness/liveness는 프로세스 메모리에만 저장한다. 프로세스가 시작하면 둘 다 `true`이고, 애플리케이션은 스스로 종료하거나 재시작하지 않는다.

## Slice 2 구현: 컨테이너 이미지

- 루트 `Dockerfile`은 Java 17 multi-stage build다. build stage는 전역 Maven 없이 저장소 Maven Wrapper로 `package`만 실행하고, runtime stage는 Java 17 JRE와 실행할 `app.jar`만 담는다.
- 애플리케이션 port는 8080이며 이미지에 `HEALTHCHECK`는 없다. probe 의미는 Slice 1과 같다.
- `.dockerignore`가 `target`, `.git`, IDE 파일, 문서를 build context에서 제외한다.
- Slice 2에서는 container image까지만 다루고, Kubernetes manifest와 kind 배포는 Slice 3부터 이어진다. CI/CD는 이 프로젝트의 non-goal이다.

### Endpoints

| Method | Path | 동작 | 응답 |
| --- | --- | --- | --- |
| GET | `/` | 서비스 식별 | 200 `kubernetes-ops-lab` |
| GET | `/version` | 설정된 version | 200 `v1` (기본값) |
| GET | `/health/ready` | 현재 readiness | 200 `ready` / 503 `not-ready` |
| POST | `/health/ready?ready=true\|false` | readiness 변경 | 변경 후 상태 (200 `ready` / 503 `not-ready`) |
| GET | `/health/live` | 현재 liveness | 200 `alive` / 503 `not-alive` |
| POST | `/health/live?live=true\|false` | liveness 변경 | 변경 후 상태 (200 `alive` / 503 `not-alive`) |

- POST는 값을 바꾼 뒤 바뀐 상태를 GET과 같은 규칙으로 보고한다. 그래서 `ready=false` POST는 503을 반환한다.
- 값이 잘못되었거나(`ready=maybe`) parameter가 없으면 400이며 상태는 바뀌지 않는다.
- liveness failure가 일시적인 이유는 상태가 메모리에만 있기 때문이다. container가 재시작되면 새 프로세스가 다시 200을 응답한다.

### version 설정

기본값은 `v1`이고 `com.example.kubernetesopslab.config.LabProperties`에 정의되어 있다. `app.version` 키를 외부에서 덮어쓸 수 있으며, Kubernetes에서는 ConfigMap → 환경변수 주입에 해당한다. `src/main/resources/application.properties`에 같은 키를 주석으로 문서화했다.

| 방법 | 예 |
| --- | --- |
| 환경변수 | `$env:APP_VERSION = "v2"` |
| program argument | `java -jar .\target\kubernetes-ops-lab-0.0.1-SNAPSHOT.jar --app.version=v2` |

## Windows PowerShell 검증 (Slice 1: 로컬 JAR 실행)

전제: JDK 17과 `JAVA_HOME`. Maven은 설치하지 않아도 된다. 첫 실행에서 `.\mvnw.cmd`가 Maven을 내려받는다.

### test / package

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
```

### 실행

```powershell
java -jar .\target\kubernetes-ops-lab-0.0.1-SNAPSHOT.jar
# 또는
.\mvnw.cmd spring-boot:run
```

version을 바꿔 실행하려면 다음을 먼저 실행한다.

```powershell
$env:APP_VERSION = "v2"
```

### endpoint 확인

```powershell
curl.exe -s http://localhost:8080/
curl.exe -s http://localhost:8080/version
curl.exe -s -o NUL -w "health/ready -> %{http_code}`n" http://localhost:8080/health/ready
curl.exe -s -o NUL -w "health/live -> %{http_code}`n" http://localhost:8080/health/live
```

### readiness failure → 복구

```powershell
curl.exe -s -X POST "http://localhost:8080/health/ready?ready=false"
curl.exe -s -o NUL -w "health/ready -> %{http_code}`n" http://localhost:8080/health/ready

curl.exe -s -X POST "http://localhost:8080/health/ready?ready=true"
curl.exe -s -o NUL -w "health/ready -> %{http_code}`n" http://localhost:8080/health/ready
```

### liveness failure → 복구

```powershell
curl.exe -s -X POST "http://localhost:8080/health/live?live=false"
curl.exe -s -o NUL -w "health/live -> %{http_code}`n" http://localhost:8080/health/live

curl.exe -s -X POST "http://localhost:8080/health/live?live=true"
curl.exe -s -o NUL -w "health/live -> %{http_code}`n" http://localhost:8080/health/live
```

Kubernetes에서는 같은 요청을 `kubectl port-forward`를 통해 보내 probe failure를 재현한다. kind cluster와 manifest는 다음 slice에서 추가한다.

## Windows PowerShell 검증 (Slice 2: 컨테이너 이미지)

전제: Docker Desktop(linux/amd64). build stage가 Maven Wrapper로 Maven을 내려받으므로 Docker 외에 다른 도구는 필요 없다.

이 PC(현재 검증 환경)에서는 host port 8080이 예약된 TCP 범위(8025-8124)에 걸려 `docker run -p 8080:8080`이 실패할 수 있다. Windows 일반이 아니라 이 환경에 한정된 제약이다. 아래 예시는 host 18081을 container 8080에 연결하며, container 안의 애플리케이션 port는 8080 그대로다.

### build와 이미지 metadata

```powershell
docker build -t kubernetes-ops-lab:v1 .
docker image inspect kubernetes-ops-lab:v1 --format "{{json .Config.ExposedPorts}}"   # {"8080/tcp":{}}
docker image inspect kubernetes-ops-lab:v1 --format "{{json .Config.Healthcheck}}"    # null (HEALTHCHECK 없음)
```

### 실행과 기동 대기

```powershell
docker run -d --name kubernetes-ops-lab-slice2-val -p 18081:8080 kubernetes-ops-lab:v1

# JVM이 뜰 때까지 재시도한다. Docker Desktop은 애플리케이션이 listen하기 전 연결을 끊으므로 --retry-all-errors를 쓴다.
curl.exe -s --retry 30 --retry-all-errors --retry-delay 1 -o NUL -w "health/live -> %{http_code}`n" http://localhost:18081/health/live
```

### endpoint 확인

```powershell
curl.exe -s http://localhost:18081/          # kubernetes-ops-lab
curl.exe -s http://localhost:18081/version   # v1
curl.exe -s -o NUL -w "health/ready -> %{http_code}`n" http://localhost:18081/health/ready
curl.exe -s -o NUL -w "health/live -> %{http_code}`n" http://localhost:18081/health/live
```

### readiness / liveness 전환

```powershell
curl.exe -s -X POST "http://localhost:18081/health/ready?ready=false"   # not-ready, 503
curl.exe -s -X POST "http://localhost:18081/health/ready?ready=true"    # ready, 200
curl.exe -s -X POST "http://localhost:18081/health/live?live=false"     # not-alive, 503

# liveness failure는 container를 종료시키지 않는다 (Running=true, RestartCount=0)
docker inspect --format "Running={{.State.Running}} RestartCount={{.RestartCount}}" kubernetes-ops-lab-slice2-val
```

### 같은 container 재시작 → 메모리 상태 초기화

```powershell
docker restart kubernetes-ops-lab-slice2-val
curl.exe -s --retry 30 --retry-all-errors --retry-delay 1 -o NUL -w "health/live -> %{http_code}`n" http://localhost:18081/health/live   # 200
```

### version override 확인

같은 host port 18081을 다시 사용하므로 version container를 시작하기 전에 validation container를 제거한다.

```powershell
docker rm -f kubernetes-ops-lab-slice2-val

docker run -d --name kubernetes-ops-lab-slice2-ver -p 18081:8080 -e APP_VERSION=verification-v2 kubernetes-ops-lab:v1
curl.exe -s --retry 30 --retry-all-errors --retry-delay 1 -o NUL -w "version -> %{http_code}`n" http://localhost:18081/version
curl.exe -s http://localhost:18081/version   # verification-v2
```

### 정리

```powershell
docker rm -f kubernetes-ops-lab-slice2-ver
docker ps -a --filter "name=kubernetes-ops-lab-slice2"   # 출력이 없어야 한다
```

## Windows PowerShell 검증 (Slice 3: kind 최초 배포)

이번 slice는 새 kind cluster에 `v1` 이미지를 올리고 `Namespace → ConfigMap → Deployment → ReplicaSet → Pods → Service` 관계가 실제로 만들어지는지 확인한다. Probe failure와 Pod 삭제 같은 장애 실험은 아직 수행하지 않는다.

전제:

- Docker Desktop이 실행 중이다.
- `kind`, `kubectl`이 설치되어 있다.
- 로컬 Docker에 `kubernetes-ops-lab:v1` 이미지가 존재한다.
- 현재 검증 환경(kind v0.33.0, 기본 node image Kubernetes v1.37.0)에서는 Docker가 cgroup v2를 사용해야 control-plane이 정상 기동했다. 먼저 다음 값이 `CgroupVersion=2`인지 확인한다.

```powershell
docker info --format 'CgroupVersion={{.CgroupVersion}} CgroupDriver={{.CgroupDriver}} Kernel={{.KernelVersion}}'
```

이 환경에서 `CgroupVersion=1`이면 `%USERPROFILE%\.wslconfig`의 기존 `[wsl2]` 섹션에 다음 kernel command line을 설정한 뒤 `wsl --shutdown`하고 Docker Desktop을 다시 시작해 cgroup v2로 전환했다.

```ini
[wsl2]
kernelCommandLine=systemd.unified_cgroup_hierarchy=1 cgroup_no_v1=all
```

이미 `[wsl2]` 또는 `kernelCommandLine` 설정이 있다면 중복 섹션을 만들거나 기존 옵션을 덮어쓰지 말고 필요한 옵션을 합친다.

### cluster 생성과 상태 확인

```powershell
kind create cluster --name kubernetes-ops-lab
kubectl cluster-info --context kind-kubernetes-ops-lab
kubectl get nodes
```

### 로컬 image를 kind node에 적재

kind node는 호스트 Docker image를 자동으로 사용하지 않으므로 명시적으로 적재한다.

```powershell
kind load docker-image kubernetes-ops-lab:v1 --name kubernetes-ops-lab
```

### manifest 적용

Namespace를 먼저 만든 뒤 namespaced resource를 적용한다.

```powershell
kubectl apply -f .\k8s\namespace.yaml
kubectl apply -f .\k8s\configmap.yaml
kubectl apply -f .\k8s\deployment.yaml
kubectl apply -f .\k8s\service.yaml

kubectl rollout status deployment/kubernetes-ops-lab -n kubernetes-ops-lab --timeout=120s
```

### Deployment → ReplicaSet → Pods → Service 관찰

```powershell
kubectl get deployment,replicaset,pod,service -n kubernetes-ops-lab -o wide
kubectl get endpointslice -n kubernetes-ops-lab -l kubernetes.io/service-name=kubernetes-ops-lab -o wide
```

확인할 것:

- Deployment의 desired/ready replica가 `2/2`다.
- Deployment가 ReplicaSet을 만들었고 ReplicaSet이 두 Pod를 유지한다.
- 두 Pod가 `Running`이면서 `READY 1/1`이다.
- Service selector와 Pod label이 연결되고 EndpointSlice에 Ready Pod 주소가 들어간다.

### Probe와 requests/limits가 실제 Pod spec에 적용됐는지 확인

```powershell
$pod = kubectl get pod -n kubernetes-ops-lab -l app.kubernetes.io/name=kubernetes-ops-lab -o jsonpath='{.items[0].metadata.name}'

kubectl describe pod $pod -n kubernetes-ops-lab
kubectl get pod $pod -n kubernetes-ops-lab -o jsonpath='{.spec.containers[0].resources}'
Write-Host
```

`describe`에서 `/health/ready`, `/health/live` probe를 확인하고 resource 출력에서 다음 설정이 실제 Pod에 적용됐는지 확인한다.

- requests: CPU `100m`, memory `128Mi`
- limits: CPU `500m`, memory `256Mi`

`requests`는 scheduler가 배치에 필요한 자원을 판단하는 기준이고, `limits`는 container가 사용할 수 있는 자원의 상한이다. 이번 프로젝트에서는 부하, throttling, OOM 실험으로 확장하지 않는다.

### Service를 통한 실제 HTTP 확인

먼저 host port 18080이 비어 있는지 확인한다. 출력이 있다면 해당 PID가 이전 실습 프로세스인지 확인한 뒤 정리하거나 다른 빈 host port를 사용한다. 포트 충돌 상태에서 실행하면 `kubectl port-forward`가 실패하고, 이후 `curl`이 Kubernetes가 아닌 기존 프로세스에 연결될 수 있다.

```powershell
Get-NetTCPConnection -LocalPort 18080 -State Listen -ErrorAction SilentlyContinue
```

첫 PowerShell 창에서 port-forward를 유지한다.

```powershell
kubectl port-forward -n kubernetes-ops-lab service/kubernetes-ops-lab 18080:80
```

두 번째 PowerShell 창에서 확인한다.

```powershell
curl.exe -i http://localhost:18080/
curl.exe -i http://localhost:18080/version
curl.exe -i http://localhost:18080/health/ready
curl.exe -i http://localhost:18080/health/live
```

정상 기준:

- `/` → HTTP 200, `kubernetes-ops-lab`
- `/version` → HTTP 200, `v1`
- `/health/ready` → HTTP 200, `ready`
- `/health/live` → HTTP 200, `alive`

애플리케이션 JVM이 8080에서 listen하기 전에 readiness probe가 먼저 실행되면 초기 Event에 일시적인 `connection refused`가 남을 수 있다. 이후 Pod가 `Ready=True`가 되고 `Restart Count=0`을 유지하면 baseline 실패가 아니다. readiness 실패 자체는 container restart를 유발하지 않는다.

cluster는 다음 self-healing/readiness/liveness 실습에서 그대로 사용하므로 이 slice가 끝나도 삭제하지 않는다. 전체 실습 종료 또는 재현성 확인 시 다음 명령으로 삭제할 수 있다.

```powershell
kind delete cluster --name kubernetes-ops-lab
```

## Windows PowerShell 검증 (Slice 4: Pod 삭제와 Self-Healing)

이번 slice는 실행 중인 Pod 하나를 직접 삭제하고, Deployment/ReplicaSet이 선언된 replica 수 `2`를 다시 만족시키기 위해 새 Pod를 생성하는 과정을 관찰한다. 삭제된 Pod 자체가 되살아나는 것이 아니라 **새 이름·새 UID·새 IP를 가진 replacement Pod**가 만들어지는 것이 핵심이다.

### 삭제 전 상태와 대상 Pod 기록

첫 PowerShell 창에서 Pod 변화를 계속 관찰한다.

```powershell
kubectl get pods -n kubernetes-ops-lab -w
```

두 번째 PowerShell 창에서 삭제할 Pod를 선택하고 identity를 기록한다.

```powershell
$victim = kubectl get pod -n kubernetes-ops-lab -l app.kubernetes.io/name=kubernetes-ops-lab -o jsonpath='{.items[0].metadata.name}'
$victim

kubectl get pod $victim -n kubernetes-ops-lab -o custom-columns='NAME:.metadata.name,UID:.metadata.uid,IP:.status.podIP,READY:.status.containerStatuses[0].ready'
```

### Pod 삭제와 replacement 관찰

```powershell
kubectl delete pod $victim -n kubernetes-ops-lab --wait=false
```

watch 창에서 가능한 범위까지 다음 흐름을 확인한다.

- 기존 Pod가 `Terminating`으로 내려간다.
- 새 Pod가 다른 이름으로 생성된다.
- 새 Pod가 `Pending → ContainerCreating → Running → READY 1/1`로 변한다.
- 중간 상태는 매우 짧아 일부가 보이지 않을 수 있다.

복구 후 상태와 Service endpoint를 확인한다.

```powershell
kubectl get deployment,replicaset,pod -n kubernetes-ops-lab -o wide
kubectl get endpointslice -n kubernetes-ops-lab -l kubernetes.io/service-name=kubernetes-ops-lab -o wide
kubectl get events -n kubernetes-ops-lab --sort-by=.lastTimestamp
```

정상 기준:

- Deployment가 다시 `READY 2/2`다.
- ReplicaSet이 다시 `DESIRED/CURRENT/READY 2/2/2`다.
- 삭제한 Pod 이름은 사라지고 새 Pod 이름이 존재한다.
- replacement Pod의 UID와 IP는 삭제한 Pod와 다르다.
- ReplicaSet Event에 replacement Pod에 대한 `SuccessfulCreate`가 남는다.
- EndpointSlice가 다시 Ready Pod 두 개를 가리킨다.

실제 관찰에서는 삭제된 Pod `...-6628g`의 IP `10.244.0.6` 대신 새 Pod `...-cvfvg`가 IP `10.244.0.7`로 생성됐고, ReplicaSet Event에도 새 Pod 생성이 기록됐다. 이 결과는 controller가 삭제된 Pod를 복원한 것이 아니라 **현재 상태가 desired replicas 2보다 부족해지자 새 Pod를 생성해 desired state를 다시 맞췄다**는 증거다.

### Service 정상화 확인

Slice 3에서 실행한 Service port-forward(18080)가 아직 살아 있으면 그대로 재사용한다. 종료했다면 host port가 비어 있는지 확인한 뒤 별도 PowerShell 창에서 다시 실행한다. 이미 18080이 listen 중이면 중복으로 port-forward를 시작하지 않는다.

```powershell
Get-NetTCPConnection -LocalPort 18080 -State Listen -ErrorAction SilentlyContinue
# 위 명령에 출력이 없을 때만 별도 창에서 실행
kubectl port-forward -n kubernetes-ops-lab service/kubernetes-ops-lab 18080:80
```

다른 PowerShell 창에서:

```powershell
curl.exe -i http://localhost:18080/
curl.exe -i http://localhost:18080/version
```

정상 기준:

- `/` → HTTP 200, `kubernetes-ops-lab`
- `/version` → HTTP 200, `v1`

cluster는 다음 readiness/liveness 실습에서 계속 사용한다.

## Windows PowerShell 검증 (Slice 5: Readiness Failure)

이번 slice는 한 Pod의 readiness만 의도적으로 실패시켜 **프로세스와 container는 계속 살아 있지만 Service 트래픽 대상에서는 제외되는 상태**를 확인한다. liveness failure와 달리 container restart가 일어나지 않는 것이 핵심이다.

### 대상 Pod와 baseline 확인

```powershell
$target = kubectl get pod -n kubernetes-ops-lab -l app.kubernetes.io/name=kubernetes-ops-lab -o jsonpath='{.items[0].metadata.name}'
$target
kubectl get pod $target -n kubernetes-ops-lab -o custom-columns='NAME:.metadata.name,IP:.status.podIP,PHASE:.status.phase,READY:.status.containerStatuses[0].ready,RESTARTS:.status.containerStatuses[0].restartCount'
```

PowerShell 변수는 새 창에 자동으로 전달되지 않는다. 다른 PowerShell 창에서 `$target`을 사용할 때는 그 창에서도 다시 설정하거나 Pod 이름을 직접 사용한다.

Pod 상태 변화를 별도 창에서 관찰한다.

```powershell
kubectl get pods -n kubernetes-ops-lab -w
```

대상 Pod에만 직접 요청하기 위해 다른 창에서 Pod port-forward를 실행한다.

```powershell
$target = kubectl get pod -n kubernetes-ops-lab -l app.kubernetes.io/name=kubernetes-ops-lab -o jsonpath='{.items[0].metadata.name}'
kubectl port-forward -n kubernetes-ops-lab pod/$target 18081:8080
```

baseline은 `/health/live`와 `/health/ready`가 모두 HTTP 200이다.

```powershell
curl.exe -i http://localhost:18081/health/live
curl.exe -i http://localhost:18081/health/ready
```

### Readiness failure 유발과 관찰

```powershell
curl.exe -i -X POST "http://localhost:18081/health/ready?ready=false"
Start-Sleep -Seconds 5
```

대상 Pod와 Service endpoint를 확인한다.

```powershell
kubectl get pod $target -n kubernetes-ops-lab -o custom-columns='NAME:.metadata.name,PHASE:.status.phase,READY:.status.containerStatuses[0].ready,RESTARTS:.status.containerStatuses[0].restartCount'
kubectl get endpointslice -n kubernetes-ops-lab -l kubernetes.io/service-name=kubernetes-ops-lab -o jsonpath='{range .items[*].endpoints[*]}{.addresses[0]}{" ready="}{.conditions.ready}{"`n"}{end}'

curl.exe -i http://localhost:18081/
curl.exe -i http://localhost:18081/health/live
curl.exe -i http://localhost:18081/health/ready
```

정상적인 failure 상태:

- 대상 Pod의 phase는 계속 `Running`이다.
- 대상 Pod의 `READY`만 `false`가 된다.
- `RestartCount`는 `0`으로 유지된다.
- `/`와 `/health/live`는 HTTP 200으로 계속 응답한다.
- `/health/ready`만 HTTP 503 `not-ready`를 반환한다.
- EndpointSlice에서 대상 Pod만 `ready=false`가 되고 다른 replica는 `ready=true`를 유지한다.

이 상태에서 Service를 통해 정상 replica가 계속 요청을 처리하는지 확인한다. 기존 18080 Service port-forward가 살아 있으면 재사용하고, 종료했다면 별도 창에서 다시 실행한다.

```powershell
Get-NetTCPConnection -LocalPort 18080 -State Listen -ErrorAction SilentlyContinue
# 위 명령에 출력이 없을 때만 별도 창에서 실행
kubectl port-forward -n kubernetes-ops-lab service/kubernetes-ops-lab 18080:80
```

다른 창에서:

```powershell
curl.exe -i http://localhost:18080/
curl.exe -i http://localhost:18080/version
```

정상 기준은 HTTP 200 `kubernetes-ops-lab`, HTTP 200 `v1`이다.

### Readiness 복구

대상 Pod 직접 port-forward인 18081을 통해 readiness를 정상화한다.

```powershell
curl.exe -i -X POST "http://localhost:18081/health/ready?ready=true"
Start-Sleep -Seconds 5

kubectl get pod $target -n kubernetes-ops-lab -o custom-columns='NAME:.metadata.name,PHASE:.status.phase,READY:.status.containerStatuses[0].ready,RESTARTS:.status.containerStatuses[0].restartCount'
kubectl get endpointslice -n kubernetes-ops-lab -l kubernetes.io/service-name=kubernetes-ops-lab -o jsonpath='{range .items[*].endpoints[*]}{.addresses[0]}{" ready="}{.conditions.ready}{"`n"}{end}'
kubectl get events -n kubernetes-ops-lab --sort-by=.lastTimestamp
```

복구 기준:

- 같은 Pod가 `Running`, `Ready=true`, `RestartCount=0`으로 돌아온다.
- EndpointSlice에서 두 Pod 모두 다시 `ready=true`가 된다.
- Event에 의도한 readiness probe HTTP 503 실패가 남는다.

실제 관찰에서는 대상 Pod `...-cvfvg`의 IP `10.244.0.7`이 `ready=false`가 되었지만 container restart 없이 계속 Running 상태를 유지했고, 다른 Pod `10.244.0.5`는 `ready=true`를 유지해 Service가 계속 HTTP 200을 반환했다. 복구 후 동일한 대상 Pod가 restart 없이 다시 `ready=true`가 되었고 EndpointSlice에도 복귀했다.

따라서 readiness failure는 **실행 중인 container를 재시작하는 신호가 아니라, 해당 Pod를 Service 트래픽 대상으로 사용할 준비가 되었는지를 나타내는 신호**임을 확인할 수 있다.

Slice 6으로 넘어가기 전에 대상 Pod용 18081 port-forward 창은 `Ctrl+C`로 종료한다. Service용 18080 port-forward는 계속 재사용해도 된다.

## Windows PowerShell 검증 (Slice 6: Liveness Failure)

이번 slice는 한 Pod의 liveness를 의도적으로 실패시켜 kubelet이 **Pod를 새로 만들지 않고 같은 Pod 안의 container를 재시작**하는 과정을 확인한다. Readiness failure와 달리 `RestartCount`가 증가하고 container ID가 바뀌는 것이 핵심이다.

### 대상 Pod와 baseline identity 기록

대상 Pod를 하나 선택하고 이름, UID, IP, restart count, container ID를 기록한다.

```powershell
$target = kubectl get pod -n kubernetes-ops-lab -l app.kubernetes.io/name=kubernetes-ops-lab -o jsonpath='{.items[0].metadata.name}'
$target

kubectl get pod $target -n kubernetes-ops-lab -o custom-columns='NAME:.metadata.name,UID:.metadata.uid,IP:.status.podIP,READY:.status.containerStatuses[0].ready,RESTARTS:.status.containerStatuses[0].restartCount,CONTAINER:.status.containerStatuses[0].containerID'
```

다른 PowerShell 창에서 대상 Pod를 watch한다.

```powershell
$target = kubectl get pod -n kubernetes-ops-lab -l app.kubernetes.io/name=kubernetes-ops-lab -o jsonpath='{.items[0].metadata.name}'
kubectl get pod $target -n kubernetes-ops-lab -w
```

또 다른 창에서 대상 Pod에 직접 port-forward한다. 새 PowerShell에서는 `$target`을 다시 설정하거나 Pod 이름을 직접 사용한다.

```powershell
kubectl port-forward -n kubernetes-ops-lab pod/$target 18081:8080
```

baseline은 liveness와 readiness가 모두 HTTP 200이다.

```powershell
curl.exe -i http://localhost:18081/health/live
curl.exe -i http://localhost:18081/health/ready
```

### Liveness failure 유발과 container restart 관찰

```powershell
curl.exe -i -X POST "http://localhost:18081/health/live?live=false"
```

의도한 failure 응답은 HTTP 503 `not-alive`다. 현재 probe 설정은 `periodSeconds=2`, `failureThreshold=3`이므로 kubelet이 연속 실패를 확인한 뒤 container를 재시작한다.

```powershell
Start-Sleep -Seconds 10

kubectl get pod $target -n kubernetes-ops-lab -o custom-columns='NAME:.metadata.name,UID:.metadata.uid,IP:.status.podIP,READY:.status.containerStatuses[0].ready,RESTARTS:.status.containerStatuses[0].restartCount,CONTAINER:.status.containerStatuses[0].containerID'
kubectl describe pod $target -n kubernetes-ops-lab
kubectl get events -n kubernetes-ops-lab --sort-by=.lastTimestamp
```

정상 기준:

- Pod 이름과 UID가 baseline과 동일하다.
- Pod IP도 동일하게 유지되는 것을 확인할 수 있다.
- `RestartCount`가 `0 → 1`로 증가한다.
- container ID는 새로운 값으로 바뀐다.
- `describe`의 `Last State`에 이전 container 종료 상태가 남는다.
- Event에 liveness probe HTTP 503 실패와 `Container app failed liveness probe, will be restarted`가 기록된다.

이 결과는 Pod가 교체된 것이 아니라 **kubelet이 같은 Pod 안에서 container만 재시작했다**는 증거다. Slice 4의 Pod 삭제 실험에서는 Pod 이름·UID·IP가 바뀐 replacement Pod가 만들어졌다는 점과 구분한다.

### process-local 상태 초기화와 정상 복구 확인

애플리케이션의 probe 상태는 process-local 메모리이므로 새 Java process가 시작되면 기본 healthy 상태로 초기화된다. container restart 과정에서 Pod port-forward가 끊겼다면 같은 Pod에 다시 실행한다.

```powershell
kubectl port-forward -n kubernetes-ops-lab pod/$target 18081:8080
```

복구 상태를 확인한다.

```powershell
curl.exe -i http://localhost:18081/health/live
curl.exe -i http://localhost:18081/health/ready
curl.exe -i http://localhost:18081/version

kubectl get endpointslice -n kubernetes-ops-lab -l kubernetes.io/service-name=kubernetes-ops-lab -o jsonpath='{range .items[*].endpoints[*]}{.addresses[0]}{" ready="}{.conditions.ready}{"`n"}{end}'
```

복구 기준:

- `/health/live` → HTTP 200 `alive`
- `/health/ready` → HTTP 200 `ready`
- `/version` → HTTP 200 `v1`
- 대상 Pod가 다시 `Ready=true`가 된다.
- EndpointSlice에서 두 Pod가 모두 `ready=true`다.

실제 관찰에서는 대상 Pod `...-cvfvg`가 UID `74ec...`, IP `10.244.0.7`을 그대로 유지한 채 `RestartCount 0 → 1`로 증가했고 container ID가 `69d206... → 16efd1...`로 바뀌었다. Event에는 liveness HTTP 503 실패 뒤 kubelet의 restart 메시지가 기록됐다. 재시작 후 `/health/live`, `/health/ready`, `/version`이 각각 HTTP 200 `alive`, `ready`, `v1`로 정상화됐고 두 Service endpoint도 다시 `ready=true`였다.

따라서 **readiness failure는 traffic eligibility를 바꾸지만 container restart를 일으키지 않고, liveness failure는 kubelet이 unhealthy container를 같은 Pod 안에서 재시작하게 만든다**는 차이를 실제 실행 결과로 확인할 수 있다.

Slice 7에서는 kind cluster를 삭제하고 다시 만들기 때문에, Slice 6 종료 후 남아 있는 18081 Pod port-forward와 18080 Service port-forward 창은 모두 `Ctrl+C`로 종료한다.

## Windows PowerShell 검증 (Slice 7: Successful Rolling Update v1 → v2)

이번 slice는 clean kind cluster에서 revision 1의 v1 baseline을 만든 뒤 v2 release manifest를 적용해 Deployment의 Rolling Update 동작을 관찰한다.

release는 image tag와 versioned ConfigMap을 함께 바꾼다.

```text
v1 = kubernetes-ops-lab:v1 + kubernetes-ops-lab-config-v1
v2 = kubernetes-ops-lab:v2 + kubernetes-ops-lab-config-v2
```

Deployment의 rollout 전략은 `maxUnavailable: 0`, `maxSurge: 1`이다. 따라서 기존 Ready replica를 먼저 잃지 않고 신규 Pod를 추가해 readiness를 확인한 뒤 old replica를 줄인다.

### v2/v3 image tag 준비와 clean cluster 재생성

이 slice부터는 rollout history를 `revision 1 = v1`, `revision 2 = v2`, `revision 3 = failed v3` 순서로 깔끔하게 관찰하기 위해 앞선 실습 cluster를 삭제하고 새 kind cluster를 만든다. 앞선 Slice 3~6의 실제 evidence는 이미 README에 남아 있으므로 cluster를 재생성해도 된다.

애플리케이션 코드는 바꾸지 않고 동일한 v1 image에 release tag만 추가한다.

```powershell
docker tag kubernetes-ops-lab:v1 kubernetes-ops-lab:v2
docker tag kubernetes-ops-lab:v1 kubernetes-ops-lab:v3
docker images kubernetes-ops-lab
```

세 tag의 IMAGE ID가 같아도 정상이다. 이 lab에서 release 차이는 Deployment의 image tag 문자열과 versioned ConfigMap reference로 표현한다.

기존 cluster를 지우고 새 cluster를 만든 뒤 Node가 Ready가 될 때까지 기다린다.

```powershell
kind delete cluster --name kubernetes-ops-lab
kind create cluster --name kubernetes-ops-lab
kubectl wait --for=condition=Ready nodes --all --timeout=120s
```

새 kind node에는 host Docker image가 없으므로 세 tag를 모두 다시 적재한다.

```powershell
kind load docker-image `
  kubernetes-ops-lab:v1 `
  kubernetes-ops-lab:v2 `
  kubernetes-ops-lab:v3 `
  --name kubernetes-ops-lab
```

### clean v1 baseline

새 cluster에 v1 baseline을 다시 배포하고 rollout history와 현재 상태를 확인한다.

```powershell
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml

kubectl rollout status deployment/kubernetes-ops-lab -n kubernetes-ops-lab --timeout=60s
kubectl rollout history deployment/kubernetes-ops-lab -n kubernetes-ops-lab
kubectl get deployment,replicaset,pod -n kubernetes-ops-lab -o wide
kubectl get configmap -n kubernetes-ops-lab
```

Service HTTP 검증을 위해 별도 PowerShell 창에서 port-forward를 유지한다.

```powershell
kubectl port-forward -n kubernetes-ops-lab service/kubernetes-ops-lab 18080:80
```

다른 창에서 baseline 응답을 확인한다.

```powershell
curl.exe -i http://localhost:18080/
curl.exe -i http://localhost:18080/version
```

baseline 기준:

- revision 1의 change cause는 `release v1`이다.
- Deployment는 `2/2` Ready다.
- v1 ReplicaSet은 desired/current/ready가 모두 2다.
- 두 v1 Pod가 `1/1 Running`, restart 0이다.
- `kubernetes-ops-lab-config-v1`이 존재한다.
- Service `/version`은 HTTP 200 `v1`을 반환한다.

### ReplicaSet과 Pod를 나눠서 watch

현재 `kubectl`에서는 `-w`와 함께 `replicaset,pod`처럼 여러 resource type을 한 번에 지정할 수 없으므로 두 PowerShell 창으로 나눠 관찰한다.

ReplicaSet watch:

```powershell
kubectl get replicaset -n kubernetes-ops-lab -w
```

Pod watch:

```powershell
kubectl get pod -n kubernetes-ops-lab -w
```

### v2 rollout

```powershell
kubectl apply -f k8s/configmap-v2.yaml
kubectl apply -f k8s/deployment-v2.yaml

kubectl rollout status deployment/kubernetes-ops-lab -n kubernetes-ops-lab --timeout=60s
```

실제 ReplicaSet watch에서는 다음 순서를 확인했다.

```text
v1 RS 94648b4bc: desired 2 / ready 2
→ v2 RS 64cb45ffcc 생성
→ v2 RS desired 1 / ready 1
→ v1 RS desired 1
→ v2 RS desired 2
→ v2 RS ready 2
→ v1 RS desired 0 / current 0 / ready 0
```

Pod watch에서는 첫 v2 Pod `...-8p82k`가 `Pending → ContainerCreating → Running → 1/1 Ready`가 된 뒤 기존 v1 Pod 하나가 종료됐고, 두 번째 v2 Pod `...-flgr9`가 `1/1 Ready`가 된 뒤 마지막 v1 Pod가 종료되는 순서를 확인했다.

`kubectl rollout status`도 최종적으로 다음과 같이 성공했다.

```text
deployment "kubernetes-ops-lab" successfully rolled out
```

### rollout 완료 상태

```powershell
kubectl rollout history deployment/kubernetes-ops-lab -n kubernetes-ops-lab
kubectl get deployment,replicaset,pod -n kubernetes-ops-lab -o wide
kubectl get deployment kubernetes-ops-lab -n kubernetes-ops-lab -o jsonpath='image={.spec.template.spec.containers[0].image}{"`n"}config={.spec.template.spec.containers[0].env[0].valueFrom.configMapKeyRef.name}{"`n"}'
```

실제 최종 상태:

- rollout history는 `1 = release v1`, `2 = release v2`다.
- Deployment는 `2/2`, image는 `kubernetes-ops-lab:v2`다.
- v2 ReplicaSet `64cb45ffcc`는 desired/current/ready `2/2/2`다.
- v1 ReplicaSet `94648b4bc`는 `0/0/0`으로 축소됐다.
- v2 Pod 두 개가 모두 `1/1 Running`, restart 0이다.
- Deployment의 ConfigMap reference는 `kubernetes-ops-lab-config-v2`다.
- v1과 v2 ConfigMap은 rollback을 위해 둘 다 유지된다.
- EndpointSlice에는 v2 Pod IP `10.244.0.7`, `10.244.0.8`이 연결됐다.

Service 최종 확인:

```powershell
curl.exe -i http://localhost:18080/version
curl.exe -i http://localhost:18080/health/ready
```

실제 응답은 `/version`이 HTTP 200 `v2`, `/health/ready`가 HTTP 200 `ready`였다.

따라서 Rolling Update는 **새 ReplicaSet과 신규 Ready Pod를 먼저 확보하고 old ReplicaSet을 점진적으로 축소해 desired state를 v2로 전환**한다는 것을 실제 상태 변화로 확인할 수 있다.

참고로 rollout 전에 실행한 `/version` 반복 요청은 모두 `v1`로 끝났기 때문에 rollout 중 v1/v2 혼재 트래픽을 보여주는 evidence로 사용하지 않는다.

## Windows PowerShell 검증 (Slice 8: Failed Rollout v3 → Diagnosis → Rollback)

이번 slice는 정상 revision 2(v2) 상태에서 readiness probe path가 잘못된 v3 manifest를 배포해 rollout 실패를 재현하고, 원인을 진단한 뒤 마지막 정상 v2 Pod template으로 rollback한다.

v3의 의도된 실패 조건은 다음과 같다.

```text
image: kubernetes-ops-lab:v3
config: kubernetes-ops-lab-config-v3
readiness probe: /health/ready-broken
```

### v3 failed rollout

```powershell
kubectl apply -f k8s/configmap-v3.yaml
kubectl apply -f k8s/deployment-v3-bad.yaml

kubectl rollout status deployment/kubernetes-ops-lab -n kubernetes-ops-lab --timeout=90s
```

실제 rollout은 다음 메시지로 실패했다.

```text
error: deployment "kubernetes-ops-lab" exceeded its progress deadline
```

실패 시점의 상태:

- Deployment는 `READY 2/2`, `AVAILABLE 2`였지만 `UP-TO-DATE 1`이었다.
- 기존 v2 ReplicaSet `64cb45ffcc`는 desired/current/ready `2/2/2`를 유지했다.
- 신규 v3 ReplicaSet `7887f7776c`는 desired/current/ready `1/1/0`에서 멈췄다.
- v3 Pod `...-4rqgx`는 container 상태가 `Running`, restart 0이지만 Pod readiness는 `0/1`이었다.
- rollout history에는 revision 3이 `release v3 with broken readiness probe`로 추가됐다.
- Deployment condition은 `Available=True`이면서 `Progressing=False`, reason은 `ProgressDeadlineExceeded`였다.

이 상태는 **기존 정상 replica가 서비스 가용성을 유지하고 있어도 새 release rollout 자체는 실패할 수 있다**는 점을 보여준다.

### get → describe/events → logs로 원인 진단

v3 Pod를 찾고 상태를 확인한다.

```powershell
kubectl get pod -n kubernetes-ops-lab -o custom-columns='NAME:.metadata.name,READY:.status.containerStatuses[0].ready,RESTARTS:.status.containerStatuses[0].restartCount,IMAGE:.spec.containers[0].image,IP:.status.podIP'
```

실제 v3 Pod는 image `kubernetes-ops-lab:v3`, `READY=false`, restart 0이었다.

```powershell
$badPod = kubectl get pod -n kubernetes-ops-lab -o jsonpath='{.items[?(@.spec.containers[0].image=="kubernetes-ops-lab:v3")].metadata.name}'
$badPod

kubectl describe pod $badPod -n kubernetes-ops-lab
kubectl get events -n kubernetes-ops-lab --sort-by=.lastTimestamp
kubectl logs $badPod -n kubernetes-ops-lab
```

`describe`와 Events에는 startup 직후 일시적인 connection refused 뒤, 잘못된 readiness 경로에 대한 HTTP 404가 반복 기록됐다.

```text
Readiness probe failed: HTTP probe failed with statuscode: 404
```

반면 application log에서는 Spring Boot와 Tomcat이 정상적으로 기동했다. 따라서 원인은 container crash나 liveness failure가 아니라 **Deployment manifest의 readiness probe path 오류**로 판단할 수 있다.

실패 중 Service도 확인한다.

```powershell
curl.exe -i http://localhost:18080/version
curl.exe -i http://localhost:18080/health/ready
kubectl get endpointslice -n kubernetes-ops-lab -l kubernetes.io/service-name=kubernetes-ops-lab -o jsonpath='{range .items[*].endpoints[*]}{.addresses[0]}{" ready="}{.conditions.ready}{"`n"}{end}'
```

실제 Service 응답은 `/version` HTTP 200 `v2`, `/health/ready` HTTP 200 `ready`였다. EndpointSlice에는 기존 v2 Pod IP `10.244.0.7`, `10.244.0.8`이 `ready=true`, v3 Pod IP `10.244.0.9`가 `ready=false`로 나타났다.

즉 readiness failure 때문에 v3 Pod는 Service의 정상 traffic 대상이 되지 않았고 기존 v2 replica가 계속 요청을 처리했다.

### 마지막 정상 v2 revision으로 rollback

실습에서는 이전 정상 revision 2를 확인한 뒤 명시적으로 rollback했다.

```powershell
kubectl rollout undo deployment/kubernetes-ops-lab -n kubernetes-ops-lab --to-revision=2
kubectl rollout status deployment/kubernetes-ops-lab -n kubernetes-ops-lab --timeout=60s
```

`kubectl rollout undo`는 다음 경고를 출력했다.

```text
Rolling back will not update the kubectl.kubernetes.io/last-applied-configuration annotation
```

이는 `kubectl apply`로 관리하던 Deployment를 imperative rollback했기 때문에 last-applied annotation이 rollback 대상 manifest로 자동 동기화되지 않는다는 뜻이다. 이 실습에서는 rollback 동작 자체의 관찰 증거로 남기며, 이후 같은 Deployment에 다시 declarative apply를 할 때는 현재 manifest와 last-applied 상태를 확인한다.

rollback status는 최종적으로 성공했다.

```text
deployment "kubernetes-ops-lab" successfully rolled out
```

### rollback 후 실제 상태

```powershell
kubectl rollout history deployment/kubernetes-ops-lab -n kubernetes-ops-lab
kubectl get deployment,replicaset,pod -n kubernetes-ops-lab -o wide
```

실제 최종 상태:

- Deployment는 다시 `2/2`, `UP-TO-DATE 2`, `AVAILABLE 2`가 됐다.
- Deployment image는 `kubernetes-ops-lab:v2`다.
- ConfigMap reference는 `kubernetes-ops-lab-config-v2`다.
- Deployment revision은 `4`다.
- v2 ReplicaSet `64cb45ffcc`는 `2/2/2`를 유지한다.
- v3 ReplicaSet `7887f7776c`는 `0/0/0`으로 축소됐다.
- v2 Pod 두 개는 계속 `1/1 Running`, restart 0이다.
- Deployment condition은 `Available=True`, `Progressing=True`, reason `NewReplicaSetAvailable`로 복구됐다.
- EndpointSlice에는 v2 Pod `10.244.0.7`, `10.244.0.8`만 `ready=true`로 남았다.
- Service `/`, `/version`, `/health/ready`는 각각 HTTP 200 `kubernetes-ops-lab`, `v2`, `ready`를 반환했다.

첫 실제 실행에서는 rollback 직전에 current Deployment의 `kubernetes.io/change-cause`를 `rollback to release v2`로 덮어쓴 상태에서 undo를 수행했기 때문에 history가 다음처럼 관찰됐다.

```text
REVISION  CHANGE-CAUSE
1         release v1
3         rollback to release v2
4         release v2
```

여기서 revision 2가 그대로 남지 않고 정상 v2 template이 revision 4로 올라간 것은 rollback이 revision 번호를 과거 값으로 되감는 동작이 아니라 **이전 ReplicaSet의 Pod template을 현재 Deployment의 새 revision으로 복구하는 동작**이기 때문이다.

위 `revision 3 = rollback to release v2` 표시는 첫 실행에서 change-cause를 덮어쓴 데 따른 기록상의 부작용이다. 현재 README 재실행 절차에서는 이 annotation overwrite를 제거하고 `kubectl rollout undo --to-revision=2`만 수행한다. clean rerun에서는 실패 revision 3의 원래 `release v3 with broken readiness probe`를 보존하고, 복구된 v2 template이 새 revision 4로 올라가는 형태를 기대한다.

```text
REVISION  CHANGE-CAUSE
1         release v1
3         release v3 with broken readiness probe
4         release v2
```

따라서 이 slice에서는 **잘못된 readiness 설정 → 신규 Pod NotReady → progress deadline 초과 → 기존 v2 Service 가용성 유지 → 원인 진단 → 정상 v2 template rollback → Ready/HTTP 복구**의 전체 운영 흐름을 재현했다.

## Windows PowerShell 검증 (Slice 9: Resources 확인 + Manual Scaling 2 → 4 → 2)

이번 slice는 application container의 CPU/memory requests와 limits가 실제 Pod spec에 적용됐는지 다시 확인하고, Deployment의 desired replicas를 `2 → 4 → 2`로 변경해 reconciliation을 관찰한다. 별도 CPU 부하, throttling 측정, memory stress, OOMKill 실험은 수행하지 않는다.

### requests / limits 실제 적용 확인

```powershell
$pod = kubectl get pod -n kubernetes-ops-lab -l app.kubernetes.io/name=kubernetes-ops-lab -o jsonpath='{.items[0].metadata.name}'
kubectl get pod $pod -n kubernetes-ops-lab -o jsonpath='{.spec.containers[0].resources}'
```

실제 Pod spec:

```json
{"limits":{"cpu":"500m","memory":"256Mi"},"requests":{"cpu":"100m","memory":"128Mi"}}
```

`requests`는 scheduler가 Pod 배치 시 필요한 자원량을 판단하는 기준이고, `limits`는 container가 사용할 수 있는 자원의 상한이다. CPU limit 초과는 일반적으로 throttling으로 나타날 수 있지만 memory limit 초과는 OOM 종료로 이어질 수 있으므로 두 resource limit의 runtime 동작은 동일하지 않다. 이번 프로젝트는 설정 적용과 의미 설명까지만 범위로 한다.

### scale-out: 2 → 4

```powershell
kubectl scale deployment/kubernetes-ops-lab -n kubernetes-ops-lab --replicas=4
kubectl wait --for=jsonpath='{.status.readyReplicas}'=4 deployment/kubernetes-ops-lab -n kubernetes-ops-lab --timeout=60s
kubectl get deployment,replicaset,pod -n kubernetes-ops-lab -o wide
```

첫 실행에서는 scale 직후의 중간 상태도 관찰했다. Deployment는 `READY 2/4`, ReplicaSet은 desired/current `4/4`지만 ready는 2였고, 새 Pod 두 개는 `Running 0/1`이었다. EndpointSlice에서도 기존 두 endpoint만 `ready=true`, 새 두 endpoint는 `ready=false`였다. 이는 desired replica 수는 이미 4로 바뀌었지만 신규 Pod가 readiness를 통과하기 전인 reconciliation 중간 상태다.

다시 2 → 4를 수행하고 `readyReplicas=4`를 기다린 뒤 다음 최종 scale-out 상태를 확인했다.

- Deployment `READY 4/4`, `UP-TO-DATE 4`, `AVAILABLE 4`
- v2 ReplicaSet `64cb45ffcc` desired/current/ready `4/4/4`
- v2 Pod 네 개 모두 `1/1 Running`, restart 0
- EndpointSlice의 `10.244.0.7`, `10.244.0.8`, `10.244.0.12`, `10.244.0.13` 네 endpoint 모두 `ready=true`
- Service `/version`은 HTTP 200 `v2`
- Service `/health/ready`는 HTTP 200 `ready`

즉 scale-out에서는 기존 Pod를 교체한 것이 아니라 같은 v2 ReplicaSet이 desired state 4를 만족시키기 위해 새 Pod 두 개를 추가 생성했다.

### scale-in: 4 → 2

```powershell
kubectl scale deployment/kubernetes-ops-lab -n kubernetes-ops-lab --replicas=2
kubectl wait --for=jsonpath='{.status.readyReplicas}'=2 deployment/kubernetes-ops-lab -n kubernetes-ops-lab --timeout=60s
kubectl get deployment,replicaset,pod -n kubernetes-ops-lab -o wide
```

최종 scale-in 상태:

- Deployment `READY 2/2`, `UP-TO-DATE 2`, `AVAILABLE 2`
- v2 ReplicaSet desired/current/ready `2/2/2`
- v2 Pod 두 개 `1/1 Running`
- EndpointSlice는 다시 `10.244.0.7`, `10.244.0.8` 두 endpoint만 `ready=true`
- Service `/version`은 계속 HTTP 200 `v2`

따라서 manual scaling은 **Deployment의 desired replicas를 변경하면 controller가 ReplicaSet/Pod의 actual state를 새 desired state에 맞추도록 생성·삭제를 수행하는 reconciliation**이라는 것을 실제 `2 → 4 → 2` 상태 변화로 확인했다.
