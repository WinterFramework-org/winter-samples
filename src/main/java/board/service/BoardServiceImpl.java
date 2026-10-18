package board.service;

import board.entitiy.Board;
import board.entitiy.BoardHistory;
import board.repository.BoardHistoryMapper;
import board.repository.BoardMapper;
import froggy.winterframework.beans.factory.annotation.Autowired;
import froggy.winterframework.stereotype.Service;
import froggy.winterframework.transaction.annotation.Transactional;
import java.util.List;
import org.apache.ibatis.exceptions.PersistenceException;

@Service
public class BoardServiceImpl implements BoardService {

    private final BoardMapper boardMapper;
    private final BoardHistoryMapper historyMapper;

    @Autowired
    public BoardServiceImpl(BoardMapper boardMapper, BoardHistoryMapper historyMapper) {
        this.boardMapper = boardMapper;
        this.historyMapper = historyMapper;
    }

    @Override
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

    @Override
    public Board findById(int id) {
        Board board = boardMapper.findById(id);
        if (board == null) {
            throw new BoardNotFoundException();
        }
        return board;
    }

    @Override
    public List<Board> findAll() {
        return boardMapper.findAll();
    }

    @Override
    @Transactional
    public Board update(int id, String content) {
        try {
            Board previous = boardMapper.findByIdForUpdate(id);
            if (previous == null) {
                throw new BoardNotFoundException();
            }
            if (previous.getContent().equals(content)) {
                return previous;
            }
            Board board = new Board();
            board.setId(id);
            board.setContent(content);
            boardMapper.update(board);
            historyMapper.insert(id, new BoardHistory(previous.getContent(), content));
            return board;
        } catch (PersistenceException exception) {
            throw new BoardStorageException(exception);
        }
    }

    @Override
    public List<BoardHistory> findHistory(int id) {
        findById(id);
        return historyMapper.findByBoardId(id);
    }

    @Override
    public void delete(int id) {
        Board board = findById(id);
        try {
            boardMapper.delete(board);
        } catch (PersistenceException exception) {
            throw new BoardStorageException(exception);
        }
    }
}
