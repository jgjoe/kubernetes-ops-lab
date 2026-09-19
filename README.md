# kubernetes-ops-lab

kind 환경에서 최소 Spring Boot 서비스를 사용해 Kubernetes의 배포, 관찰, 장애, 업데이트, 복구 동작을 재현하는 운영 실습 프로젝트다.

구현 범위와 완료 기준의 정본은 [Kubernetes 운영 실습 — Mini PRD v1.0 FINAL](docs/mini-prd-v1.0.md)이다.

## 현재 상태 (Slice 1: 애플리케이션)

- Java 17 + Spring Boot 3.5.16 + Maven Wrapper. 전역 Maven 설치 없이 `.\mvnw.cmd`로 build한다.
- Kubernetes 동작 검증에 필요한 endpoint만 제공한다. 비즈니스 API와 Actuator는 없다.
- readiness/liveness는 프로세스 메모리에만 저장한다. 프로세스가 시작하면 둘 다 `true`이고, 애플리케이션은 스스로 종료하거나 재시작하지 않는다.
- Kubernetes manifest, kind cluster, container image, CI/CD는 아직 없다.

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

## Windows PowerShell 검증

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
