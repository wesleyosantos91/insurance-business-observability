# syntax=docker/dockerfile:1
FROM sonarqube:26.10.0.133078-community@sha256:f95aaa9016dd4e92c7d70691167a6d96dbb5d8acffd61eba1cb4335d364a7840 AS plugin
USER root
RUN curl --fail --location --retry 3 \
      https://github.com/devcon5ch/mutation-analysis-plugin/releases/download/v1.8/mutation-analysis-plugin-1.8.jar \
      --output /tmp/mutation-analysis-plugin-1.8.jar \
    && echo "077e6cbb5496f86bdb590a694d4a123586211245a5bc22fca2bf55595859993f  /tmp/mutation-analysis-plugin-1.8.jar" | sha256sum --check -

FROM sonarqube:26.10.0.133078-community@sha256:f95aaa9016dd4e92c7d70691167a6d96dbb5d8acffd61eba1cb4335d364a7840
USER root
COPY --from=plugin --chown=root:root --chmod=0444 /tmp/mutation-analysis-plugin-1.8.jar /opt/insurance/plugins/mutation-analysis-plugin-1.8.jar
COPY --chown=root:root --chmod=0555 entrypoint.sh /opt/insurance/entrypoint.sh
LABEL io.github.wesleyosantos91.sonar.mutation-plugin.version="1.8" \
      io.github.wesleyosantos91.sonar.mutation-plugin.sha256="077e6cbb5496f86bdb590a694d4a123586211245a5bc22fca2bf55595859993f"
RUN chmod 0555 /opt/insurance /opt/insurance/plugins
USER sonarqube
ENTRYPOINT ["/opt/insurance/entrypoint.sh"]
