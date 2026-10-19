package board;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class BoardApiIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CONTENT = "조회 검증 " + UUID.randomUUID();
    private static Connection database;
    private static Process application;
    private static Path configurationDirectory;
    private static String baseUrl;
    private static int boardId;

    @BeforeClass
    public static void startApplicationWithTestDatabase() throws Exception {
        try {
            Properties properties = loadTestProperties();
            String jdbcUrl = properties.getProperty("winter.datasource.url", "");
            assertTrue("별도 winter_samples_test DB를 지정하세요.", jdbcUrl.matches(
                "jdbc:mysql://[^/]+/winter_samples_test(?:_[A-Za-z0-9_]+)?(?:\\?.*)?"));
            try {
                database = DriverManager.getConnection(jdbcUrl,
                    properties.getProperty("winter.datasource.username"),
                    properties.getProperty("winter.datasource.password"));
            } catch (SQLException exception) {
                fail("테스트 DB에 연결할 수 없습니다. 설정과 서버를 확인하세요. SQLState="
                    + exception.getSQLState());
            }
            try (InputStream schema = BoardApiIT.class.getResourceAsStream("/sql/schema.sql")) {
                assertNotNull(schema);
                try (Statement statement = database.createStatement()) {
                    for (String sql : new String(readAll(schema), "UTF-8").split(";")) {
                        if (!sql.trim().isEmpty()) {
                            statement.execute(sql);
                        }
                    }
                }
            }
            try (PreparedStatement insert = database.prepareStatement(
                "INSERT INTO board (content) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, CONTENT);
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    assertTrue(keys.next());
                    boardId = keys.getInt(1);
                }
            }

            int port;
            try (ServerSocket socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            }
            baseUrl = "http://127.0.0.1:" + port;
            properties.setProperty("server.port", Integer.toString(port));
            configurationDirectory = Files.createTempDirectory("winter-samples-it-");
            try (OutputStream output = Files.newOutputStream(
                configurationDirectory.resolve("application.properties"))) {
                properties.store(output, "Integration test");
            }
            // 테스트 설정을 먼저 읽게 하여 개인 실행용 DB를 사용하지 않는다.
            String classpath = configurationDirectory + File.pathSeparator
                + System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            application = new ProcessBuilder(
                Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, "board.MainApplication")
                .redirectErrorStream(true)
                .redirectOutput(new File("target/mysql-it.log"))
                .start();
            waitForApplication();
        } catch (Exception | AssertionError failure) {
            stopApplicationAndRemoveTestData();
            throw failure;
        }
    }

    @Test
    public void providesContentEtagOnCreateAndSingleRead() throws Exception {
        HttpResult created = request("POST", "/board", jsonContent("abc"));
        int id = created.json().path("id").asInt();
        try {
            assertEquals(201, created.status);
            String expected = "\"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad\"";
            assertEquals(expected, created.headers.get("etag"));
            assertEquals("/board/" + id, created.headers.get("location"));
            HttpResult found = request("GET", "/board/" + id, null);
            assertEquals(200, found.status);
            assertEquals(expected, found.headers.get("etag"));
            assertEquals(2, found.json().size());
            assertEquals("abc", found.json().path("content").asText());
        } finally {
            removeTestBoard(id);
        }
    }

    @Test
    public void requiresSingleMatchingIfMatchBeforeUpdating() throws Exception {
        HttpResult created = request("POST", "/board", jsonContent("조건 검사 전"));
        int id = created.json().path("id").asInt();
        String path = "/board/" + id;
        String tag = created.headers.get("etag");
        try {
            HttpResult missing = request("PATCH", path, jsonContent("수정"));
            assertError(missing, 428, "IF_MATCH_REQUIRED", path);
            assertEquals("no-store", missing.headers.get("cache-control"));
            for (String value : new String[]{"", " ", "*", tag + ", " + tag,
                "unquoted", "\"unterminated", tag + " trailing", "w/" + tag}) {
                assertError(request("PATCH", path, jsonContent("수정"), value),
                    400, "INVALID_IF_MATCH", path);
            }
            assertError(request("PATCH", path, jsonContent("수정"), tag, tag),
                400, "INVALID_IF_MATCH", path);
            for (String value : new String[]{"\"other\"", "W/" + tag, "\"\"", "\"tag,with,commas\""}) {
                assertError(request("PATCH", path, jsonContent("수정"), value),
                    412, "IF_MATCH_FAILED", path);
            }
            assertEquals("조건 검사 전", getJson(path).path("content").asText());
            assertEquals(0, getJson(path + "/history").size());
            HttpResult updated = request("PATCH", path, jsonContent("새 본문"), " \t" + tag + " \t");
            assertEquals(200, updated.status);
            assertTrue(!tag.equals(updated.headers.get("etag")));
            assertError(request("PATCH", path, jsonContent("새 본문"), tag),
                412, "IF_MATCH_FAILED", path);
            assertEquals("새 본문", getJson(path).path("content").asText());
            assertEquals(1, getJson(path + "/history").size());
        } finally {
            removeTestBoard(id);
        }
    }

    @Test
    public void preservesEtagForIdenticalAndRestoredUnicodeContent() throws Exception {
        String original = "  한글😀\n끝  ";
        String expected = "\"6579b3a866208e9c6877a1eb64a767a24c6d5e876caeca867037ac608d4f89cd\"";
        HttpResult created = request("POST", "/board", jsonContent(original));
        int id = created.json().path("id").asInt();
        String path = "/board/" + id;
        try {
            assertEquals(201, created.status);
            assertEquals(expected, created.headers.get("etag"));
            HttpResult unchanged = request("PATCH", path, jsonContent(original), expected);
            assertEquals(200, unchanged.status);
            assertEquals(expected, unchanged.headers.get("etag"));
            assertEquals(0, getJson(path + "/history").size());
            HttpResult changed = request("PATCH", path, jsonContent("다른 본문"), expected);
            assertEquals(200, changed.status);
            assertTrue(!expected.equals(changed.headers.get("etag")));
            HttpResult restored = request("PATCH", path, jsonContent(original),
                changed.headers.get("etag"));
            assertEquals(200, restored.status);
            assertEquals(expected, restored.headers.get("etag"));
            assertEquals(original, restored.json().path("content").asText());
            assertEquals(200, request("PATCH", path, jsonContent("복원 후 수정"), expected).status);
            assertEquals(3, getJson(path + "/history").size());
            assertTrue(!request("GET", "/board", null).headers.containsKey("etag"));
            assertTrue(!request("GET", path + "/history", null).headers.containsKey("etag"));
        } finally {
            removeTestBoard(id);
        }
    }

    @Test
    public void findsStoredBoardThroughHttp() throws Exception {
        JsonNode board = getJson("/board/" + boardId);
        assertEquals(boardId, board.get("id").asInt());
        assertEquals(CONTENT, board.get("content").asText());
        assertEquals(2, board.size());
    }

    @Test
    public void listsStoredBoardThroughHttp() throws Exception {
        JsonNode boards = getJson("/board");
        assertTrue(boards.isArray());
        boolean found = false;
        for (JsonNode board : boards) {
            if (board.get("id").asInt() == boardId) {
                assertEquals(CONTENT, board.get("content").asText());
                assertEquals(2, board.size());
                found = true;
            }
        }
        assertTrue("DB에 저장한 게시글이 HTTP 목록에 있어야 합니다.", found);
    }

    @Test
    public void createsJsonBoardWithLocationAndCanRetrieveIt() throws Exception {
        String content = "  JSON 등록\n본문 보존  " + UUID.randomUUID();
        HttpResult created = request("POST", "/board", jsonContent(content));
        int id = created.json().path("id").asInt();
        try {
            assertEquals(201, created.status);
            assertTrue(id > 0);
            assertEquals(content, created.json().path("content").asText());
            assertEquals("/board/" + id, created.headers.get("location"));
            assertEquals(content, getJson("/board/" + id).path("content").asText());
        } finally {
            removeTestBoard(id);
        }
    }

    @Test
    public void updatesJsonBoardAndPreservesContent() throws Exception {
        HttpResult created = request("POST", "/board", jsonContent("수정 전 " + UUID.randomUUID()));
        assertEquals(201, created.status);
        int id = created.json().path("id").asInt();
        String content = "  수정\n본문\t보존  ";
        try {
            HttpResult updated = request("PATCH", "/board/" + id, jsonContent(content),
                created.headers.get("etag"));
            assertEquals(200, updated.status);
            assertEquals(id, updated.json().path("id").asInt());
            assertEquals(content, updated.json().path("content").asText());
            assertEquals(content, getJson("/board/" + id).path("content").asText());
            JsonNode history = getJson("/board/" + id + "/history");
            assertEquals(1, history.size());
            assertEquals(created.json().path("content").asText(),
                history.get(0).path("previousContent").asText());
            assertEquals(content, history.get(0).path("newContent").asText());
            assertEquals(4, history.get(0).size());
            assertTrue(history.get(0).path("id").asLong() > 0);
            java.time.LocalDateTime.parse(history.get(0).path("changedAt").asText());
        } finally {
            removeTestBoard(id);
        }
    }

    @Test
    public void deletesBoardWithoutResponseBodyAndRemovesItFromList() throws Exception {
        HttpResult created = request("POST", "/board", jsonContent("삭제 검증 " + UUID.randomUUID()));
        assertEquals(201, created.status);
        int id = created.json().path("id").asInt();
        try {
            assertEquals(200, request("PATCH", "/board/" + id,
                jsonContent("삭제 전 이력"), created.headers.get("etag")).status);
            assertEquals(1, getJson("/board/" + id + "/history").size());
            HttpResult deleted = request("DELETE", "/board/" + id, null);
            assertEquals(204, deleted.status);
            assertEquals("", deleted.body);
            assertError(request("GET", "/board/" + id, null), 404, "NOT_FOUND", "/board/" + id);
            assertError(request("GET", "/board/" + id + "/history", null),
                404, "NOT_FOUND", "/board/" + id + "/history");
            try (PreparedStatement query = database.prepareStatement(
                "SELECT COUNT(*) FROM board_history WHERE board_id = ?")) {
                query.setInt(1, id);
                try (ResultSet rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals("게시글 삭제 시 이력도 삭제되어야 합니다.", 0, rows.getInt(1));
                }
            }
            for (JsonNode board : getJson("/board")) {
                assertTrue("삭제한 게시글이 목록에 남으면 안 됩니다.", board.path("id").asInt() != id);
            }
        } finally {
            removeTestBoard(id);
        }
    }

    @Test
    public void rejectsInvalidJsonAndBlankContentOnCreateAndUpdate() throws Exception {
        int count = getJson("/board").size();
        String[] invalidBodies = {null, "", " \t\r\n ", "null", " \t null \r\n ",
            "\"null\"", "None", "\"None\"", "{}", "{\"content\":null}",
            jsonContent(""), jsonContent(" \t\r\n "), "{broken"};
        for (String method : new String[]{"POST", "PATCH"}) {
            String path = method.equals("POST") ? "/board" : "/board/" + boardId;
            for (String body : invalidBodies) {
                HttpResult result = request(method, path, body);
                assertError(result, 400, "BAD_REQUEST", path);
                if (body != null && (body.equals("{}") || body.startsWith("{\"content\":"))) {
                    JsonNode errors = result.json().path("errors");
                    assertTrue("DTO 검증 오류 정보가 있어야 합니다.", errors.isArray());
                    boolean contentError = false;
                    for (JsonNode error : errors) {
                        contentError |= "content".equals(error.path("field").asText());
                    }
                    assertTrue("content 필드의 검증 오류가 있어야 합니다.", contentError);
                }
            }
        }
        assertEquals(count, getJson("/board").size());
        assertEquals(CONTENT, getJson("/board/" + boardId).path("content").asText());
    }

    @Test
    public void returnsNotFoundForMissingBoardOnReadUpdateAndDelete() throws Exception {
        for (String method : new String[]{"GET", "PATCH", "DELETE"}) {
            String body = method.equals("PATCH") ? jsonContent("없는 게시글 수정") : null;
            assertError(request(method, "/board/0", body), 404, "NOT_FOUND", "/board/0");
        }
    }

    @Test
    public void explainsActualStorageFailureWithoutExposingDatabaseDetails() throws Exception {
        String content = new String(new char[70000]).replace('\0', 'x');
        int count = getJson("/board").size();
        HttpResult result = request("POST", "/board", jsonContent(content));
        assertError(result, 500, "BOARD_STORAGE_ERROR", "/board");
        assertEquals("게시글을 저장하지 못했습니다. 잠시 후 다시 시도해주세요.",
            result.json().path("message").asText());
        assertTrue(!result.body.contains("jdbc:mysql"));
        assertTrue(!result.body.contains("INSERT INTO"));
        assertEquals(count, getJson("/board").size());
    }

    @Test
    public void returnsEmptyHistoryForExistingBoardAndNotFoundForMissingBoard() throws Exception {
        JsonNode history = getJson("/board/" + boardId + "/history");
        assertTrue(history.isArray());
        assertEquals(0, history.size());
        assertError(request("GET", "/board/0/history", null), 404, "NOT_FOUND", "/board/0/history");
    }

    @Test
    public void doesNotCreateHistoryForIdenticalContent() throws Exception {
        String original = "  같은 본문\n공백 보존  ";
        HttpResult created = request("POST", "/board", jsonContent(original));
        assertEquals(201, created.status);
        int id = created.json().path("id").asInt();
        String path = "/board/" + id;
        try {
            HttpResult unchanged = request("PATCH", path, jsonContent(original),
                created.headers.get("etag"));
            assertEquals(200, unchanged.status);
            assertEquals(original, unchanged.json().path("content").asText());
            assertEquals(created.headers.get("etag"), unchanged.headers.get("etag"));
            assertEquals(0, getJson(path + "/history").size());
            assertEquals(200, request("PATCH", path, jsonContent(original + " "),
                created.headers.get("etag")).status);
            assertEquals(original + " ", getJson(path).path("content").asText());
            assertEquals(1, getJson(path + "/history").size());
        } finally {
            removeTestBoard(id);
        }
    }

    @Test
    public void listsHistoryNewestFirstIncludingEqualTimestamps() throws Exception {
        HttpResult created = request("POST", "/board", jsonContent("처음"));
        assertEquals(201, created.status);
        int id = created.json().path("id").asInt();
        String path = "/board/" + id;
        try {
            assertEquals(200, patchWithCurrentEtag(path, jsonContent("두 번째")).status);
            assertEquals(200, patchWithCurrentEtag(path, jsonContent("세 번째")).status);
            JsonNode history = getJson(path + "/history");
            assertEquals(2, history.size());
            assertEquals("두 번째", history.get(0).path("previousContent").asText());
            assertEquals("세 번째", history.get(0).path("newContent").asText());
            assertEquals("처음", history.get(1).path("previousContent").asText());
            try (PreparedStatement timestamps = database.prepareStatement(
                "UPDATE board_history SET changed_at = '2026-01-01 00:00:00' WHERE board_id = ?")) {
                timestamps.setInt(1, id);
                assertEquals(2, timestamps.executeUpdate());
            }
            JsonNode tied = getJson(path + "/history");
            assertTrue(tied.get(0).path("id").asLong() > tied.get(1).path("id").asLong());
            assertEquals("세 번째", tied.get(0).path("newContent").asText());
        } finally {
            removeTestBoard(id);
        }
    }

    @Test
    public void rollsBackContentWhenHistoryInsertFails() throws Exception {
        String original = UUID.randomUUID().toString();
        HttpResult created = request("POST", "/board", jsonContent(original));
        assertEquals(201, created.status);
        int id = created.json().path("id").asInt();
        String path = "/board/" + id;
        String constraint = "it_history_failure_" + UUID.randomUUID().toString().replace("-", "");
        boolean constraintAdded = false;
        try {
            // 이 테스트의 고유한 이전 본문만 거부해 이력 INSERT를 실패시킨다.
            try (Statement statement = database.createStatement()) {
                statement.execute("ALTER TABLE board_history ADD CONSTRAINT " + constraint
                    + " CHECK (previous_content <> '" + original + "')");
                constraintAdded = true;
            }
            HttpResult failed = request("PATCH", path, jsonContent("롤백될 본문"),
                created.headers.get("etag"));
            assertError(failed, 500, "BOARD_STORAGE_ERROR", path);
            assertEquals("게시글을 저장하지 못했습니다. 잠시 후 다시 시도해주세요.",
                failed.json().path("message").asText());
            assertTrue(!failed.body.contains(constraint));
            assertTrue(!failed.body.contains("INSERT INTO"));
            assertEquals(original, getJson(path).path("content").asText());
            assertEquals(0, getJson(path + "/history").size());
            try (Statement statement = database.createStatement()) {
                statement.execute("ALTER TABLE board_history DROP CHECK " + constraint);
                constraintAdded = false;
            }
            assertEquals(200, request("PATCH", path, jsonContent("다시 수정"),
                created.headers.get("etag")).status);
            assertEquals(1, getJson(path + "/history").size());
        } finally {
            try {
                if (constraintAdded) {
                    try (Statement statement = database.createStatement()) {
                        statement.execute("ALTER TABLE board_history DROP CHECK " + constraint);
                    }
                }
            } finally {
                removeTestBoard(id);
            }
        }
    }

    @Test
    public void acceptsOnlyOneConcurrentUpdateWithTheSameEtag() throws Exception {
        String original = "동시 수정 전";
        HttpResult created = request("POST", "/board", jsonContent(original));
        assertEquals(201, created.status);
        int id = created.json().path("id").asInt();
        String path = "/board/" + id;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        try {
            database.setAutoCommit(false);
            try (PreparedStatement lock = database.prepareStatement(
                "SELECT id FROM board WHERE id = ? FOR UPDATE")) {
                lock.setInt(1, id);
                try (ResultSet row = lock.executeQuery()) {
                    assertTrue(row.next());
                }
            }
            Future<HttpResult> first = executor.submit(() -> {
                ready.countDown();
                return request("PATCH", path, jsonContent("첫 번째 요청"), created.headers.get("etag"));
            });
            Future<HttpResult> second = executor.submit(() -> {
                ready.countDown();
                return request("PATCH", path, jsonContent("두 번째 요청"), created.headers.get("etag"));
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            for (Future<HttpResult> pending : java.util.Arrays.asList(first, second)) {
                try {
                    pending.get(250, TimeUnit.MILLISECONDS);
                    fail("행 잠금이 해제될 때까지 수정 요청이 대기해야 합니다.");
                } catch (java.util.concurrent.TimeoutException expected) {
                    // 별도 DB 연결의 잠금을 유지한 동안 HTTP 수정 완료를 막는다.
                }
            }
            assertEquals(original, getJson(path).path("content").asText());
            database.commit();
            database.setAutoCommit(true);
            HttpResult firstResult = first.get(10, TimeUnit.SECONDS);
            HttpResult secondResult = second.get(10, TimeUnit.SECONDS);
            assertTrue((firstResult.status == 200 && secondResult.status == 412)
                || (firstResult.status == 412 && secondResult.status == 200));
            HttpResult success = firstResult.status == 200 ? firstResult : secondResult;
            assertError(firstResult.status == 412 ? firstResult : secondResult,
                412, "IF_MATCH_FAILED", path);
            JsonNode history = getJson(path + "/history");
            assertEquals(1, history.size());
            assertEquals(original, history.get(0).path("previousContent").asText());
            assertEquals(success.json().path("content").asText(),
                history.get(0).path("newContent").asText());
            HttpResult found = request("GET", path, null);
            assertEquals(success.json().path("content").asText(), found.json().path("content").asText());
            assertEquals(success.headers.get("etag"), found.headers.get("etag"));
        } finally {
            try {
                if (!database.getAutoCommit()) {
                    database.rollback();
                }
            } finally {
                database.setAutoCommit(true);
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
                removeTestBoard(id);
            }
        }
    }

    private static void assertError(HttpResult result, int status, String code, String path)
        throws Exception {
        assertEquals(status, result.status);
        assertTrue(result.headers.get("content-type").startsWith("application/json"));
        JsonNode body = result.json();
        assertEquals(status, body.path("status").asInt());
        assertEquals(code, body.path("code").asText());
        assertTrue(!body.path("message").asText().isEmpty());
        assertEquals(path, body.path("path").asText());
    }

    @AfterClass
    public static void stopApplicationAndRemoveTestData() throws Exception {
        try {
            if (application != null) {
                application.destroy();
                if (!application.waitFor(5, TimeUnit.SECONDS)) {
                    application.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                }
            }
            if (database != null && boardId != 0) {
                try (PreparedStatement delete = database.prepareStatement(
                    "DELETE FROM board WHERE id = ?")) {
                    delete.setInt(1, boardId);
                    delete.executeUpdate();
                }
            }
        } finally {
            if (database != null) {
                database.close();
                database = null;
            }
            if (configurationDirectory != null) {
                Files.deleteIfExists(configurationDirectory.resolve("application.properties"));
                Files.deleteIfExists(configurationDirectory);
            }
        }
    }

    private static Properties loadTestProperties() throws Exception {
        String configuredPath = System.getenv("WINTER_TEST_CONFIG");
        Path path = Paths.get(configuredPath == null ? "config/mysql-test.properties" : configuredPath);
        assertTrue("config/mysql-test.properties에 별도 테스트 DB 접속 정보를 설정하세요.",
            Files.isRegularFile(path));
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static void waitForApplication() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            assertTrue("애플리케이션이 종료되었습니다. target/mysql-it.log를 확인하세요.",
                application.isAlive());
            try {
                getJson("/board/" + boardId);
                return;
            } catch (java.io.IOException ignored) {
                Thread.sleep(100);
            }
        }
        fail("애플리케이션 기동 시간 초과: target/mysql-it.log를 확인하세요.");
    }

    private static JsonNode getJson(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setConnectTimeout(1000);
        connection.setReadTimeout(3000);
        connection.setRequestProperty("Accept", "application/json");
        try {
            assertEquals(200, connection.getResponseCode());
            try (InputStream input = connection.getInputStream()) {
                return JSON.readTree(input);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String jsonContent(String content) throws Exception {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("content", content);
        return JSON.writeValueAsString(body);
    }

    private static void removeTestBoard(int id) throws Exception {
        if (id > 0) {
            try (PreparedStatement delete = database.prepareStatement("DELETE FROM board WHERE id = ?")) {
                delete.setInt(1, id);
                delete.executeUpdate();
            }
        }
    }

    private static HttpResult patchWithCurrentEtag(String path, String body) throws Exception {
        HttpResult current = request("GET", path, null);
        assertEquals(200, current.status);
        assertNotNull(current.headers.get("etag"));
        return request("PATCH", path, body, current.headers.get("etag"));
    }

    private static HttpResult request(String method, String path, String body) throws Exception {
        return request(method, path, body, new String[0]);
    }

    private static HttpResult request(String method, String path, String body, String... ifMatch)
        throws Exception {
        URL address = new URL(baseUrl);
        byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket(address.getHost(), address.getPort())) {
            socket.setSoTimeout(5000);
            StringBuilder conditions = new StringBuilder();
            for (String value : ifMatch) {
                conditions.append("If-Match: ").append(value).append("\r\n");
            }
            String headers = method + " " + path + " HTTP/1.0\r\n"
                + "Host: localhost\r\nAccept: application/json\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n"
                + conditions + "Content-Length: " + payload.length + "\r\nConnection: close\r\n\r\n";
            OutputStream output = socket.getOutputStream();
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.write(payload);
            output.flush();
            String response = new String(readAll(socket.getInputStream()), StandardCharsets.UTF_8);
            int boundary = response.indexOf("\r\n\r\n");
            assertTrue("HTTP 응답 헤더가 있어야 합니다.", boundary >= 0);
            String[] lines = response.substring(0, boundary).split("\r\n");
            int status = Integer.parseInt(lines[0].split(" ")[1]);
            Map<String, String> responseHeaders = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    responseHeaders.put(lines[i].substring(0, colon).toLowerCase(java.util.Locale.ROOT),
                        lines[i].substring(colon + 1).trim());
                }
            }
            return new HttpResult(status, responseHeaders, response.substring(boundary + 4));
        }
    }

    private static class HttpResult {
        private final int status;
        private final Map<String, String> headers;
        private final String body;

        private HttpResult(int status, Map<String, String> headers, String body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        private JsonNode json() throws Exception {
            return JSON.readTree(body);
        }
    }

    private static byte[] readAll(InputStream input) throws java.io.IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
