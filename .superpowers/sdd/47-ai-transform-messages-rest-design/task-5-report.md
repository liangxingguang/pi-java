# Task 5 report

## 接线清单

- `AnthropicMessagesApi.buildParams` now passes a fresh `AnthropicToolCallIds.create()` to the five-argument `TransformMessages.apply` overload.
- `GoogleGenerativeAiApi` passes a fresh `GoogleToolCallIds.create()` before `GoogleMessageConverter.toContents`.
- `MistralConversationsApi.toMistralMessages` creates a fresh `MistralToolCallIds.create()` per message conversion/request, preserving the normalizer's request-local state.
- `OpenAICompletionsMessageConverter.buildParams` passes `CompletionsToolCallIds.create()`.
- Shared `ResponsesMessageConverter.convertMessages` passes `ResponsesToolCallIds.create(apiName)`, so both `OpenAIResponsesApi` (`openai-responses`) and `AzureOpenAIResponsesApi` (`azure-openai-responses`) retain their distinct API names.
- `PiMessagesApi` and all normalizer implementations were left unchanged.

## TDD and test evidence

- RED: before production wiring, the new completions converter-path assertion observed the original pipe-containing id instead of the expected normalized/hash form. This was the four-argument overload behavior.
- GREEN: after wiring, `LaneTransformMessagesWiringTest` passed 14/14. Production-path fixtures cover Anthropic, Google gated/non-gated HTTP requests, Completions, Mistral sequential requests on one adapter/server, and OpenAI/Azure Responses adapter paths with same-origin source identities and exact API-sensitive IDs.
- Mutation probe: temporarily removed `CompletionsToolCallIds.create()` from `OpenAICompletionsMessageConverter`, ran the focused real converter-path test, and it failed (`expected ccccc..._tbuqnb14 but was ccccc...|ffffffff...`). The source was restored before the final commit; the restored test passed.
- RED/mutation probes: removing Google adapter wiring made the gated HTTP assertion fail; removing Mistral adapter wiring made the Mistral request-body assertions fail. The earlier Completions mutation also failed. All production sources were restored before regression.
- Mandatory regression passed:
  `export JAVA_HOME='D:\soft\jdk\graalvm-jdk-25' && /d/soft/apache-maven-3.9.9/bin/mvn -pl pi-java-ai test -am`
  Reactor completed successfully. Maven/checkstyle emitted pre-existing warnings (invalid local Doxia POM bytes and existing source warnings), but checkstyle reported 0 violations.

## Mistral state lifecycle

- Mistral uses one adapter and one recording server for two sequential requests: `abcdefgh1` stays the direct nine-character candidate and `abc-defgh1` exercises the collision retry path; the adapter creates a fresh normalizer per request.

## OpenAI/Azure Responses evidence

Both adapters call the shared `ResponsesMessageConverter.buildParams` with their own `apiName()`. The converter now constructs `ResponsesToolCallIds.create(apiName)` at the single shared conversion point. The fixture exercises both strings and asserts that same-origin handling differs, proving the API name is not hard-coded or shared incorrectly.

## Concerns / deviations

- Reviewed main lineage is `d88aa7c`, `3f7225d`, `ac58491`, `58de9eb`, `d5e3fa1`; implementation/fix lineage is `50b50a1`, `5bd70d6`, `c8b0936`, `8b5b5c6`, and `8776a98`.
- The repository's existing checkstyle warnings remain outside this task; no new checkstyle violations were introduced.
