package com.pijava.coding.agent.rpc;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.ai.message.ContentBlock;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P6-5b: RPC 命令 sealed 层次 ↔ JSON round-trip；未知 type 解析失败。
 */
class RpcCommandTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void allCommandsRoundTrip() throws Exception {
        List<RpcCommand> commands = List.of(
            new RpcCommand.Prompt("1", "hello", null, null),
            new RpcCommand.Prompt("1", "hello",
                List.of(new ContentBlock.ImageContent("image/png", "AA==")),
                RpcCommand.StreamingBehavior.STEER),
            new RpcCommand.Steer("2", "steer text", null),
            new RpcCommand.FollowUp("3", "more"),
            new RpcCommand.Abort("4"),
            new RpcCommand.GetState("5"),
            new RpcCommand.NewSession("6"),
            new RpcCommand.GetMessages("7"),
            new RpcCommand.GetLastAssistantText("8"));

        for (var command : commands) {
            var json = JSON.writeValueAsString(command);
            var back = JSON.readValue(json, RpcCommand.class);
            assertThat(back).isEqualTo(command);
            assertThat(back.type()).isEqualTo(command.type());
        }
    }

    @Test
    void wireTypeFieldIsPresent() throws Exception {
        var json = JSON.writeValueAsString(new RpcCommand.Abort("9"));
        assertThat(json).contains("\"type\":\"abort\"").contains("\"id\":\"9\"");
    }

    @Test
    void unknownTypeThrows() {
        assertThatThrownBy(() -> JSON.readValue(
            "{\"id\":\"1\",\"type\":\"set_model\",\"model\":\"x\"}",
            RpcCommand.class))
            .isInstanceOf(Exception.class);
    }
}
