# Winter 게시판 예제

winter-framework와 mybatis-winter를 사용하는 Java 8·MySQL 게시글 CRUD 예제입니다.
의존성과 버전은 [pom.xml](pom.xml), 최신화 작업은 [GitHub 이슈](https://github.com/WinterFramework-org/winter-samples/issues/1)에서 관리합니다.

## 실행

JDK 8, Maven, MySQL이 필요합니다. Winter 의존성이 로컬 Maven 저장소에 없다면
지정한 릴리스 소스를 별도 디렉터리에서 받아 `mvn install`로 설치합니다.
winter-framework를 먼저 설치한 뒤 mybatis-winter를 설치합니다.

사용할 DB를 생성하고 [schema.sql](src/main/resources/sql/schema.sql)을 적용합니다.
`src/main/resources/application.properties`를 만들고 접속 정보를 입력합니다.

```properties
winter.datasource.url=jdbc:mysql://localhost:3306/winter_samples
winter.datasource.username=<DB 사용자>
winter.datasource.password=<DB 비밀번호>
server.port=8080
```

IntelliJ에서 `board.MainApplication`의 `main()`을 실행합니다.
작업 디렉터리는 프로젝트 루트로 지정합니다.
개인 설정은 Git에서 제외하지만 Maven 빌드 시 JAR에는 포함됩니다.

`GET /board`로 목록을, `GET /board/{id}`로 게시글을 조회합니다.

## 검증

```text
mvn clean verify
```

HTTP·MySQL 통합 검증은 별도 테스트 DB와 `config/mysql-test.properties`를 사용합니다.
위 설정의 DB 접속 키 세 개를 입력하고 테스트 DB 이름을 `winter_samples_test` 또는
`winter_samples_test_` 뒤에 영문·숫자·밑줄을 붙인 이름으로 지정합니다.
테스트 계정에는 테이블 생성과 게시글 조회·등록·삭제 권한이 필요합니다.

```text
mvn -Pmysql-it clean verify
```

결과는 `target/failsafe-reports`에서 확인합니다.
