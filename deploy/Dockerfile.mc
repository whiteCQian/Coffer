FROM quay.io/minio/aistor/mc:RELEASE.2026-09-19T15-24-59Z@sha256:23511e340cbabf07e6a8b53f9c434975708f22f3c047d6beeddc17bf1a5aed88 AS client
FROM eclipse-temurin:17-jre-jammy@sha256:8993f1aed8b25fcea7a7047a7949c1866fa558fc6830d938c22c4f13b26be9d7
COPY --from=client /usr/bin/mc /usr/local/bin/mc
USER 10001:10001
ENTRYPOINT ["/usr/local/bin/mc"]
