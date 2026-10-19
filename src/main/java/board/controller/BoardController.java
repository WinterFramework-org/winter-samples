package board.controller;

import board.dto.BoardRequest;
import board.entitiy.Board;
import board.entitiy.BoardHistory;
import board.http.BoardConditionException;
import board.http.BoardEtag;
import board.service.BoardNotFoundException;
import board.service.BoardService;
import board.service.BoardStorageException;
import froggy.winterframework.beans.factory.annotation.Autowired;
import froggy.winterframework.http.ResponseEntity;
import froggy.winterframework.stereotype.Controller;
import froggy.winterframework.web.bind.annotation.ExceptionHandler;
import froggy.winterframework.web.bind.annotation.PathVariable;
import froggy.winterframework.web.bind.annotation.RequestBody;
import froggy.winterframework.web.bind.annotation.RequestHeader;
import froggy.winterframework.web.bind.annotation.RequestMapping;
import froggy.winterframework.web.bind.annotation.RequestMethod;
import froggy.winterframework.web.bind.annotation.ResponseBody;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@Controller
@RequestMapping("/board")
public class BoardController {

    private static final Logger LOGGER = Logger.getLogger(BoardController.class.getName());

    private final BoardService boardService;

    @Autowired
    public BoardController(BoardService boardService) {
        this.boardService = boardService;
    }

    @RequestMapping(method = {RequestMethod.POST})
    @ResponseBody
    public ResponseEntity<Board> addBoard(@Valid @RequestBody BoardRequest request) {
        Board board = boardService.create(request.getContent());
        return ResponseEntity.created(URI.create("/board/" + board.getId()))
            .header("ETag", BoardEtag.of(board.getContent())).body(board);
    }

    @RequestMapping(value = "/{id}", method = {RequestMethod.GET})
    @ResponseBody
    public ResponseEntity<Board> findBoard(@PathVariable("id") int id) {
        Board board = boardService.findById(id);
        return ResponseEntity.ok().header("ETag", BoardEtag.of(board.getContent())).body(board);
    }

    @RequestMapping(method = RequestMethod.GET)
    @ResponseBody
    public List<Board> findAll() {
        return boardService.findAll();
    }

    @RequestMapping(value = "/{id}", method = RequestMethod.PATCH)
    @ResponseBody
    public ResponseEntity<Board> updateBoard(
        @PathVariable("id") int id, @Valid @RequestBody BoardRequest request,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        HttpServletRequest servletRequest) {
        boolean repeated = Collections.list(servletRequest.getHeaders("If-Match")).size() > 1;
        Board board = boardService.update(id, request.getContent(), ifMatch, repeated);
        return ResponseEntity.ok().header("ETag", BoardEtag.of(board.getContent())).body(board);
    }

    @RequestMapping(value = "/{id}/history", method = RequestMethod.GET)
    @ResponseBody
    public List<BoardHistory> findHistory(@PathVariable("id") int id) {
        return boardService.findHistory(id);
    }

    @RequestMapping(value = "/{id}", method = RequestMethod.DELETE)
    @ResponseBody
    public ResponseEntity<Void> deleteBoard(@PathVariable("id") int id) {
        boardService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(BoardNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(
        BoardNotFoundException exception, HttpServletRequest request) {
        return error(404, "NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(BoardStorageException.class)
    public ResponseEntity<Map<String, Object>> handleStorageFailure(
        BoardStorageException exception, HttpServletRequest request) {
        LOGGER.log(Level.SEVERE, "게시글 저장 실패", exception);
        return error(500, "BOARD_STORAGE_ERROR", exception.getMessage(), request);
    }

    @ExceptionHandler(BoardConditionException.class)
    public ResponseEntity<Map<String, Object>> handleCondition(
        BoardConditionException exception, HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> response = error(
            exception.getStatus(),
            exception.getCode(),
            exception.getMessage(),
            request
        );
        if (exception.getStatus() == 428) {
            return ResponseEntity.status(428).header("Cache-Control", "no-store")
                .body(response.getBody());
        }
        return response;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidBody(HttpServletRequest request) {
        return error(400, "BAD_REQUEST", "올바른 게시글 JSON 본문을 입력해주세요.", request);
    }

    private ResponseEntity<Map<String, Object>> error(
        int status, String code, String message, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("code", code);
        body.put("message", message);
        body.put("path", request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
