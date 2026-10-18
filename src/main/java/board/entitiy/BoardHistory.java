package board.entitiy;

import java.time.LocalDateTime;

public class BoardHistory {

    private Long id;
    private String previousContent;
    private String newContent;
    private LocalDateTime changedAt;

    public BoardHistory() {
    }

    public BoardHistory(String previousContent, String newContent) {
        this.previousContent = previousContent;
        this.newContent = newContent;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPreviousContent() { return previousContent; }
    public void setPreviousContent(String previousContent) { this.previousContent = previousContent; }

    public String getNewContent() { return newContent; }
    public void setNewContent(String newContent) { this.newContent = newContent; }

    public LocalDateTime getChangedAt() { return changedAt; }
    public void setChangedAt(LocalDateTime changedAt) { this.changedAt = changedAt; }
}
