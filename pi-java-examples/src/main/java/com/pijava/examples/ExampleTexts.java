package com.pijava.examples;

import java.util.List;

import com.pijava.ai.message.ContentBlock;

/** examples 主源码共享的文本投影（拼接 TextContent），test 夹具 ExampleFixtures.textOf 的主源码对位。 */
final class ExampleTexts {

    private ExampleTexts() {}

    static String textOf(List<ContentBlock> content) {
        var text = new StringBuilder();
        for (var block : content) {
            if (block instanceof ContentBlock.TextContent t) {
                text.append(t.text());
            }
        }
        return text.toString();
    }
}
