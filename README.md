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
