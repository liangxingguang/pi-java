package com.pijava.tui.screen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.pijava.tui.util.ClipboardImage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClipboardPasteFlowTest {

    @Test
    void imageIsWrittenToTempFileAndPathInserted() throws Exception {
        var inserted = new ArrayList<String>();
        byte[] bytes = {1, 2, 3};
        ClipboardPasteFlow.paste(inserted::add, inserted::add,
            () -> Optional.of(new ClipboardImage.Image(bytes, "image/png")),
            Optional::empty);

        assertThat(inserted).hasSize(1);
        Path file = Path.of(inserted.get(0));
        assertThat(file.getFileName().toString()).startsWith("pi-java-clipboard-")
            .endsWith(".png");
        assertThat(Files.readAllBytes(file)).isEqualTo(bytes);
        Files.deleteIfExists(file);
    }

    @Test
    void textFallbackInsertsClipboardText() {
        var inserted = new ArrayList<String>();
        ClipboardPasteFlow.paste(inserted::add, inserted::add,
            Optional::empty, () -> Optional.of("hello"));
        assertThat(inserted).containsExactly("hello");
    }

    @Test
    void emptyClipboardReportsPiMessage() {
        var errors = new ArrayList<String>();
        ClipboardPasteFlow.paste(text -> { }, errors::add,
            Optional::empty, Optional::empty);
        assertThat(errors).containsExactly(
            "Failed to paste from clipboard: clipboard has no image or text");
    }

    @Test
    void readerFailureIsPrefixed() {
        var errors = new ArrayList<String>();
        ClipboardPasteFlow.paste(text -> { }, errors::add,
            () -> { throw new IllegalStateException("boom"); }, Optional::empty);
        assertThat(errors).containsExactly("Failed to paste from clipboard: boom");
    }
}
