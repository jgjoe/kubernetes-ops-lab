# kubernetes-ops-lab

kind 환경에서 최소 Spring Boot 서비스를 사용해 Kubernetes의 배포, 관찰, 장애, 업데이트, 복구 동작을 재현하는 운영 실습 프로젝트다.

구현 범위와 완료 기준의 정본은 [Kubernetes 운영 실습 — Mini PRD v1.0 FINAL](docs/mini-prd-v1.0.md)이다.

## 현재 상태 (Slice 1: 애플리케이션)

- Java 17 + Spring Boot 3.5.16 + Maven Wrapper. 전역 Maven 설치 없이 `.\mvnw.cmd`로 build한다.
- Kubernetes 동작 검증에 필요한 endpoint만 제공한다. 비즈니스 API와 Actuator는 없다.
- readiness/liveness는 프로세스 메모리에만 저장한다. 프로세스가 시작하면 둘 다 `true`이고, 애플리케이션은 스스로 종료하거나 재시작하지 않는다.

## 현재 상태 (Slice 2: 컨테이너 이미지)

- 루트 `Dockerfile`은 Java 17 multi-stage build다. build stage는 전역 Maven 없이 저장소 Maven Wrapper로 `package`만 실행하고, runtime stage는 Java 17 JRE와 실행할 `app.jar`만 담는다.
- 애플리케이션 port는 8080이며 이미지에 `HEALTHCHECK`는 없다. probe 의미는 Slice 1과 같다.
- `.dockerignore`가 `target`, `.git`, IDE 파일, 문서를 build context에서 제외한다.
- Kubernetes manifest, kind cluster, CI/CD는 아직 없다.

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

host port가 비어 있는지 확인한 뒤 port-forward를 실행한다.

```powershell
Get-NetTCPConnection -LocalPort 18080 -State Listen -ErrorAction SilentlyContinue
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

이 상태에서 Service를 port-forward하면 정상 replica가 계속 요청을 처리한다.

```powershell
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
