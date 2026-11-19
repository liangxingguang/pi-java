package com.pijava.examples;

import java.util.ArrayList;
import java.util.List;

import com.pijava.coding.agent.extension.ExtensionComponent;

/**
 * docs/52 §2.10：tic-tac-toe 棋盘 UI 组件（pi TicTacToeComponent，D238 纯文本降级）——
 * 无 ANSI 颜色、无 Unicode 边框、无动画；渲染 ASCII 棋盘与状态/键位文本行。
 */
final class TicTacToeBoardComponent implements ExtensionComponent {

    private final TicTacToeExtension ext;

    TicTacToeBoardComponent(TicTacToeExtension ext) {
        this.ext = ext;
    }

    @Override
    public List<String> render(int width) {
        var s = ext.state();
        var lines = new ArrayList<String>();
        lines.add(" Tic-Tac-Toe ");
        var ascii = TicTacToeState.boardToAscii(s.board, s.userCursorRow, s.userCursorCol);
        for (var row : ascii.split("\n")) {
            lines.add(row);
        }
        lines.add(switch (s.status) {
            case TicTacToeState.WIN_X -> "X wins!";
            case TicTacToeState.WIN_O -> "O wins!";
            case TicTacToeState.DRAW -> "Draw!";
            default -> "Turn: " + s.currentTurn + " (X You | O Agent)";
        });
        if (TicTacToeState.PLAYING.equals(s.status)) {
            lines.add("←↑↓→ move | ENTER play | ESC quit");
        } else {
            lines.add("R restart | Q/ESC quit");
        }
        return lines;
    }

    @Override
    public void handleInput(com.pijava.coding.agent.core.KeybindingsManager.KeyStroke key) {
    }
}
