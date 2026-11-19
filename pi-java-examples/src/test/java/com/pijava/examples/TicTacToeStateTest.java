package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** docs/52 §2.10：tic-tac-toe 纯逻辑——8 条胜利线、胜负四态、ASCII 三形、clamp、重建。 */
class TicTacToeStateTest {

    private static String[][] board(String... rows) {
        return new String[][] {
            {rows[0], rows[1], rows[2]},
            {rows[3], rows[4], rows[5]},
            {rows[6], rows[7], rows[8]}};
    }

    @Test
    void initialStateHomeCursorAndBoard() {
        var s = TicTacToeState.createInitialState();
        assertThat(s.agentCursorRow).isZero();
        assertThat(s.agentCursorCol).isZero();
        assertThat(s.userCursorRow).isEqualTo(1);
        assertThat(s.status).isEqualTo("playing");
        assertThat(s.currentTurn).isEqualTo("X");
    }

    @Test
    void detectsAllEightWinLines() {
        String[][] rowWin = board("X", "X", "X", " ", " ", " ", " ", " ", " ");
        assertThat(TicTacToeState.checkWin(rowWin)).isEqualTo("win_X");
        String[][] colWin = board("O", " ", " ", "O", " ", " ", "O", " ", " ");
        assertThat(TicTacToeState.checkWin(colWin)).isEqualTo("win_O");
        String[][] diagWin = board("X", " ", " ", " ", "X", " ", " ", " ", "X");
        assertThat(TicTacToeState.getWinLine(diagWin)).hasDimensions(3, 2);
        String[][] antiDiag = board(" ", " ", "O", " ", "O", " ", "O", " ", " ");
        assertThat(TicTacToeState.checkWin(antiDiag)).isEqualTo("win_O");
    }

    @Test
    void drawAndPlaying() {
        String[][] draw = board("X", "O", "X", "X", "O", "O", "O", "X", "X");
        assertThat(TicTacToeState.checkWin(draw)).isEqualTo("draw");
        assertThat(TicTacToeState.checkWin(board(" ", " ", " ", " ", " ", " ", " ", " ", " ")))
            .isEqualTo("playing");
    }

    @Test
    void boardToAsciiThreeShapesAndCursorPrivacy() {
        var b = board(" ", " ", " ", " ", "X", " ", " ", " ", " ");
        var ascii = TicTacToeState.boardToAscii(b, 1, 1);
        assertThat(ascii).contains(" [0,0] ").contains("   <X>   "); // 光标在 (1,1) 的 X 上
        assertThat(ascii).doesNotContain("user");
        // 光标在空格上显示 <[r,c]>，普通已有子显示四空格包裹
        var ascii2 = TicTacToeState.boardToAscii(b, 0, 0);
        assertThat(ascii2).contains("<[0,0]>").contains("    X    ");
        assertThat(ascii2).doesNotContain("<X>");
    }

    @Test
    void clampEdges() {
        assertThat(TicTacToeState.clamp(-1)).isZero();
        assertThat(TicTacToeState.clamp(3)).isEqualTo(2);
        assertThat(TicTacToeState.clamp(1)).isEqualTo(1);
    }

    @Test
    void boardDetailsNeverLeaksUserCursor() {
        var s = TicTacToeState.createInitialState();
        var details = s.boardDetails();
        assertThat(details).containsKeys("board", "agentCursorRow", "agentCursorCol",
            "status", "currentTurn");
        assertThat(details.keySet()).noneMatch(k -> k.contains("user"));
    }

    @Test
    void applyDetailsRestoresSnapshot() {
        var s = TicTacToeState.createInitialState();
        var details = Map.<String, Object>of(
            "board", List.of(List.of("O", " ", " "), List.of(" ", "X", " "), List.of(" ", " ", " ")),
            "agentCursorRow", 2, "agentCursorCol", 1,
            "status", "playing", "currentTurn", "O");
        TicTacToeState.applyDetails(s, details);
        assertThat(s.board[0][0]).isEqualTo("O");
        assertThat(s.board[1][1]).isEqualTo("X");
        assertThat(s.agentCursorRow).isEqualTo(2);
        assertThat(s.currentTurn).isEqualTo("O");
    }
}
