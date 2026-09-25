# Task 6 report — pi transform-messages Copilot OpenAI → Anthropic oracles

## Scope and mapping

| # | pi oracle | Java fixture | Observable assertion |
|---|---|---|---|
| 1 | Cross-model OpenAI/Copilot thinking plus text | `crossModelThinkingIsDowngradedToText` | Thinking disappears; its text and ordinary text are two `TextContent` blocks |
| 2 | Cross-model tool call with `thoughtSignature` | `toolUseShapeHasNoThoughtSignatureComponent` | Shape evidence only: `ToolUseContent` has no `thoughtSignature` record component |
| 3 | Trailing orphan `call_123|fc_123` | `trailingOrphanGetsNormalizedSyntheticErrorResult` | `call_123_fc_123`, `read`, error, exact `No result provided` payload |
| 4 | Two calls, only first answered | `selectiveOrphanSynthesisKeepsRealResultAndAddsOneMissingResult` | Real result is normalized and retained; exactly one synthetic error for second call |

All behavior fixtures call the public five-argument `TransformMessages.apply` with target `ModelId.of("github-copilot", "claude-sonnet-4.6")`, a complete `ModelInfo`, and `AnthropicToolCallIds.create()`. Case 1 uses source identity `api=openai-completions`, `provider=github-copilot`, `model=gpt-4o`, `stopReason=stop`; cases 2–4 use `api=openai-responses`, `provider=github-copilot`, `model=gpt-5`, `stopReason=toolUse`, matching the individual pi oracle scenarios.

## TDD evidence

- **RED:** The initial focused run first exposed a test-only AssertJ API mismatch (`doesNotContainInstanceOf` is unavailable for this list assertion). After correcting the assertion to filter and assert empty, the fixture compiled and passed; no production code was changed.
- **GREEN:** Focused test passed: 4 tests, 0 failures, 0 errors.
- **Mutation probe:** Temporarily replaced the five-argument normalizer with `null` in the test helper. The focused run failed exactly on the two normalized-id expectations (`call_123|fc_123` and `call_1|fc_1` remained unchanged), demonstrating that the #3/#4 oracle depends on the five-argument Anthropic normalizer. The helper was restored and the focused test returned to 4/4 green. This is a test-input mutation only; it does not claim a production mutation was performed.
- **Mandatory regression:**
  `export JAVA_HOME='D:\soft\jdk\graalvm-jdk-25' && /d/soft/apache-maven-3.9.9/bin/mvn -pl pi-java-ai test -am`
  completed successfully. The build output reports pre-existing checkstyle warnings in unrelated files and local Maven Doxia POM parse warnings; checkstyle reports 0 violations.

## B14b/R1 shape ruling

Pi's oracle expects a `thoughtSignature` property to be removed from a tool call. Java's `ContentBlock.ToolUseContent` record has only `id`, `name`, and `arguments`; the field is structurally unrepresentable. The #2 test explicitly asserts that `thoughtSignature` is absent and does not fake a field, add production stripping logic, or claim behavioral coverage. As documented in its javadoc, this test cannot turn red because an imaginary stripping branch was deleted; it can only detect an accidental forbidden shape change.

## Java-shape differences

- Pi has `toolCall`/`toolResult` messages and a tool-call `thoughtSignature` property; Java uses `ToolUseContent`/`ToolResultMessage` and cannot carry that property.
- Java's public transform API also takes target `ModelInfo` and an explicit `ToolCallIdNormalizer`; the oracle deliberately supplies the real Anthropic normalizer rather than invoking `OrphanToolResults` directly.
- The Java fixture uses value-based record assertions and explicitly checks the final synthetic `ToolResultMessage` payload.

## Concerns

No production concerns. Existing repository checkstyle warnings and the local Doxia-cache parse warning remain unrelated to this test-only change. The #2 oracle is intentionally a non-behavioral shape report per B14b/R1.
