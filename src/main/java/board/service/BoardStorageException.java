package board.service;

public class BoardStorageException extends RuntimeException {

    public BoardStorageException(Throwable cause) {
        super("게시글을 저장하지 못했습니다. 잠시 후 다시 시도해주세요.", cause);
    }
}
