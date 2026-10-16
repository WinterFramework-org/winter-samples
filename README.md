# Winter 게시판 예제

winter-framework와 mybatis-winter를 사용하는 Java 8·MySQL 게시글 CRUD 예제입니다.
JSON 요청 검증과 HTTP 성공·오류 응답을 보여줍니다.
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

본문을 생략하거나 JSON `null`을 보내면 `400`입니다. `content`를 공백으로 바꿔도 `400`입니다.
존재하지 않는 게시글 조회는 `404`를 반환합니다.
저장 실패 시 `500` 오류 응답을 반환합니다.

## 검증

컴파일·패키징은 `mvn clean verify`로 확인합니다.
실제 CRUD·입력·오류 응답 검증은 별도 MySQL 테스트 DB를 사용합니다.
별도 테스트 DB `winter_samples_test`를 생성합니다.
`config/mysql-test.properties`를 생성하고, 실행 설정과 같은 `winter.datasource.url`,
`winter.datasource.username`, `winter.datasource.password`에 테스트 DB 접속 정보를 설정합니다.
테스트 계정에는 테이블 생성과 조회·등록·수정·삭제 권한이 필요합니다.

```text
mvn -Pmysql-it clean verify
```

결과는 `target/failsafe-reports`, 서버 로그는 `target/mysql-it.log`에서 확인합니다.
