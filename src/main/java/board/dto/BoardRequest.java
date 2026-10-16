package board.dto;

import javax.validation.constraints.NotBlank;

public class BoardRequest {

    @NotBlank(message = "게시글 본문을 입력해주세요.")
    private String content;

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
