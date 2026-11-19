package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.pijava.agent.tool.ExecutionMode;
import com.pijava.coding.agent.extension.ExtensionMessageRenderer;

import org.junit.jupiter.api.Test;

/** docs/52 §2.10：tic-tac-toe 验收——走子/撞子/终局、sequential、消息触发、光标不泄漏。 */
class TicTacToeExtensionTest {

    @Test
    void moveActionsClampAndReportCursor() {
        var ext = new TicTacToeExtension();
        assertThat(ext.executeAction("move_up")).isEqualTo("Moved up. Cursor: (0, 0)");
        assertThat(ext.executeAction("move_down")).isEqualTo("Moved down. Cursor: (1, 0)");
        assertThat(ext.executeAction("move_right")).isEqualTo("Moved right. Cursor: (1, 1)");
        assertThat(ext.executeAction("move_left")).isEqualTo("Moved left. Cursor: (1, 0)");
        assertThat(ext.executeAction("bogus")).isEqualTo("Unknown action: bogus");
    }

    @Test
    void fullTurnMoveThenPlayResetsCursorAndEmitsMessage() {
        var w = new W3Context();
        var ext = new TicTacToeExtension();
        ext.register(w.ctx);
        ext.state().currentTurn = "O"; // 用户已落子，轮到代理 O

        ext.executeAction("move_down"); // (1,0)
        ext.executeAction("move_right"); // (1,1)
        var result = ext.executeAction("play");

        assertThat(result).isEqualTo("Placed O at (1,1). Cursor reset to (0,0). Your turn, X!");
        assertThat(ext.state().board[1][1]).isEqualTo("O");
        assertThat(ext.state().agentCursorRow).isZero();
        assertThat(w.appended).contains(TicTacToeState.SAVE_TYPE);
        assertThat(w.sentMessages).hasSize(1); // 落子消息（triggerTurn）
    }

    @Test
    void failedPlayKeepsCursorAndDoesNotEmit() {
        var w = new W3Context();
        var ext = new TicTacToeExtension();
        ext.register(w.ctx);
        // 先让 X 占 (0,0)（模拟用户落子），代理光标 home 也是 (0,0)
        ext.state().board[0][0] = "X";
        ext.state().currentTurn = "O";

        var result = ext.executeAction("play");

        assertThat(result).contains("is already X").contains("cursor is still at (0,0)");
        assertThat(ext.state().agentCursorRow).isZero();
        assertThat(w.sentMessages).isEmpty();
    }

    @Test
    void toolMetadataSequentialAndDetailsHideUserCursor() {
        var ext = new TicTacToeExtension();
        var moveTool = new TicTacToeExtension.MoveTool(ext);
        assertThat(moveTool.name()).isEqualTo("tic_tac_toe");
        assertThat(moveTool.executionMode()).isEqualTo(new ExecutionMode.Sequential());

        var result = moveTool.execute("t1", Map.of("action", "move_down"), null, null, null);
        assertThat(ExampleFixtures.textOf(result.content()))
            .isEqualTo("Moved down. Cursor: (1, 0)");
        assertThat(result.details()).containsKeys("board", "agentCursorRow", "agentCursorCol");
        assertThat(result.details().keySet()).noneMatch(k -> k.contains("user"));

        var seeBoard = new TicTacToeExtension.SeeBoardTool(ext);
        assertThat(seeBoard.executionMode()).isEqualTo(new ExecutionMode.Sequential());
        var seeResult = seeBoard.execute("t2", Map.of(), null, null, null);
        assertThat(ExampleFixtures.textOf(seeResult.content())).contains("Your cursor:");
    }

    @Test
    void nonInteractiveCommandWarns() {
        var w = new W3Context();
        w.mode = "print";
        new TicTacToeExtension().register(w.ctx);

        w.command("tic-tac-toe", "");

        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .containsExactly("Tic-tac-toe requires interactive mode");
    }

    @Test
    void beforeAgentStartInjectsInstructions() {
        var ext = new TicTacToeExtension();
        assertThat(TicTacToeExtension.INSTRUCTIONS).contains("## Tic-Tac-Toe (you are Player O)")
            .contains("CRITICAL: emit the whole turn in a single response")
            .contains("### Examples (cursor starts at (0, 0))");
    }

    @Test
    void renderersProducePlainText() {
        ExtensionMessageRenderer move = TicTacToeExtension.moveRenderer();
        assertThat(move.render("tic-tac-toe-move", null, Map.of(), false, 0))
            .containsExactly("Player X played  →  next: O (Agent)");
        ExtensionMessageRenderer over = TicTacToeExtension.gameOverRenderer();
        assertThat(over.render("tic-tac-toe-game-over",
            com.pijava.agent.entry.CustomMessageContent.of("Game over: Draw."),
            Map.of(), false, 0)).containsExactly("— Draw —");
    }

    @Test
    void gameOverEmitsNonTriggerMessage() {
        var w = new W3Context();
        var ext = new TicTacToeExtension();
        ext.register(w.ctx);
        // 构造一步获胜局面（O 占顶行前两格，光标 home 在 (0,0) 已被占——先把光标移到 (0,2)）
        ext.state().board[0][0] = "O";
        ext.state().board[0][1] = "O";
        ext.state().currentTurn = "O";
        ext.executeAction("move_right");
        ext.executeAction("move_right");

        var result = ext.executeAction("play");

        assertThat(result).contains("Player O wins!");
        assertThat(ext.state().status).isEqualTo("win_O");
        assertThat(w.sentMessages).hasSize(2); // 落子消息 + 终局消息
    }
}
