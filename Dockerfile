# 运行阶段：JRE 21 + docker CLI（技能沙箱兄弟容器）+ python3（PPT 渲染）
# 基础镜像在 Docker Hub 直连不可达的环境用镜像代理拉取后打同名 tag：
#   docker pull docker.m.daocloud.io/library/eclipse-temurin:21-jre-jammy && docker tag … eclipse-temurin:21-jre-jammy
#   docker pull docker.m.daocloud.io/library/docker:29-cli && docker tag … docker:29-cli
FROM eclipse-temurin:21-jre-jammy
COPY --from=docker:29-cli /usr/local/bin/docker /usr/local/bin/docker
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-pip \
    && pip3 install --no-cache-dir -i https://pypi.tuna.tsinghua.edu.cn/simple python-pptx==1.0.2 requests==2.32.3 \
    && apt-get purge -y python3-pip && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
