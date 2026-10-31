# syntax=docker/dockerfile:1
#
# 批 32（docs/44 §7.1）：Docker eval 镜像（多阶段）。
#
# D182：CI 不真实构建/运行这些镜像，只用假 DockerProcess 钉住命令契约；
# 真实构建需要 Docker、Maven 依赖缓存与网络。
#
# 镜像前缀由 DockerImageSpec.repositoryDigest 计算：
#   pi-java-evals:<sha256(repositoryRoot 内容)[0..12]>-<target>
# target: without-docs / with-docs
#
# 容器入口：java -cp /opt/pi-eval/lib/* com.pijava.evals.cli.EvalArmMain
#   --list-cases            输出 JSON 行（evalSet/case）
#   --suite/--case          单臂模式；PI_EVAL_VARIANT/PI_PROVIDER/PI_MODEL/PI_EVAL_RUN 由 docker run -e 注入
#   写入 /artifacts/tasks/<arm-id>/junit.xml 与 observations.jsonl

ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-25
ARG JRE_IMAGE=eclipse-temurin:25-jre

FROM ${MAVEN_IMAGE} AS build
WORKDIR /repo
COPY . .
ARG PI_EVAL_TARGET=with-docs
RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -DskipTests package \
 && mvn -q -pl pi-java-evals dependency:copy-dependencies \
      -DincludeScope=runtime -DoutputDirectory=/opt/pi-eval/lib \
 && cp pi-java-evals/target/pi-java-evals-*.jar /opt/pi-eval/lib/

FROM ${JRE_IMAGE} AS base
WORKDIR /opt/pi-eval
COPY --from=build /opt/pi-eval/lib /opt/pi-eval/lib
COPY --from=build /repo/docs /src/docs
COPY --from=build /repo/pi-java-examples /src/pi-java-examples
# 仓库根没有 README.md；docs 段只要求 readme 路径存在，注入最小桩文件。
RUN printf '# pi-java\n' > /src/README.md
ENV PI_JAVA_PACKAGE_DIR=/src

FROM base AS with-docs
ENTRYPOINT ["java","-cp","/opt/pi-eval/lib/*","com.pijava.evals.cli.EvalArmMain"]

FROM base AS without-docs
# 删除文档与示例，并把发行根指向无 docs 的桩目录；
# 提示变换（HarnessArmRunner）在容器内对「已无 docs 段」的提示保持原样（双保险）。
RUN rm -rf /src/docs /src/README.md /src/pi-java-examples \
 && mkdir -p /opt/pi-eval/no-docs
ENV PI_JAVA_PACKAGE_DIR=/opt/pi-eval/no-docs
ENTRYPOINT ["java","-cp","/opt/pi-eval/lib/*","com.pijava.evals.cli.EvalArmMain"]
