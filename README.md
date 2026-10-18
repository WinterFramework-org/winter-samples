# Winter 게시판 예제

winter-framework와 mybatis-winter를 사용하는 Java 8·MySQL 게시글 CRUD 예제입니다.
JSON 요청 검증, HTTP 응답, 트랜잭션을 사용하는 변경 이력 저장을 보여줍니다.
의존성과 버전은 [pom.xml](pom.xml)에서 확인합니다.

## 실행

JDK 8, Maven, MySQL이 필요합니다. Winter 의존성이 로컬 Maven 저장소에 없다면
`pom.xml`에 지정된 버전의 릴리스 소스를 별도 디렉터리에서 받아
[winter-framework](https://github.com/WinterFramework-org/winter-framework),
[mybatis-winter](https://github.com/WinterFramework-org/mybatis-winter) 순서로 `mvn install`합니다.

사용할 DB를 생성하고 [schema.sql](src/main/resources/sql/schema.sql)을 적용합니다.
`src/main/resources/application.properties`를 생성하고 다음 값을 설정합니다.

```properties
winter.datasource.url=jdbc:mysql://localhost:3306/winter_samples
winter.datasource.username=<DB 사용자>
winter.datasource.password=<DB 비밀번호>
server.port=8080
```

`board.MainApplication`의 `main()`을 실행합니다. 작업 디렉터리는 프로젝트 루트입니다.
개인 설정은 Git에서 제외하지만 Maven 빌드 JAR에는 포함됩니다.

## 사용 예제

등록·수정은 `content`를 가진 JSON 본문을 받습니다. 포트는 실행 설정에 맞춥니다.

```bash
curl -i -H 'Accept: application/json' -H 'Content-Type: application/json' \
  -d '{"content":"게시글 본문"}' http://localhost:8080/board
```

등록 응답은 `201`, `Location: /board/{id}`와 `id`·`content` JSON입니다.
`GET /board`는 목록, `GET /board/{id}`는 개별 게시글을 조회합니다.
같은 개별 경로에서 `PATCH`는 수정(`200`), `DELETE`는 삭제(`204`, 본문 없음)합니다.

```bash
curl -i -X PATCH -H 'Accept: application/json' -H 'Content-Type: application/json' \
  -d '{"content":"수정한 본문"}' http://localhost:8080/board/1
```

예제의 ID는 실제 등록·조회 값으로 바꿉니다.
`GET /board/{id}/history`는 `id`·`previousContent`·`newContent`·`changedAt`을 최신순 배열로 반환합니다.
`changedAt`은 시간대 오프셋 없는 ISO 8601 형식입니다. 이력이 없으면 `[]`, 게시글이 없으면 `404`입니다.
같은 본문으로 수정하면 이력을 추가하지 않으며, 게시글 삭제 시 해당 이력도 삭제합니다.

Service의 `@Transactional`로 본문 수정과 이력 저장을 함께 커밋합니다.
수정할 게시글을 잠금 조회해 동시 수정도 순서대로 처리하고 실제 직전 본문을 이력에 기록합니다.
이력 저장 실패 시 본문 변경도 롤백하고 `500`을 반환합니다.

본문을 생략하거나 JSON `null`을 보내면 `400`입니다. `content`를 공백으로 바꿔도 `400`입니다.
존재하지 않는 게시글 조회는 `404`를 반환합니다.
저장 실패 시 `500` 오류 응답을 반환합니다.

## 검증

컴파일·패키징은 `mvn clean verify`로 확인합니다.
실제 CRUD·입력·오류 응답 검증은 별도 MySQL 테스트 DB를 사용합니다.
별도 테스트 DB `winter_samples_test`를 생성합니다.
`config/mysql-test.properties`를 생성하고, 실행 설정과 같은 `winter.datasource.url`,
`winter.datasource.username`, `winter.datasource.password`에 테스트 DB 접속 정보를 설정합니다.
테스트 계정에는 `CREATE`, `SELECT`, `INSERT`, `UPDATE`, `DELETE`, `ALTER`, `REFERENCES` 권한이 필요합니다.
테스트는 테이블 SQL을 자동 적용합니다. 롤백 검증은 MySQL 8.0.16 이상의 `CHECK` 제약을 사용해
테스트 게시글의 이력 저장만 실패시키고, 검증 후 제약을 제거합니다.
동시 수정·롤백 재현은 아래 통합 테스트에 포함되며, 일반 API에는 실패 유도 옵션을 두지 않습니다.

```text
mvn -Pmysql-it clean verify
```

결과는 `target/failsafe-reports`, 서버 로그는 `target/mysql-it.log`에서 확인합니다.
