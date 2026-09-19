# Repository development rules

이 저장소의 구현 기준선은 `docs/mini-prd-v1.0.md`다. 작업 전 Git 상태와 해당 문서를 먼저 확인한다.

## 범위

- 현재 저장소는 bootstrap만 완료된 상태이며 Kubernetes 실습 구현은 아직 시작하지 않았다.
- PRD의 Non-goals를 지키고, 검증 시나리오에 필요하지 않은 dependency나 infrastructure를 추가하지 않는다.
- generic bootstrap과 PRD 범위에 따라 CI workflow가 없는 상태가 정상이다. 사용자가 범위를 변경하기 전에는 CI/CD를 추가하거나 제안하지 않는다.
- 범위를 변경해야 한다면 구현보다 먼저 PRD와 사용자 결정을 갱신한다.

## Git 작업 방식

- 안정 브랜치 `main`에서 직접 기능을 구현하지 않는다.
- 작업은 목적에 맞는 `feature/*`, `fix/*`, `chore/*`, `docs/*`, `refactor/*`, `test/*`, `ci/*` 브랜치에서 수행한다.
- 공식 bootstrap은 최초 커밋을 만들지 않는다. 아직 커밋이 없다면 bootstrap 파일만 검토해 `main`의 최초 기준선 커밋으로 만든 뒤 구현을 시작한다.
- 최초 기준선 커밋 이후 작업 브랜치가 없다면 `..\_project-tooling\scripts\Start-Task.ps1`을 사용한다.
- 기존 사용자 변경 사항을 임의로 덮어쓰거나 폐기하지 않는다.

## 검증과 완료

- README, 패키지 스크립트, CI 설정에서 정의된 검증 명령을 확인하고 변경 범위에 맞게 실행한다.
- 실패 또는 미실행 검증을 보고한다.
- 사용자 승인 없이 안정 브랜치 병합, 원격 push, PR 병합, 버전 태그 push, 실제 배포를 하지 않는다.

공통 상세 원칙: `..\_project-tooling\standards\git-workflow.md`
