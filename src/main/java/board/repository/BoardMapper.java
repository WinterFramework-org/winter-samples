package board.repository;

import board.entitiy.Board;
import froggy.mybatis.winter.annotation.Mapper;
import java.util.List;

@Mapper
public interface BoardMapper {

    void insert(Board board);

    Board findById(int id);

    List<Board> findAll();

    void update(Board board);

    void delete(Board board);
}