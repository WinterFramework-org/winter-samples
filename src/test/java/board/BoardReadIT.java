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

public class BoardReadIT {

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
            try (InputStream schema = BoardReadIT.class.getResourceAsStream("/sql/schema.sql")) {
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
