## Agent skills

### Issue tracker
명세와 작업 티켓은 GitHub Issues로 관리한다.
이슈를 읽거나 작성할 때 `docs/agents/issue-tracker.md`를 읽는다.

### Triage labels
작업 상태를 분류할 때 `docs/agents/triage-labels.md`의 매핑을 사용한다.

### Domain docs
코드를 탐색하거나 설계할 때 `docs/agents/domain.md`를 읽는다.
용어집과 ADR은 single-context 구조로 관리한다.

## 검증 보고와 DB 진단

- 검증 결과는 실행 명령, 검증 범위, 성공·실패, 실제 실행한 테스트 수를 명시한다. 빌드·수동 테스트·자동 통합 테스트 결과를 구분하고, 미실행 범위도 밝힌다.
- DB 권한 명령을 안내하기 전에 실제 접속 대상과 해당 연결의 `CURRENT_USER()`를 확인한다. 비밀번호는 출력하지 않고, WSL 검증용 주소는 임시 설정에만 적용한다.
