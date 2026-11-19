package com.pijava.examples;

import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.CustomMessageContent;
import com.pijava.agent.entry.Entry;
import com.pijava.agent.tool.AgentTool;
import com.pijava.agent.tool.ExecutionMode;
import com.pijava.agent.tool.ToolContext;
import com.pijava.agent.tool.ToolResult;
import com.pijava.agent.tool.ToolUpdateCallback;
import com.pijava.ai.AbortSignal;
import com.pijava.ai.message.ContentBlock;
import com.pijava.coding.agent.extension.ExtensionComponent;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionMessageRenderer;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEvent;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.extension.event.ExtensionResults.BeforeAgentStartEventResult;

/**
 * docs/52 §2.10：tic-tac-toe 注册与消息编排（pi tic-tac-toe.ts:619-1007）——
 * 两个工具（sequential）、before_agent_start 注入、落子/终局消息、两个消息渲染器。
 * 纯逻辑拆至 {@link TicTacToeState}，UI 组件拆至 {@link TicTacToeBoardComponent}。
 */
public final class TicTacToeExtension implements PiExtension {

    /** pi L710-768：注入指令（逐字骨架，(0, 0) 为代理光标 home）。 */
    static final String INSTRUCTIONS = """
        
        ## Tic-Tac-Toe (you are Player O)
        
        A tic-tac-toe game is in progress. The human is Player X. You are Player O.
        The human plays through a TUI; you play through the `tic_tac_toe` tool.
        
        ### Turn protocol
        
        When the human plays, you receive a message that contains the cell X marked,
        the full board, and YOUR cursor position (Player O's cursor). The message is
        the source of truth for the board.
        
        Player O's cursor persists between O turns. It is reset to (row=0, col=0)
        only after a successful `play`. If a `play` fails (cell already taken), the
        cursor stays where it was, so you can move and retry.
        
        You may also call `tic_tac_toe_see_board` if you want the current board and
        your cursor position restated at any point. The user's cursor is private and
        is never shown to you.
        
        ### The tool
        
        `tic_tac_toe` takes ONE action per call:
        - `move_up` / `move_down` / `move_left` / `move_right`: move YOUR cursor one cell (clamped at edges)
        - `play`: place O on the cell under YOUR cursor. Errors if the cell is not empty.
        
        There is no batched form. One call = one action.
        
        ### CRITICAL: emit the whole turn in a single response
        
        To play at (r, c) from your cursor (r0, c0) emit, in order:
        - `move_down` (r - r0) times (or `move_up` (r0 - r) times if r < r0)
        - `move_right` (c - c0) times (or `move_left` (c0 - c) times if c < c0)
        - one call of `play`
        
        All of these tool calls MUST be emitted in the SAME assistant response, as
        separate tool_use blocks, before you stop. Do not:
        - split the sequence across multiple assistant responses,
        - wait for a move result before emitting the next move or `play`,
        - write any explanation or text between the tool calls,
        - call any other tool during your turn (except `tic_tac_toe_see_board` when you
          explicitly need the state restated).
        
        Decide the target cell first, then dump every action for the turn in one go.
        
        ### Examples (cursor starts at (0, 0))
        
        - Target (0,0): one call, `play`.
        - Target (0,2): `move_right`, `move_right`, `play`. Three calls, one response.
        - Target (1,1): `move_down`, `move_right`, `play`. Three calls, one response.
        - Target (2,2): `move_down`, `move_down`, `move_right`, `move_right`, `play`. Five calls, one response.
        
        ### Strategy
        
        1. If you have two O's in a line with the third cell empty, win by playing there.
        2. Otherwise, if X has two in a line with the third cell empty, block there.
        3. Otherwise, prefer center, then corners, then edges.
        """;

    private final TicTacToeState.State state = TicTacToeState.createInitialState();
    private ExtensionContext captured;

    @Override
    public String name() {
        return "tic-tac-toe";
    }

    @Override
    public void register(ExtensionContext ctx) {
        this.captured = ctx;
        ctx.on(ExtensionEventSpec.SESSION_START, (event, c) -> {
            rebuild(c);
            return null;
        });
        ctx.on(ExtensionEventSpec.SESSION_TREE, (event, c) -> {
            rebuild(c);
            return null;
        });
        ctx.on(ExtensionEventSpec.BEFORE_AGENT_START, (event, c) ->
            new BeforeAgentStartEventResult(null, event.systemPrompt() + INSTRUCTIONS));
        ctx.slashCommands().register(ExampleCommands.command("tic-tac-toe",
            "Play tic-tac-toe against the agent", args -> startGame(ctx)));
        ctx.registerMessageRenderer(TicTacToeState.MOVE_TYPE, moveRenderer());
        ctx.registerMessageRenderer(TicTacToeState.GAME_OVER_TYPE, gameOverRenderer());
        ctx.tools().register(new MoveTool(this));
        ctx.tools().register(new SeeBoardTool(this));
    }

    /** 从分支的工具结果重建棋盘（pi L627-646）。 */
    void rebuild(ExtensionContext ctx) {
        var fresh = TicTacToeState.createInitialState();
        for (var entry : ctx.sessionManager().getBranch()) {
            if (!(entry instanceof Entry.Message m)
                    || !(m.message() instanceof com.pijava.ai.message.Message.ToolResultMessage t)
                    || !("tic_tac_toe".equals(t.toolName())
                         || "tic_tac_toe_see_board".equals(t.toolName()))) {
                continue;
            }
            TicTacToeState.applyDetails(fresh, t.details());
        }
        copyInto(state, fresh);
    }

    private static void copyInto(TicTacToeState.State target, TicTacToeState.State source) {
        for (int r = 0; r < 3; r++) {
            System.arraycopy(source.board[r], 0, target.board[r], 0, 3);
        }
        target.agentCursorRow = source.agentCursorRow;
        target.agentCursorCol = source.agentCursorCol;
        target.status = source.status;
        target.currentTurn = source.currentTurn;
        target.userCursorRow = source.userCursorRow;
        target.userCursorCol = source.userCursorCol;
    }

    /** 命令：仅交互模式，重置棋盘并命名会话。 */
    void startGame(ExtensionContext ctx) {
        if (!"tui".equals(ctx.mode())) {
            ctx.ui().notify("Tic-tac-toe requires interactive mode", NotifyType.ERROR);
            return;
        }
        var fresh = TicTacToeState.createInitialState();
        copyInto(state, fresh);
        ctx.setSessionName("Tic-Tac-Toe");
        ctx.ui().custom((com.pijava.coding.agent.extension.ExtensionUiHostEnv env,
                         com.pijava.coding.agent.core.KeybindingsManager kb,
                         java.util.function.Consumer<String> done) -> {
            done.accept(null);
            return new TicTacToeBoardComponent(this);
        }, null);
    }

    /** 执行一个工具动作，返回 content 文本（pi L883-939）。 */
    String executeAction(String action) {
        if (action == null) {
            return "Unknown action: null";
        }
        switch (action) {
            case "move_up": state.agentCursorRow = TicTacToeState.clamp(state.agentCursorRow - 1);
                return moved("up");
            case "move_down": state.agentCursorRow = TicTacToeState.clamp(state.agentCursorRow + 1);
                return moved("down");
            case "move_left": state.agentCursorCol = TicTacToeState.clamp(state.agentCursorCol - 1);
                return moved("left");
            case "move_right": state.agentCursorCol = TicTacToeState.clamp(state.agentCursorCol + 1);
                return moved("right");
            case "play": return play();
            default: return "Unknown action: " + action;
        }
    }

    private String moved(String direction) {
        return "Moved " + direction + ". Cursor: ("
            + state.agentCursorRow + ", " + state.agentCursorCol + ")";
    }

    private String play() {
        if (!TicTacToeState.PLAYING.equals(state.status)) {
            return "Game is over (" + state.status + ").";
        }
        if (!"O".equals(state.currentTurn)) {
            return "It is not your turn.";
        }
        var r = state.agentCursorRow;
        var c = state.agentCursorCol;
        if (!" ".equals(state.board[r][c])) {
            return "Cell (" + r + "," + c + ") is already " + state.board[r][c]
                + ". Your cursor is still at (" + r + "," + c
                + "). Move to an empty cell and retry play.";
        }
        state.board[r][c] = "O";
        var status = TicTacToeState.checkWin(state.board);
        state.status = status;
        state.currentTurn = "X";
        state.agentCursorRow = TicTacToeState.AGENT_CURSOR_HOME_ROW;
        state.agentCursorCol = TicTacToeState.AGENT_CURSOR_HOME_COL;

        if (captured != null) {
            captured.appendEntry(TicTacToeState.SAVE_TYPE, state.boardDetails());
            emitPlayerMove(captured);
            if (!TicTacToeState.PLAYING.equals(status)) {
                emitGameOver(captured, status);
            }
        }
        return switch (status) {
            case TicTacToeState.WIN_O -> "Placed O at (" + r + "," + c + "). Player O wins!";
            case TicTacToeState.DRAW -> "Placed O at (" + r + "," + c + "). It's a draw!";
            default -> "Placed O at (" + r + "," + c
                + "). Cursor reset to (0,0). Your turn, X!";
        };
    }

    /** 用户落子消息（pi L822-827）：触发挥下一回合。 */
    void emitPlayerMove(ExtensionContext ctx) {
        var ascii = TicTacToeState.boardToAscii(state.board,
            state.agentCursorRow, state.agentCursorCol);
        var text = "Player X played at (row=" + state.userCursorRow + ", col="
            + state.userCursorCol + "). It is now Player O's turn.\n\n"
            + "Board (your cursor marked with <>):\n" + ascii + "\n\n"
            + "Your cursor is at (row=" + state.agentCursorRow + ", col="
            + state.agentCursorCol + "). Decide your target cell, then emit every move_* and the final play "
            + "as separate tic_tac_toe tool calls in THIS response.";
        ctx.sendMessage(TicTacToeState.MOVE_TYPE, CustomMessageContent.of(text), true,
            state.boardDetails(), new ExtensionContext.SendMessageOptions(true));
    }

    /** 终局消息（pi L666-679）：不触发回合。 */
    void emitGameOver(ExtensionContext ctx, String status) {
        var label = switch (status) {
            case TicTacToeState.WIN_X -> "Player X (human) wins";
            case TicTacToeState.WIN_O -> "Player O (agent) wins";
            case TicTacToeState.DRAW -> "Draw";
            default -> "Game over";
        };
        ctx.sendMessage(TicTacToeState.GAME_OVER_TYPE,
            CustomMessageContent.of("Game over: " + label + "."), true,
            state.boardDetails(), new ExtensionContext.SendMessageOptions(false));
    }

    TicTacToeState.State state() {
        return state;
    }

    static ExtensionMessageRenderer moveRenderer() {
        return (customType, content, details, expanded, outputPad) -> {
            var turnLabel = "O (Agent)";
            return List.of("Player X played  →  next: " + turnLabel);
        };
    }

    static ExtensionMessageRenderer gameOverRenderer() {
        return (customType, content, details, expanded, outputPad) -> {
            var text = content == null ? "" : content.toString();
            return List.of(text.contains("Player X") ? "★ Player X wins ★"
                : text.contains("Player O") ? "★ Player O wins ★"
                : text.contains("Draw") ? "— Draw —" : "Game over");
        };
    }

    /** tic_tac_toe 工具本体。 */
    static final class MoveTool implements AgentTool<Map<String, Object>, Map<String, Object>> {
        private final TicTacToeExtension ext;

        MoveTool(TicTacToeExtension ext) {
            this.ext = ext;
        }

        @Override public String name() { return "tic_tac_toe"; }
        @Override public String label() { return "Tic-Tac-Toe"; }
        @Override public String description() {
            return "Execute ONE tic-tac-toe action as Player O. move_up/down/left/right or play.";
        }
        @Override public Map<String, Object> inputSchema() {
            return JsonSchemas.obj("type", "object", "properties", JsonSchemas.obj(
                "action", JsonSchemas.obj("type", "string", "enum",
                    List.of("move_up", "move_down", "move_left", "move_right", "play"))));
        }
        @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }

        @Override
        public ToolResult<Map<String, Object>> execute(String toolCallId,
                Map<String, Object> params, AbortSignal signal,
                ToolUpdateCallback<Map<String, Object>> onUpdate, ToolContext context) {
            var action = params.get("action") instanceof String s ? s : null;
            var text = ext.executeAction(action);
            return new ToolResult<>(List.of(new ContentBlock.TextContent(text)),
                ext.state.boardDetails(), null, false, List.of());
        }
    }

    /** tic_tac_toe_see_board 工具（无参数）。 */
    static final class SeeBoardTool implements AgentTool<Map<String, Object>, Map<String, Object>> {
        private final TicTacToeExtension ext;

        SeeBoardTool(TicTacToeExtension ext) {
            this.ext = ext;
        }

        @Override public String name() { return "tic_tac_toe_see_board"; }
        @Override public String label() { return "See Board"; }
        @Override public String description() {
            return "Return the current tic-tac-toe board state and YOUR cursor position (Player O).";
        }
        @Override public Map<String, Object> inputSchema() {
            return JsonSchemas.obj("type", "object", "properties", Map.of());
        }
        @Override public ExecutionMode executionMode() { return new ExecutionMode.Sequential(); }

        @Override
        public ToolResult<Map<String, Object>> execute(String toolCallId,
                Map<String, Object> params, AbortSignal signal,
                ToolUpdateCallback<Map<String, Object>> onUpdate, ToolContext context) {
            var s = ext.state;
            var ascii = TicTacToeState.boardToAscii(s.board, s.agentCursorRow, s.agentCursorCol);
            var text = "Board (your cursor marked with <>):\n" + ascii + "\n\n"
                + "Your cursor: (row=" + s.agentCursorRow + ", col=" + s.agentCursorCol + ")\n"
                + "Status: " + s.status + "\n"
                + "Turn: " + ("O".equals(s.currentTurn) ? "Player O (you)" : "Player X");
            return new ToolResult<>(List.of(new ContentBlock.TextContent(text)),
                s.boardDetails(), null, false, List.of());
        }
    }
}
