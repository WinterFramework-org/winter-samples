package board.repository;

import board.entitiy.BoardHistory;
import froggy.mybatis.winter.annotation.Mapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BoardHistoryMapper {

    void insert(@Param("boardId") int boardId, @Param("history") BoardHistory history);

    List<BoardHistory> findByBoardId(int boardId);
}
