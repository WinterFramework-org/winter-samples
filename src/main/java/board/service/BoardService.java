package board.service;

import board.entitiy.Board;
import board.entitiy.BoardHistory;
import java.util.List;

public interface BoardService {

    Board create(String content);

    Board findById(int id);

    List<Board> findAll();

    Board update(int id, String content, String ifMatch, boolean repeatedIfMatch);

    List<BoardHistory> findHistory(int id);

    void delete(int id);
}
