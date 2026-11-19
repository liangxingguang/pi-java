package com.pijava.examples;

import java.util.List;
import java.util.Map;

/**
 * docs/52 §2.10：tic-tac-toe 纯逻辑（pi tic-tac-toe.ts:58-176）——棋盘、胜负、
 * ASCII 投影、状态重建。用户光标仅 TUI 用，绝不进入 {@link #boardDetails()}。
 */
final class TicTacToeState {

    static final int AGENT_CURSOR_HOME_ROW = 0;
    static final int AGENT_CURSOR_HOME_COL = 0;
    static final String PLAYING = "playing";
    static final String WIN_X = "win_X";
    static final String WIN_O = "win_O";
    static final String DRAW = "draw";
    static final String SAVE_TYPE = "tic-tac-toe-save";
    static final String MOVE_TYPE = "tic-tac-toe-move";
    static final String GAME_OVER_TYPE = "tic-tac-toe-game-over";

    /** 可变游戏状态（棋盘与两个光标）。 */
    static final class State {
        String[][] board = new String[][] {
            {" ", " ", " "}, {" ", " ", " "}, {" ", " ", " "}};
        int userCursorRow = 1;
        int userCursorCol = 1;
        int agentCursorRow = AGENT_CURSOR_HOME_ROW;
        int agentCursorCol = AGENT_CURSOR_HOME_COL;
        String status = PLAYING;
        String currentTurn = "X";
        final String userMark = "X";
        final String agentMark = "O";

        /** 只暴露代理光标与棋盘给模型（用户光标不泄漏）。 */
        Map<String, Object> boardDetails() {
            var rows = new java.util.ArrayList<List<String>>();
            for (var row : board) {
                rows.add(List.of(row[0], row[1], row[2]));
            }
            return Map.of(
                "board", List.copyOf(rows),
                "agentCursorRow", agentCursorRow,
                "agentCursorCol", agentCursorCol,
                "status", status,
                "currentTurn", currentTurn);
        }
    }

    private TicTacToeState() {}

    static State createInitialState() {
        return new State();
    }

    /** pi L97-147：8 条胜利线，命中返回坐标对；否则 null。 */
    static int[][] getWinLine(String[][] board) {
        int[][] lines = {
            {0, 0, 0, 1, 0, 2}, {1, 0, 1, 1, 1, 2}, {2, 0, 2, 1, 2, 2},
            {0, 0, 1, 0, 2, 0}, {0, 1, 1, 1, 2, 1}, {0, 2, 1, 2, 2, 2},
            {0, 0, 1, 1, 2, 2}, {0, 2, 1, 1, 2, 0}};
        for (var line : lines) {
            var a = board[line[0]][line[1]];
            var b = board[line[2]][line[3]];
            var c = board[line[4]][line[5]];
            if (!" ".equals(a) && a.equals(b) && b.equals(c)) {
                return new int[][] {{line[0], line[1]}, {line[2], line[3]}, {line[4], line[5]}};
            }
        }
        return null;
    }

    /** pi L149-159：胜负/平局/进行中。 */
    static String checkWin(String[][] board) {
        var line = getWinLine(board);
        if (line != null) {
            return "X".equals(board[line[0][0]][line[0][1]]) ? WIN_X : WIN_O;
        }
        for (var row : board) {
            for (var cell : row) {
                if (" ".equals(cell)) {
                    return PLAYING;
                }
            }
        }
        return DRAW;
    }

    /** pi L161-176：空格坐标、代理光标尖括号；用户光标绝不出现。 */
    static String boardToAscii(String[][] board, int agentRow, int agentCol) {
        var rows = new String[3];
        for (int r = 0; r < 3; r++) {
            var cells = new String[3];
            for (int c = 0; c < 3; c++) {
                boolean onCursor = r == agentRow && c == agentCol;
                var cell = board[r][c];
                if (" ".equals(cell)) {
                    cells[c] = onCursor ? "<[" + r + "," + c + "]>" : " [" + r + "," + c + "] ";
                } else {
                    cells[c] = onCursor ? "   <" + cell + ">   " : "    " + cell + "    ";
                }
            }
            rows[r] = String.join("|", cells);
        }
        return String.join("\n---------+---------+---------\n", rows);
    }

    /** 边缘 clamp。 */
    static int clamp(int value) {
        return Math.max(0, Math.min(2, value));
    }

    /** 从工具结果 details 顺序覆盖状态（pi reconstructState L627-646）。 */
    @SuppressWarnings("unchecked")
    static void applyDetails(State state, Object details) {
        if (!(details instanceof Map<?, ?> m)) {
            return;
        }
        if (m.get("board") instanceof List<?> rows && rows.size() == 3) {
            for (int r = 0; r < 3; r++) {
                if (rows.get(r) instanceof List<?> cols && cols.size() == 3) {
                    for (int c = 0; c < 3; c++) {
                        state.board[r][c] = String.valueOf(cols.get(c));
                    }
                }
            }
        }
        if (m.get("agentCursorRow") instanceof Number n) {
            state.agentCursorRow = n.intValue();
        }
        if (m.get("agentCursorCol") instanceof Number n) {
            state.agentCursorCol = n.intValue();
        }
        if (m.get("status") instanceof String s) {
            state.status = s;
        }
        if (m.get("currentTurn") instanceof String s) {
            state.currentTurn = s;
        }
    }
}
