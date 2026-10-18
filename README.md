# Winter Samples

Winter를 처음 사용하는 사람이 샘플을 실행하고, 실제 코드에서 기본 사용법을 찾아볼 수 있도록 만든 게시판 예제입니다. HTTP 요청 처리, 입력값 검증, MyBatis 연동과 트랜잭션 사용 흐름을 살펴볼 수 있습니다.

## 실행

### 필수 환경

- JDK 8 이상
- Maven
- MySQL

의존성과 버전은 [pom.xml](pom.xml)에서 확인하세요. Winter 의존성이 로컬 Maven 저장소에 없다면, 지정된 버전의 릴리스 소스를 별도 디렉터리에 받아 다음 순서로 설치하세요.

1. [winter-framework](https://github.com/WinterFramework-org/winter-framework) 디렉터리에서 `mvn install`
2. [mybatis-winter](https://github.com/WinterFramework-org/mybatis-winter) 디렉터리에서 `mvn install`

### 데이터베이스 설정

MySQL에 `winter_samples` 데이터베이스를 만들고 [schema.sql](src/main/resources/sql/schema.sql)을 적용하세요.

`src/main/resources/application.properties` 파일을 생성하고 접속 정보를 입력하세요.

```properties
winter.datasource.url=jdbc:mysql://localhost:3306/winter_samples
winter.datasource.username=<DB 사용자>
winter.datasource.password=<DB 비밀번호>
server.port=8080
```

프로젝트 루트를 작업 디렉터리로 지정하고 `board.MainApplication`의 `main()`을 실행하세요.

## 사용

다음 요청으로 게시글을 생성하세요. 포트를 변경했다면 요청 주소도 맞춰주세요.

```bash
curl -i -H 'Accept: application/json' -H 'Content-Type: application/json' \
  -d '{"content":"게시글 본문"}' http://localhost:8080/board
```

정상 응답은 `201 Created`이며, `Location` 헤더에 생성된 게시글 경로를 반환합니다. 다음은 생성된 ID가 `1`인 경우의 예시입니다.

```http
HTTP/1.1 201 Created
Location: /board/1
Content-Type: application/json

{"id":1,"content":"게시글 본문"}
```

## 코드 안내

시작점, 요청 처리, 서비스, DB 매핑 순서로 코드를 살펴볼 수 있습니다.

- [MainApplication.java](src/main/java/board/MainApplication.java): `WinterApplication.run()`으로 애플리케이션을 시작합니다.
- [BoardController.java](src/main/java/board/controller/BoardController.java): 요청 매핑, 생성자 주입과 `@Valid @RequestBody`를 통한 JSON 입력 검증을 사용합니다.
- [BoardRequest.java](src/main/java/board/dto/BoardRequest.java): `@NotBlank`로 게시글 본문의 필수 입력과 공백 검증 조건을 선언합니다.
- [BoardServiceImpl.java](src/main/java/board/service/BoardServiceImpl.java): 게시글 처리 로직을 구현하며, `@Transactional`로 본문 수정과 이력 저장을 하나의 트랜잭션으로 처리합니다.
- [BoardMapper.java](src/main/java/board/repository/BoardMapper.java): `@Mapper` 인터페이스로 DB 작업을 선언합니다.
- [BoardMapper.xml](src/main/resources/mappers/BoardMapper.xml): Mapper 메서드에 대응하는 SQL을 정의합니다.
- [mybatis-config.xml](src/main/resources/mybatis-config.xml): Mapper XML을 등록합니다. 실행 시 mybatis-winter가 Winter의 `DataSource`와 연동합니다.
