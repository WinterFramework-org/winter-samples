package board.service;

import board.entitiy.Board;
import board.repository.BoardMapper;
import froggy.winterframework.beans.factory.annotation.Autowired;
import froggy.winterframework.stereotype.Service;
import java.util.List;
import org.apache.ibatis.exceptions.PersistenceException;

@Service
public class BoardService {

    private final BoardMapper boardMapper;

    @Autowired
    public BoardService(BoardMapper boardMapper) {
        this.boardMapper = boardMapper;
    }

    public Board create(String content) {
        Board board = new Board();
        board.setContent(content);
        try {
            boardMapper.insert(board);
            return board;
        } catch (PersistenceException exception) {
            throw new BoardStorageException(exception);
        }
    }

    public Board findById(int id) {
        Board board = boardMapper.findById(id);
        if (board == null) {
            throw new BoardNotFoundException();
        }
        return board;
    }

    public List<Board> findAll() {
        return boardMapper.findAll();
    }

    public Board update(int id, String content) {
        findById(id);
        Board board = new Board();
        board.setId(id);
        board.setContent(content);
        try {
            boardMapper.update(board);
            return findById(id);
        } catch (PersistenceException exception) {
            throw new BoardStorageException(exception);
        }
    }

    public void delete(int id) {
        Board board = findById(id);
        try {
            boardMapper.delete(board);
        } catch (PersistenceException exception) {
            throw new BoardStorageException(exception);
        }
    }
}
