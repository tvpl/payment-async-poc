# Observabilidade

O profile `observability` contém Jaeger, OTel Collector, Prometheus, Grafana e exporters de Kafka, Redis e PostgreSQL. `make verify-profiles` consulta readiness/query do Prometheus, serviços do Jaeger e health de Grafana e Kafka UI.

`observability/prometheus.yml` coleta apenas infraestrutura comum e o arquivo de service discovery `application-targets.json`. `application-assets.json` é o manifest autorizado para referências versionadas fornecidas pelos owners de aplicação.

Dashboards, alerts e SLOs de produto permanecem em `<application>/ops`. O sandbox pode montá-los/assemblá-los no futuro somente quando o manifest declarar owner, versão e caminho. Copiar esses arquivos para `sandbox/observability` mudaria ownership e é proibido.
