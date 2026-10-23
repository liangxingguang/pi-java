package com.pijava.coding.agent.core.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.pijava.coding.agent.core.bugreport.BugReport;

/**
 * 经典 ZIP 归档写出（pi {@code utils/zip.ts} 语义对位）。
 *
 * <p>批 15 裁决①：用 JDK {@link ZipOutputStream}（DEFLATED，UTF-8 名），不手卷
 * pi 的 local/central/end 三段字节格式——上报包的消费方是解压器不是字节比较器；
 * 字节级差（时间戳、flag 位、descriptor）登记窄差 D79。</p>
 */
public final class ZipArchives {

    private ZipArchives() {}

    /** pi {@code writeZipArchive}：按序写全部条目（UTF-8 内容）。 */
    public static void write(Path file, List<BugReport.ReportFile> entries) throws IOException {
        try (var zos = new ZipOutputStream(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
            for (var entry : entries) {
                zos.putNextEntry(new ZipEntry(entry.name()));
                zos.write(entry.data().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
    }
}
