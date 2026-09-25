# Task 7 report — B14 regression, documentation backfill, and closeout ledger

## STATUS

Task 7 was completed from base `c6bce1c` in the isolated task worktree. B14 production semantics were not changed. The work consisted of the listed prior-review quality cleanups, documentation backfill, and verification.

## Commands and results

1. Base/worktree checks:
   - `git rev-parse HEAD` initially showed an unrelated worktree tip; `git reset --hard c6bce1c` aligned this task worktree to the required base.
   - `git status --short --branch` was clean before edits.
   - `git diff --check` passed after edits.
   - `docs/31-gap-inventory.md` was checked and does not exist. The repository's `docs/31-agent-loop-host-alignment-design.md` does exist and contains the B14 entry; that entry was backfilled rather than creating a new file.

2. Prior-review quality inspection and changes (only still-present items changed):
   - Removed duplicate `ShortHash` import from `pi-java-ai/src/main/java/com/pijava/ai/protocol/CompletionsToolCallIds.java`.
   - Removed unused `Message`/`ModelId` imports and normalized the closing-brace line in `pi-java-ai/src/main/java/com/pijava/ai/protocol/ResponsesToolCallIds.java`.
   - Removed unused `ToolCallIdNormalizer` import from `pi-java-ai/src/test/java/com/pijava/ai/protocol/ToolCallIdsTest.java`.
   - Added a Completions pipe case with a 35-character call id, explicitly asserting the 31-character prefix and 40-character result.
   - Added `a != b` coverage to `mistralTwoDistinctIdsGetStableDistinctResults`.
   - Corrected `TransformMessagesIdNormalizationTest`'s class Javadoc to state the measured stub result: only cases 1/6 are red under the stub; case 5 is observationally equivalent to an empty mapping; cases 2/3/4 are green regression/gate shapes.
   - No B14 changed Java file exceeds 500 lines: 38, 71, 220, and 313 lines respectively.
   - Diff scan found no added `System.out.println` and no added `@SuppressWarnings`.

3. Documentation:
   - `docs/41-gap-inventory.md`: B14 now records id normalization (③) and orphan result synthesis (④) as implemented, cites `bb08034/9141c34/b2a8da5/c0587e9/d88aa7c`, and cites final wiring/fixture and oracle closure `89cce36`/`c6bce1c`. B14b/R1, R2, R3, R4, and R5 remain explicitly registered.
   - `docs/31-agent-loop-host-alignment-design.md`: the B14 row now records H2 image downgrade and B14 id/orphan implementation, with the remaining B14b/R1 and R2–R5 ledger entries retained.
   - `docs/47-ai-transform-messages-rest-design.md`: status now says Task 0–7 completed; the acceptance section records the measured B14 focused count and points to this report; the ShortHash constant note now accurately says `1597334677` is below `Integer.MAX_VALUE`; approved design decisions and known differences were not rewritten.

4. Focused B14 regression:
   ```text
   export JAVA_HOME='D:\soft\jdk\graalvm-jdk-25' && /d/soft/apache-maven-3.9.9/bin/mvn -pl pi-java-ai -am -Dtest=TransformMessagesCopilotAnthropicOracleTest,TransformMessagesIdNormalizationTest,TransformMessagesTest,OrphanToolResultsTest,ToolCallIdsTest,LaneTransformMessagesWiringTest -Dsurefire.failIfNoSpecifiedTests=false test
   ```
   Result: `BUILD SUCCESS`; 75 tests, 0 failures, 0 errors. Breakdown: OrphanToolResults 11, Copilot/Anthropic oracle 4, id normalization 6, TransformMessages 9, lane wiring 14, ToolCallIds 31.

5. Full `pi-java-ai` reactor regression (serial, after the concurrent-build attempt):
   ```text
   export JAVA_HOME='D:\soft\jdk\graalvm-jdk-25' && /d/soft/apache-maven-3.9.9/bin/mvn -pl pi-java-ai test -am
   ```
   Result: `BUILD SUCCESS`, aggregate `Tests run: 812, Failures: 0, Errors: 0, Skipped: 0`, finished 2026-09-25 11:21:55 +08:00.

6. Checkstyle:
   ```text
   export JAVA_HOME='D:\soft\jdk\graalvm-jdk-25' && /d/soft/apache-maven-3.9.9/bin/mvn -pl pi-java-ai checkstyle:check
   ```
   Result: exit code 0 and `You have 0 Checkstyle violations.` The output still reports pre-existing warnings (corrupt/local Doxia POM parse warnings and existing source warnings); none were introduced by this task.

7. Full reactor verification:
   ```text
   export JAVA_HOME='D:\soft\jdk\graalvm-jdk-25' && /d/soft/apache-maven-3.9.9/bin/mvn clean verify
   ```
   Result: **not green; environmental failure**. The reactor reached the TUI module and then failed while a JUnit-driven session attempted to append a JSONL file under a temporary session directory. The terminal exception was:
   `DefaultJsonlFileSystem$JsonlFileException: Failed to append ...\\2026-09-25T03-19-07-778Z_ffffffff-fffd-7a01-980d-4c9a3935bf4a.jsonl`, caused by `java.nio.file.NoSuchFileException` for that same JUnit temp path. The output also showed the TUI session runner reporting the resulting drive failure. No B14 production behavior was changed in response.

8. Concurrent-build diagnostic (not treated as a valid regression result): an initial simultaneous `mvn -pl pi-java-ai test -am` and `mvn clean verify` run produced `NoClassDefFoundError: com/pijava/ai/protocol/ResponsesStreamProcessor$FunctionCallState`, consistent with both Maven processes concurrently cleaning/compiling the shared target directory. The test was rerun serially and passed with 812/812. This is recorded separately from the TUI environment failure.

9. Final static checks:
   - `git diff --check`: passed.
   - Changed B14 Java file line counts: 38 / 71 / 220 / 313; all under 500.
   - Added `System.out.println` scan: no matches.
   - Added `@SuppressWarnings` scan: no matches.

## COMMITS

One commit is required by the brief, with message:

```text
docs(ai): 包B14 步7 —— 回归、文档回填与收尾台账

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
```

The commit is created after explicit path staging and without pushing.

## TESTS

- Focused B14: 75/75 passed.
- Full `pi-java-ai` reactor: 812/812 passed.
- `pi-java-ai` checkstyle: 0 violations, command exit 0.
- Full `mvn clean verify`: environmental failure in TUI JUnit session-temp JSONL append (`NoSuchFileException`), fully recorded above.
- Diff/size/stdout/suppression checks: passed.

## CONCERNS

- Full-reactor clean verification remains blocked by the reproducible-in-this-run TUI session-temp directory failure; it is unrelated to the B14 files and was not “fixed” by changing production behavior.
- The first concurrent Maven run also exposed a shared-target classfile race; the serial reactor regression is the authoritative `pi-java-ai` result.
- Known B14 differences remain by design: B14b/R1 thoughtSignature round trip, R2 timestamps, R3/R4 Responses composite/item ids, and R5 SystemMessage/heldSystemMessages.
