package board.controller;

import board.entitiy.Board;
import board.repository.BoardMapper;
import froggy.winterframework.beans.factory.annotation.Autowired;
import froggy.winterframework.stereotype.Controller;
import froggy.winterframework.web.bind.annotation.HttpMethod;
import froggy.winterframework.web.bind.annotation.PathVariable;
import froggy.winterframework.web.bind.annotation.RequestMapping;
import froggy.winterframework.web.bind.annotation.RequestParam;
import froggy.winterframework.web.bind.annotation.ResponseBody;
import java.util.List;

@Controller
@RequestMapping(urlPattern = "/board")
public class BoardController {

    private final BoardMapper boardMapper;

    @Autowired
    public BoardController(BoardMapper boardMapper) {
        this.boardMapper = boardMapper;
    }

    @RequestMapping(httpMethod = {HttpMethod.POST})
    @ResponseBody
    public Board addBoard(@RequestParam("content") String content) {
        Board board = new Board();
        board.setContent(content);
        boardMapper.insert(board);

        return board;
    }
    @RequestMapping(urlPattern = "/{id}", httpMethod = {HttpMethod.GET})
    @ResponseBody
    public Board findBoard(@PathVariable("id") int id) {
        return boardMapper.findById(id);
    }

    @RequestMapping(httpMethod = HttpMethod.GET)
    @ResponseBody
    public List<Board> findAll() {
        return boardMapper.findAll();
    }

    @RequestMapping(urlPattern = "/{id}", httpMethod = HttpMethod.PATCH)
    @ResponseBody
    public Board updateBoard(@PathVariable("id") int id, @RequestParam("content") String content) {
        Board board = new Board();
        board.setId(id);
        board.setContent(content);

        boardMapper.update(board);

        return boardMapper.findById(id);
    }

    @RequestMapping(urlPattern = "/{id}", httpMethod = HttpMethod.DELETE)
    @ResponseBody
    public String deleteBoard(@PathVariable("id") int id) {
        Board board = new Board();
        board.setId(id);

        boardMapper.delete(board);

        return "정상적으로 삭제되었습니다.";
    }

}