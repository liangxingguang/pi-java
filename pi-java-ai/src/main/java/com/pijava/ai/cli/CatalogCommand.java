package com.pijava.ai.cli;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import com.pijava.ai.catalog.CatalogPublisher;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * 模型目录发布工具（设计 §7.2）：{@code validate} / {@code merge} / {@code publish}。
 */
@Command(name = "catalog", description = "Model catalog publish tooling",
         subcommands = {CatalogCommand.Validate.class, CatalogCommand.Merge.class,
                        CatalogCommand.Publish.class})
public final class CatalogCommand implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    /** 校验 models.json。 */
    @Command(name = "validate", description = "Validate a models.json file")
    static final class Validate implements Callable<Integer> {

        @Option(names = "--file", required = true, description = "Path to models.json")
        Path file;

        @Override
        public Integer call() throws Exception {
            var models = CatalogPublisher.parse(Files.readAllBytes(file));
            var errors = CatalogPublisher.validate(models);
            if (errors.isEmpty()) {
                System.out.println("OK: " + models.size() + " models valid");
                return 0;
            }
            errors.forEach(e -> System.err.println("error: " + e));
            return 1;
        }
    }

    /** 合并 base 与 overlay。 */
    @Command(name = "merge", description = "Merge base and overlay catalogs")
    static final class Merge implements Callable<Integer> {

        @Option(names = "--base", required = true) Path base;
        @Option(names = "--overlay", required = true) Path overlay;
        @Option(names = "--out", required = true) Path out;

        @Override
        public Integer call() throws Exception {
            var baseModels = CatalogPublisher.parse(Files.readAllBytes(base));
            var overlayModels = CatalogPublisher.parse(Files.readAllBytes(overlay));
            var merged = CatalogPublisher.merge(baseModels, overlayModels);
            Files.write(out, CatalogPublisher.toJsonBytes(merged));
            System.out.println("Merged " + merged.size() + " models -> " + out);
            return 0;
        }
    }

    /** 校验并上传。 */
    @Command(name = "publish", description = "Validate and upload a models.json")
    static final class Publish implements Callable<Integer> {

        @Option(names = "--file", required = true) Path file;
        @Option(names = "--endpoint", required = true, description = "Upload URL")
        String endpoint;

        @Override
        public Integer call() throws Exception {
            byte[] content = Files.readAllBytes(file);
            var models = CatalogPublisher.parse(content);
            var errors = CatalogPublisher.validate(models);
            if (!errors.isEmpty()) {
                errors.forEach(e -> System.err.println("error: " + e));
                return 1;
            }
            var etag = CatalogPublisher.generateEtag(content);
            CatalogPublisher.publish(URI.create(endpoint), content, etag);
            System.out.println("Published " + models.size() + " models, ETag " + etag);
            return 0;
        }
    }
}
