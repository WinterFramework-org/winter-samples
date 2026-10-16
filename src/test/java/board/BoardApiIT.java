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
    public void findsStoredBoardThroughHttp() throws Exception {
        JsonNode board = getJson("/board/" + boardId);
        assertEquals(boardId, board.get("id").asInt());
        assertEquals(CONTENT, board.get("content").asText());
    }

    @Test
    public void listsStoredBoardThroughHttp() throws Exception {
        JsonNode boards = getJson("/board");
        assertTrue(boards.isArray());
        boolean found = false;
        for (JsonNode board : boards) {
            if (board.get("id").asInt() == boardId) {
                assertEquals(CONTENT, board.get("content").asText());
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
            HttpResult updated = request("PATCH", "/board/" + id, jsonContent(content));
            assertEquals(200, updated.status);
            assertEquals(id, updated.json().path("id").asInt());
            assertEquals(content, updated.json().path("content").asText());
            assertEquals(content, getJson("/board/" + id).path("content").asText());
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
            HttpResult deleted = request("DELETE", "/board/" + id, null);
            assertEquals(204, deleted.status);
            assertEquals("", deleted.body);
            assertError(request("GET", "/board/" + id, null), 404, "NOT_FOUND", "/board/" + id);
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
                    assertEquals("content", errors.get(0).path("field").asText());
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

    private static HttpResult request(String method, String path, String body) throws Exception {
        URL address = new URL(baseUrl);
        byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket(address.getHost(), address.getPort())) {
            socket.setSoTimeout(5000);
            String headers = method + " " + path + " HTTP/1.0\r\n"
                + "Host: localhost\r\nAccept: application/json\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n"
                + "Content-Length: " + payload.length + "\r\nConnection: close\r\n\r\n";
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
