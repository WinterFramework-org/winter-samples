CREATE TABLE IF NOT EXISTS board (
    id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    content TEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS board_history (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    board_id INT NOT NULL,
    previous_content TEXT NOT NULL,
    new_content TEXT NOT NULL,
    changed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_board_history_board_changed (board_id, changed_at, id),
    CONSTRAINT fk_board_history_board FOREIGN KEY (board_id)
        REFERENCES board (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
